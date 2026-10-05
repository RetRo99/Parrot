package com.retro99.packs

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PackTransferTest {
    @Test fun aResumeMustMatchBothOffsetAndTotal() {
        assertEquals(40L, packResumeOffset(206, "bytes 40-99/100", 40, 100))
        assertEquals(0L, packResumeOffset(200, null, 40, 100))
        assertFails { packResumeOffset(206, "bytes 0-99/100", 40, 100) }
        assertFails { packResumeOffset(206, "bytes 40-99/101", 40, 100) }
        assertFails { packResumeOffset(206, null, 40, 100) }
        assertFails { packResumeOffset(206, "bytes 40-39/100", 40, 100) }
        assertFails { packResumeOffset(206, "bytes 40-100/100", 40, 100) }
        assertFails { packResumeOffset(206, "bytes 40-80/100", 40, 100) }
        assertFails { packResumeOffset(206, "bytes 100-99/100", 100, 100) }
    }

    @Test fun truncatedResponseKeepsWrittenBytesForResume() = runTest {
        var written = 0
        var read = false
        assertFails {
            copyPackBytes(100, 40, read = { if (read) -1 else { read = true; 20 } }, write = { _, count -> written += count }, onBytes = {})
        }
        assertEquals(20, written)
    }

    @Test fun oversizedResponseIsRejectedBeforeWriting() = runTest {
        var written = 0
        assertFails { copyPackBytes(100, 40, read = { 61 }, write = { _, count -> written += count }, onBytes = {}) }
        assertEquals(0, written)
    }

    @Test fun manifestCannotEscapePackDirectoryOrHost() {
        fun manifest(path: String, url: String) = PackManifest("dictionary-en", "v1", listOf(PackFile(path, url, 10, "a".repeat(64))))
        assertFails { manifest("../outside", "https://github.com/RetRo99/tts-models/releases/download/v1/a").validate() }
        assertFails { manifest("english.sqlite", "https://example.com/a").validate() }
        manifest("english.sqlite", "https://github.com/RetRo99/tts-models/releases/download/v1/a").validate()
    }
}
