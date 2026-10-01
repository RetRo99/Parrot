package com.retro99.epub.implementation

import com.retro99.epub.implementation.zip.ZipArchive
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipArchiveTest {

    private val directory: File = Files.createTempDirectory("zip-archive").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `reads deflated entries by name`() {
        // Given
        val text = "Some chapter text ".repeat(200).toByteArray()
        val file = zip("plain.zip", entryCount = 1, extra = "chapter.xhtml" to text)

        // When
        val bytes = ZipArchive.open(RecordingSource(file.path)).use { archive ->
            archive.read("chapter.xhtml", maxBytes = 1_000_000)
        }

        // Then
        assertContentEquals(text, bytes)
    }

    @Test
    fun `skips entries over the size limit`() {
        // Given
        val file = zip("big.zip", entryCount = 1, extra = "big.xhtml" to ByteArray(2_000))

        // When
        val bytes = ZipArchive.open(RecordingSource(file.path)).use { archive ->
            archive.read("big.xhtml", maxBytes = 1_000)
        }

        // Then
        assertNull(bytes)
    }

    @Test
    fun `reads a ZIP64 archive`() {
        // Given: more than 65535 entries makes the JDK write ZIP64 end records.
        val file = zip("many.zip", entryCount = 70_000, extra = "last.xhtml" to "end".toByteArray())

        // When
        val (count, bytes) = ZipArchive.open(RecordingSource(file.path)).use { archive ->
            archive.entries.size to archive.read("last.xhtml", maxBytes = 100)
        }

        // Then
        assertEquals(70_001, count)
        assertContentEquals("end".toByteArray(), bytes)
    }

    private fun zip(name: String, entryCount: Int, extra: Pair<String, ByteArray>): File {
        val file = File(directory, name)
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            repeat(entryCount) { index ->
                output.putNextEntry(ZipEntry("filler/$index.txt"))
                output.closeEntry()
            }
            output.putNextEntry(ZipEntry(extra.first))
            output.write(extra.second)
            output.closeEntry()
        }
        return file
    }
}
