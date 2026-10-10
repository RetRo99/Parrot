package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PreparedAudioFormatTest {
    @Test
    fun `phone duration gate fallback publishes a byte identical WAV with exact duration`() = runTest {
        val root = Files.createTempDirectory("prepared-format").toFile()
        try {
            val input = File(root, "source.wav")
            val output = File(root, "prepared.wav")
            val bytes = ByteBuffer.allocate(48_044).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(48_036); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(1); putInt(24_000); putInt(48_000)
                putShort(2); putShort(16); put("data".toByteArray()); putInt(48_000)
            }.array()
            input.writeBytes(bytes)
            val result = assertIs<PreparedAudioEncoding.Success>(AndroidTtsPreparedAudioEncoder().encode(input, output))
            assertEquals(1_000L, result.durationMs)
            assertContentEquals(bytes, output.readBytes())
            assertContentEquals(bytes, input.readBytes())
            assertEquals(setOf("source.wav", "prepared.wav"), root.list()!!.toSet())
        } finally { root.deleteRecursively() }
    }
}
