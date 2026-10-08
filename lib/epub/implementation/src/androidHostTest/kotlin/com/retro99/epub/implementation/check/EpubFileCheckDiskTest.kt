package com.retro99.epub.implementation.check

import com.retro99.epub.api.EpubFileCheck
import com.retro99.epub.api.EpubFileProblem
import com.retro99.epub.implementation.FixtureEpubs
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The checker against real files, written by the JDK's own ZIP writer and by the test builder. */
class EpubFileCheckDiskTest {

    private val directory: File = Files.createTempDirectory("epub-check").toFile()
    private val checker = EpubFileCheckerImpl()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `an epub zipped by the jdk with deflated entries is valid`() = runTest {
        assertEquals(EpubFileCheck.Valid, checker.check(FixtureEpubs.plain(directory).path))
        assertEquals(EpubFileCheck.Valid, checker.check(FixtureEpubs.readaloud(directory, audioBytes = 4096).path))
    }

    @Test
    fun `a staged file is checked by its bytes and not by its name`() = runTest {
        val staged = File(directory, "f3a9.epub.part").apply { writeBytes(TestEpub.valid(deflated = true)) }
        assertEquals(EpubFileCheck.Valid, checker.check(staged.path))
    }

    @Test
    fun `invalid files on disk are told apart`() = runTest {
        val page = File(directory, "page.epub.part").apply { writeText("<html><body>Sign in</body></html>") }
        val empty = File(directory, "empty.epub.part").apply { writeBytes(ByteArray(0)) }
        val cut = File(directory, "cut.epub.part").apply {
            writeBytes(FixtureEpubs.plain(directory).readBytes().let { bytes -> bytes.copyOf(bytes.size / 2) })
        }

        assertEquals(EpubFileCheck.NotAnEpub(EpubFileProblem.NotAZip), checker.check(page.path))
        assertEquals(EpubFileCheck.NotAnEpub(EpubFileProblem.Empty), checker.check(empty.path))
        assertEquals(EpubFileCheck.NotAnEpub(EpubFileProblem.NotAZip), checker.check(cut.path))
        assertEquals(
            EpubFileCheck.NotAnEpub(EpubFileProblem.Unreadable),
            checker.check(File(directory, "missing.epub.part").path),
        )
    }
}
