package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtsModelManifestValidatorTest {

    private val validUrl =
        "https://github.com/RetRo99/tts-models/releases/download/models-1/kokoro-voices.bin"

    private fun entry(
        version: String = "20260928123911-4",
        path: String = "voices.bin",
        url: String = validUrl,
        extractTo: String? = null,
    ) = TtsModelManifestEntry(
        id = "kokoro",
        version = version,
        files = listOf(
            TtsModelFile(url = url, path = path, size = 1L, sha256 = "a", extractTo = extractTo),
        ),
    )

    @Test
    fun `accepts the production manifest shape`() {
        // Given
        val entry = entry(
            path = "espeak-ng-data.zip",
            extractTo = "espeak-ng-data",
        )

        // Then
        assertNull(TtsModelManifestValidator.violation(entry))
    }

    @Test
    fun `rejects traversal in file paths`() {
        val paths = listOf(
            "../evil", "..", ".", "a/../b", "a/./b", "/abs", "a//b", "a/", "",
            "..\\evil", "a b", "%2e%2e/x",
        )
        paths.forEach { path ->
            assertNotNull(TtsModelManifestValidator.violation(entry(path = path)), path)
        }
    }

    @Test
    fun `allows nested relative paths`() {
        assertTrue(TtsModelManifestValidator.isSafeRelativePath("data/voices.bin"))
    }

    @Test
    fun `rejects unsafe extractTo folder names`() {
        listOf("..", ".", "a/b", "../x", "").forEach { extractTo ->
            val entry = entry(path = "x.zip", extractTo = extractTo)
            assertNotNull(TtsModelManifestValidator.violation(entry), extractTo)
        }
    }

    @Test
    fun `rejects unsafe version names`() {
        listOf("..", ".", "a/b", "", "v1/..").forEach { version ->
            assertNotNull(TtsModelManifestValidator.violation(entry(version = version)), version)
        }
    }

    @Test
    fun `rejects urls outside the release download path`() {
        val urls = listOf(
            "http://github.com/RetRo99/tts-models/releases/download/m/f.bin",
            "https://evil.com/RetRo99/tts-models/releases/download/m/f.bin",
            "https://github.com.evil.com/RetRo99/tts-models/releases/download/m/f.bin",
            "https://github.com/Other/tts-models/releases/download/m/f.bin",
            "https://github.com/RetRo99/tts-models/releases/download/../../x/f.bin",
            "https://github.com/RetRo99/tts-models/releases/download/m/f.bin?x=1",
            "https://github.com/RetRo99/tts-models/releases/download/f.bin",
            "file:///etc/passwd",
        )
        urls.forEach { url ->
            assertFalse(TtsModelManifestValidator.isTrustedUrl(url), url)
            assertEquals(
                "untrusted url '$url'",
                TtsModelManifestValidator.violation(entry(url = url)),
            )
        }
    }
}
