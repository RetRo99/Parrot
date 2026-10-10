package com.retro99.reader.ui.tts

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the owner's revised step 1 decision: prepared audio is AAC in an `.m4a` file, the
 * encoder's own padding is accepted, the manifest keeps the encoded duration, and there is
 * no silent fallback to a copied WAV inside a chapter.
 */
class PreparedAudioFormatTest {
    private val root = Files.createTempDirectory("prepared-format").toFile()
    private val store = TtsPreparedStore(root, { 1_000_000L })
    private val id = PreparedChapterId("book", "server", "chapter.xhtml")
    private val settings = PreparedVoiceSettings("system", null, 1f, 1f)
    private val key = TtsAudioCacheStore(root).key("system", null, 1f, 1f, "A sentence.")

    @AfterTest fun cleanup() { root.deleteRecursively() }

    @Test fun `the production encoder publishes nothing rather than copying the WAV it cannot encode`() = runTest {
        val folder = File(root, "encode").apply { mkdirs() }
        val input = File(folder, "source.wav")
        val output = File(folder, "prepared.m4a")
        input.writeBytes(wav())
        // The host has no MediaCodec, so the native encoder must fail this sentence.
        assertEquals(PreparedAudioEncoding.Failure, AndroidTtsPreparedAacEncoder().encode(input, output))
        assertFalse(output.exists())
        assertContentEquals(wav(), input.readBytes())
        assertEquals(setOf("source.wav"), folder.list()!!.toSet())
    }

    @Test fun `the store names prepared audio m4a and serves the duration the encoder measured`() {
        val encoded = File(root.parentFile, root.name + "-encoded").apply { writeBytes(ByteArray(11_190) { 7 }) }
        try {
            store.begin(id, settings, listOf(key))
            // The AAC file is longer than its WAV source by the encoder's padding; the
            // manifest records what the encoder measured, not the PCM duration.
            store.add(id, key, encoded, 1_706)
            val served = assertNotNull(store.lookup(key))
            assertEquals("$key.m4a", served.file.name)
            assertEquals(1_706L, served.durationMs)
            assertEquals(11_190L, served.file.length())
            assertEquals(setOf("manifest.json", "$key.m4a"), store.chapterDirectory(id).list()!!.toSet())
        } finally { encoded.delete() }
    }

    @Test fun `a prepared WAV chapter from the previous build is not prepared and is cleaned up`() {
        val folder = store.chapterDirectory(id).apply { mkdirs() }
        val manifest = PreparedChapterManifest(
            bookId = id.bookId, serverId = id.serverId, chapterHref = id.chapterHref,
            voiceId = settings.voiceId, modelVersion = null, rate = 1f, pitch = 1f,
            createdTimeMs = 1_000_000L, sentences = listOf(PreparedSentenceEntry(key, 1_602, 76_970)),
            complete = true, totalBytes = 76_970,
        )
        val encoded = json.encodeToString(manifest)
        File(folder, "manifest.json").writeText(encoded.replace("\"formatVersion\":2,", "\"formatVersion\":1,"))
        File(folder, "$key.wav").writeBytes(ByteArray(76_970) { 1 })
        assertEquals(PreparedChapterState.NotPrepared, store.state(id, settings))
        assertFalse(folder.exists())
    }

    @Test fun `the current manifest format version is the AAC one`() {
        store.begin(id, settings, listOf(key))
        assertTrue(File(store.chapterDirectory(id), "manifest.json").readText().contains("\"formatVersion\":2"))
    }

    private val json = Json { encodeDefaults = true }

    private fun wav(): ByteArray = ByteBuffer.allocate(48_044).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(48_036); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(24_000); putInt(48_000)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(48_000)
    }.array()
}
