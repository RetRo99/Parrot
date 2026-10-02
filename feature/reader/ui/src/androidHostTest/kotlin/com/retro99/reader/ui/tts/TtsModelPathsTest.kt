package com.retro99.reader.ui.tts

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TtsModelPathsTest {

    private val root: File = Files.createTempDirectory("tts-paths").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `resolves plain and nested relative paths inside the directory`() {
        // When
        val plain = root.resolveInside("model.int8.onnx")
        val nested = root.resolveInside("data/voices.bin")

        // Then
        assertEquals(File(root.canonicalFile, "model.int8.onnx"), plain)
        assertEquals(File(root.canonicalFile, "data/voices.bin"), nested)
    }

    @Test
    fun `rejects paths escaping the directory`() {
        // Given
        val escapes = listOf("..", "../x", "a/../../x", ".", "a/..", "../${root.name}x/f")

        // Then
        escapes.forEach { path ->
            assertFailsWith<IOException>(path) { root.resolveInside(path) }
        }
    }

    @Test
    fun `keeps absolute child paths under the directory`() {
        // When
        val resolved = root.resolveInside("/etc/passwd")

        // Then
        assertEquals(File(root.canonicalFile, "etc/passwd"), resolved)
    }

    @Test
    fun `rejects symlinks pointing outside the directory`() {
        // Given
        val outside = Files.createTempDirectory("tts-outside").toFile()
        try {
            Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())

            // Then
            assertFailsWith<IOException> { root.resolveInside("link/file") }
        } finally {
            outside.deleteRecursively()
        }
    }
}
