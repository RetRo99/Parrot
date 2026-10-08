package com.retro99.epub.implementation.check

import com.retro99.epub.api.EpubFileCheck
import com.retro99.epub.api.EpubFileProblem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The checker against real files, through the iOS file reader and zlib. */
@OptIn(ExperimentalForeignApi::class)
class EpubFileCheckDiskIosTest {

    private val directory = "${NSTemporaryDirectory()}epub-check-${NSUUID().UUIDString}"
    private val checker = EpubFileCheckerImpl()

    init {
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
    }

    @AfterTest
    fun tearDown() {
        NSFileManager.defaultManager.removeItemAtPath(directory, null)
    }

    @Test
    fun `a staged epub with deflated entries is valid`() = runTest {
        assertEquals(EpubFileCheck.Valid, checker.check(write("a.epub.part", TestEpub.valid(deflated = true))))
        assertEquals(EpubFileCheck.Valid, checker.check(write("b.epub.part", TestEpub.valid(deflated = false))))
    }

    @Test
    fun `invalid files on disk are told apart`() = runTest {
        val whole = TestEpub.valid()
        val aes = TestEpub.encryption("http://www.w3.org/2001/04/xmlenc#aes256-cbc", "OEBPS/chapter1.xhtml")
        val protected = TestZip.build(TestEpub.entries() + TestEntry("META-INF/encryption.xml", aes, deflated = true))

        assertEquals(
            EpubFileCheck.NotAnEpub(EpubFileProblem.NotAZip),
            checker.check(write("page.epub.part", "<html><body>Sign in</body></html>".encodeToByteArray())),
        )
        assertEquals(EpubFileCheck.NotAnEpub(EpubFileProblem.Empty), checker.check(write("empty.epub.part", ByteArray(0))))
        assertEquals(
            EpubFileCheck.NotAnEpub(EpubFileProblem.NotAZip),
            checker.check(write("cut.epub.part", whole.copyOf(whole.size / 2))),
        )
        assertEquals(EpubFileCheck.Protected, checker.check(write("drm.epub.part", protected)))
        assertEquals(EpubFileCheck.NotAnEpub(EpubFileProblem.Unreadable), checker.check("$directory/missing.epub.part"))
    }

    private fun write(name: String, bytes: ByteArray): String {
        val path = "$directory/$name"
        val file = fopen(path, "wb") ?: error("Could not create $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file) }
            }
        } finally {
            fclose(file)
        }
        return path
    }
}
