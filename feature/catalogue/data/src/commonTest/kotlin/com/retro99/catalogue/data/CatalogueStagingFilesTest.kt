package com.retro99.catalogue.data

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A staging store over a new temporary folder, and a way to remove that folder. */
internal expect fun newTestStagingFiles(): Pair<CatalogueStagingFiles, () -> Unit>

/** The real file store of each platform, on disk. */
class CatalogueStagingFilesTest {
    private val created = newTestStagingFiles()
    private val files = created.first

    @AfterTest
    fun tearDown() = created.second()

    @Test
    fun `a part path is generated per profile and ends in epub part`() {
        // When
        val first = files.newPartPath("profile-1")
        val second = files.newPartPath("profile-1")
        val traversal = files.newPartPath("../../escape")

        // Then
        assertNotEquals(first, second)
        assertTrue(first.endsWith(".epub.part"))
        assertTrue("/catalogue_staging/profile-1/" in first)
        assertFalse(".." in traversal, "a profile id cannot move the file out of staging")
        assertTrue(Regex("/[0-9a-f-]{36}\\.epub\\.part$").containsMatchIn(first))
        assertEquals(first.removeSuffix(".part"), CatalogueStagingFiles.stagedPathFor(first))
    }

    @Test
    fun `the folders of profiles that are gone are removed with their files and the others stay`() = runTest {
        // Given
        val kept = files.newPartPath("profile-1")
        val gone = files.newPartPath("profile-2")
        listOf(kept, gone).forEach { path -> files.openForWriting(path).apply { write("abc".encodeToByteArray(), 3) }.close() }

        // When
        files.deleteFoldersExcept(setOf("profile-1", "profile-3"))

        // Then
        assertEquals(listOf(kept), files.list("profile-1"))
        assertEquals(emptyList(), files.list("profile-2"))
        assertEquals(0L, files.size(gone))

        // And a new download for a profile whose folder was removed still works
        val again = files.newPartPath("profile-2")
        files.openForWriting(again).apply { write("abc".encodeToByteArray(), 3) }.close()
        assertEquals(listOf(again), files.list("profile-2"))
    }

    @Test
    fun `bytes are written in chunks and the size and hash are read back from disk`() = runTest {
        // Given
        val path = files.newPartPath("profile-1")
        val writer = files.openForWriting(path)

        // When: only the first [length] bytes of each buffer count
        writer.write("abcXX".encodeToByteArray(), 3)
        writer.write(ByteArray(0), 0)
        writer.close()

        // Then: SHA-256 of "abc"
        assertEquals(3L, files.size(path))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", files.sha256(path))
    }

    @Test
    fun `a large file is hashed across buffer boundaries`() = runTest {
        // Given: one million times the letter a, written in uneven chunks
        val path = files.newPartPath("profile-1")
        val writer = files.openForWriting(path)
        val chunk = ByteArray(70_001) { 'a'.code.toByte() }
        var left = 1_000_000

        // When
        while (left > 0) {
            val length = minOf(left, chunk.size)
            writer.write(chunk, length)
            left -= length
        }
        writer.close()

        // Then
        assertEquals(1_000_000L, files.size(path))
        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", files.sha256(path))
    }

    @Test
    fun `opening a path again starts the file from zero`() = runTest {
        // Given
        val path = files.newPartPath("profile-1")
        files.openForWriting(path).apply { write(ByteArray(500), 500) }.close()

        // When
        files.openForWriting(path).apply { write(ByteArray(20), 20) }.close()

        // Then
        assertEquals(20L, files.size(path))
    }

    @Test
    fun `an empty file hashes as empty and a missing file has no size`() = runTest {
        // Given
        val path = files.newPartPath("profile-1")
        files.openForWriting(path).close()

        // Then
        assertEquals(0L, files.size(path))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", files.sha256(path))
        assertEquals(0L, files.size(files.newPartPath("profile-1")))
    }

    @Test
    fun `a checked file is renamed to its staged name and can be deleted`() = runTest {
        // Given
        val part = files.newPartPath("profile-1")
        val staged = CatalogueStagingFiles.stagedPathFor(part)
        files.openForWriting(part).apply { write(ByteArray(64), 64) }.close()

        // When
        val renamed = files.rename(part, staged)

        // Then
        assertTrue(renamed)
        assertEquals(64L, files.size(staged))
        assertEquals(0L, files.size(part))
        assertFalse(files.rename(part, staged), "there is nothing left to rename")

        // When
        files.delete(staged)
        files.delete(staged)

        // Then
        assertEquals(0L, files.size(staged))
    }

    @Test
    fun `free space is reported`() = runTest {
        val free = assertNotNull(files.freeSpaceBytes())
        assertTrue(free > 0)
    }

    @Test
    fun `the files of one profile are listed and the other profile's are not`() = runTest {
        // Given
        val part = files.newPartPath("profile-1")
        val staged = CatalogueStagingFiles.stagedPathFor(files.newPartPath("profile-1"))
        val other = files.newPartPath("profile-2")
        listOf(part, staged, other).forEach { path ->
            files.openForWriting(path).apply {
                write("x".encodeToByteArray(), 1)
                close()
            }
        }

        // Then
        assertEquals(setOf(part, staged), files.list("profile-1").toSet())
        assertEquals(listOf(other), files.list("profile-2"))
        assertTrue(files.list("profile-never-used").isEmpty())

        // When
        files.delete(part)

        // Then
        assertEquals(listOf(staged), files.list("profile-1"))
    }
}
