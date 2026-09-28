package com.retro99.reader.ui.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TtsModelManifestTest {

    @Test
    fun `parses models and file metadata`() {
        // Given
        val raw = """
            {
              "schemaVersion": 1,
              "models": [
                {
                  "id": "kokoro",
                  "version": "int8-en-v0_19",
                  "files": [
                    {
                      "url": "https://example.com/kokoro-model.int8.onnx",
                      "path": "model.int8.onnx",
                      "size": 100,
                      "sha256": "abc"
                    },
                    {
                      "url": "https://example.com/kokoro-espeak-ng-data.zip",
                      "path": "espeak-ng-data.zip",
                      "size": 50,
                      "sha256": "def",
                      "extractTo": "espeak-ng-data"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        // When
        val manifest = TtsModelManifest.parse(raw)

        // Then
        requireNotNull(manifest)
        assertEquals(1, manifest.schemaVersion)
        val model = requireNotNull(manifest.model("kokoro"))
        assertEquals("int8-en-v0_19", model.version)
        assertEquals(150L, model.totalBytes)

        val plainFile = model.files[0]
        assertEquals("https://example.com/kokoro-model.int8.onnx", plainFile.url)
        assertEquals("model.int8.onnx", plainFile.path)
        assertEquals(100L, plainFile.size)
        assertEquals("abc", plainFile.sha256)
        assertNull(plainFile.extractTo)

        val archiveFile = model.files[1]
        assertEquals("espeak-ng-data", archiveFile.extractTo)
    }

    @Test
    fun `ignores unknown keys`() {
        // Given
        val raw = """
            {
              "schemaVersion": 2,
              "futureField": true,
              "models": [
                {
                  "id": "supertonic",
                  "version": "int8-2026-05-11",
                  "futureField": 42,
                  "files": [
                    {
                      "url": "https://example.com/vocoder.int8.onnx",
                      "path": "vocoder.int8.onnx",
                      "size": 20,
                      "sha256": "aaa",
                      "futureField": null
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        // When
        val manifest = TtsModelManifest.parse(raw)

        // Then
        val model = requireNotNull(manifest).model("supertonic")
        requireNotNull(model)
        assertEquals(1, model.files.size)
        assertEquals(20L, model.totalBytes)
    }

    @Test
    fun `returns null for malformed json`() {
        // Given
        val raw = "not json"

        // When
        val manifest = TtsModelManifest.parse(raw)

        // Then
        assertNull(manifest)
    }

    @Test
    fun `model lookup returns null for unknown id`() {
        // Given
        val raw = """
            {
              "schemaVersion": 1,
              "models": []
            }
        """.trimIndent()

        // When
        val manifest = TtsModelManifest.parse(raw)

        // Then
        assertNull(requireNotNull(manifest).model("kokoro"))
    }
}
