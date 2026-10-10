package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TtsPreparedStoreTest {
    private val root = Files.createTempDirectory("prepared-store").toFile()
    private val source = File(root.parentFile, root.name + "-source.wav").apply { writeBytes(ByteArray(2_000) { 1 }) }
    private var clock = 1_000_000L
    private val store = TtsPreparedStore(root, { clock })
    private val id = PreparedChapterId("book", "server", "chapter.xhtml")
    private val settings = PreparedVoiceSettings("system", null, 1f, 1f)
    private val key = TtsAudioCacheStore(root).key("system", null, 1f, 1f, "A sentence.")
    private val second = TtsAudioCacheStore(root).key("system", null, 1f, 1f, "Another sentence.")

    @AfterTest fun cleanup() { root.deleteRecursively(); source.delete() }

    @Test fun `partial resume and completion persist with duration and privacy safe names`() {
        store.begin(id, settings, listOf(key, second))
        store.add(id, key, source, 500)
        assertEquals(PreparedChapterState.Partial(1, 2), store.state(id, settings))
        val resumed = TtsPreparedStore(root, { clock })
        resumed.begin(id, settings, listOf(key, second))
        assertEquals(500L, assertNotNull(resumed.lookup(key)).durationMs)
        assertEquals(PreparedChapterState.Partial(1, 2), resumed.state(id, settings))
        resumed.add(id, second, source, 750)
        resumed.markComplete(id)
        assertEquals(PreparedChapterState.Ready(4_000), resumed.state(id, settings))
        val manifest = File(resumed.chapterDirectory(id), "manifest.json").readText()
        assertFalse(manifest.contains("A sentence."))
        assertFalse(manifest.contains("Another sentence."))
        assertTrue(root.walkTopDown().filter { it.isFile }.all { it.name == "manifest.json" || it.name.matches(Regex("[a-f0-9]{64}\\.m4a")) })
    }

    @Test fun `voice model rate and pitch changes show the old settings without destroying audio`() {
        store.begin(id, settings, listOf(key)); store.add(id, key, source, 500); store.markComplete(id)
        for (other in listOf(settings.copy(voiceId = "other"), settings.copy(modelVersion = "v2"), settings.copy(rate = 1.1f), settings.copy(pitch = 1.2f))) {
            assertEquals(PreparedChapterState.OtherSettings(settings, true, 1, 1), store.state(id, other))
            assertNotNull(store.lookup(key))
        }
    }

    @Test fun `atomic file publication failure leaves valid partial manifest and no half audio`() {
        store.begin(id, settings, listOf(key))
        val failing = TtsPreparedStore(root, { clock }, publish = { staging, destination ->
            assertTrue(staging.name.endsWith(".part")); assertEquals(2_000L, staging.length())
            assertFalse(destination.exists()); throw IllegalStateException("injected crash before rename")
        })
        assertFailsWith<IllegalStateException> { failing.add(id, key, source, 500) }
        assertEquals(PreparedChapterState.Partial(0, 1), store.state(id, settings))
        assertNull(store.lookup(key))
        assertEquals(listOf("manifest.json"), store.chapterDirectory(id).list()!!.toList())
    }

    @Test fun `corrupt and unknown manifests are not prepared and are cleaned without crashing`() {
        for (content in listOf("not JSON", Json.encodeToString(manifest().copy(formatVersion = 999)))) {
            val directory = store.chapterDirectory(id).apply { mkdirs() }
            File(directory, "manifest.json").writeText(content)
            File(directory, "$key.m4a").writeBytes(byteArrayOf(1))
            assertEquals(PreparedChapterState.NotPrepared, store.state(id, settings))
            assertFalse(directory.exists())
        }
    }

    @Test fun `a manifest missing its required version is rejected rather than assumed current`() {
        val directory = store.chapterDirectory(id).apply { mkdirs() }
        val json = Json { encodeDefaults = true }.encodeToString(manifest())
        File(directory, "manifest.json").writeText(json.replace("\"formatVersion\":2,", ""))
        assertEquals(PreparedChapterState.NotPrepared, store.state(id, settings))
        assertFalse(directory.exists())
    }

    @Test fun `unlisted and staging files are removed but partial entries survive restart`() {
        store.begin(id, settings, listOf(key, second)); store.add(id, key, source, 500)
        val directory = store.chapterDirectory(id)
        File(directory, "unlisted.m4a").writeText("orphan")
        File(directory, "interrupted.part").writeText("half")
        assertEquals(PreparedChapterState.Partial(1, 2), TtsPreparedStore(root).state(id, settings))
        assertEquals(setOf("manifest.json", "$key.m4a"), directory.list()!!.toSet())
    }

    @Test fun `missing or truncated listed audio cannot be served or remain complete`() {
        store.begin(id, settings, listOf(key)); store.add(id, key, source, 500); store.markComplete(id)
        File(store.chapterDirectory(id), "$key.m4a").writeText("short")
        assertNull(store.lookup(key))
        assertEquals(PreparedChapterState.Partial(0, 1), store.state(id, settings))
    }

    @Test fun `hostile identifiers and sentence keys never escape root`() {
        val hostile = PreparedChapterId("../../outside", "../server", "/absolute/../../outside")
        store.begin(hostile, settings, listOf(key))
        store.add(hostile, key, source, 500)
        assertTrue(store.chapterDirectory(hostile).canonicalPath.startsWith(root.canonicalPath + File.separator))
        assertFailsWith<IllegalArgumentException> { store.begin(id, settings, listOf("../escape")) }
        assertFailsWith<IllegalArgumentException> { store.add(hostile, "../escape", source, 500) }
        assertNull(store.lookup("../escape"))
    }

    @Test fun `symlink chapter directory is refused without altering the outside target`() {
        val outside = Files.createTempDirectory("prepared-outside").toFile()
        try {
            val folder = store.chapterDirectory(id)
            folder.parentFile.mkdirs()
            Files.createSymbolicLink(folder.toPath(), outside.toPath())
            assertFailsWith<IllegalArgumentException> { store.begin(id, settings, listOf(key)) }
            assertTrue(outside.list()!!.isEmpty())
        } finally { outside.deleteRecursively() }
    }

    @Test fun `limit evicts oldest complete first but protects active recent and partial chapters`() {
        val oldest = id.copy(chapterHref = "oldest")
        val active = id.copy(chapterHref = "active")
        val recent = id.copy(chapterHref = "recent")
        val partial = id.copy(chapterHref = "partial")
        for (chapter in listOf(oldest, id, active, recent, partial)) {
            store.begin(chapter, settings, listOf(key)); store.add(chapter, key, source, 500)
            if (chapter != partial) store.markComplete(chapter)
            clock += 1_000
        }
        // The lookup touches every chapter containing this key, so use a distinct recent key.
        val recentKey = "a".repeat(64)
        store.begin(recent, settings, listOf(recentKey)); store.add(recent, recentKey, source, 500); store.markComplete(recent)
        clock += 600_001
        store.lookup(recentKey)
        val before = store.totalSize()
        store.enforceLimit(active, before - 1)
        assertEquals(PreparedChapterState.NotPrepared, store.state(oldest, settings))
        assertIs<PreparedChapterState.Ready>(store.state(id, settings))
        store.enforceLimit(active, 0)
        assertIs<PreparedChapterState.Ready>(store.state(active, settings))
        assertIs<PreparedChapterState.Ready>(store.state(recent, settings))
        assertIs<PreparedChapterState.Partial>(store.state(partial, settings))
    }

    @Test fun `delete chapter and all update total including manifest bytes`() {
        store.begin(id, settings, listOf(key)); store.add(id, key, source, 500)
        assertTrue(store.totalSize() > 2_000)
        store.delete(id)
        assertEquals(0L, store.totalSize())
        store.begin(id, settings, listOf(key)); store.add(id, key, source, 500)
        store.deleteAll()
        assertEquals(0L, store.totalSize())
        assertNull(store.lookup(key))
    }

    @Test fun `completion refuses missing entries and repeated keys count positions but store bytes once`() {
        store.begin(id, settings, listOf(key, key, second))
        store.add(id, key, source, 500)
        assertEquals(PreparedChapterState.Partial(2, 3), store.state(id, settings))
        assertFailsWith<IllegalStateException> { store.markComplete(id) }
        store.add(id, second, source, 500); store.markComplete(id)
        assertEquals(PreparedChapterState.Ready(4_000), store.state(id, settings))
    }

    @Test fun `complete chapters are listed with the identity and settings the sweep needs`() {
        // Given: one finished chapter, and one still being prepared.
        store.begin(id, settings, listOf(key, second))
        store.add(id, key, source, 500)
        store.add(id, second, source, 750)
        store.markComplete(id)
        clock += 1_000
        val other = PreparedChapterId("other-book", null, "two.xhtml")
        val otherSettings = PreparedVoiceSettings("other-voice", "v2", 1.25f, 0.9f)
        store.begin(other, otherSettings, listOf(key))
        store.add(other, key, source, 500)
        store.markComplete(other)
        val unfinished = PreparedChapterId("book", "server", "three.xhtml")
        store.begin(unfinished, settings, listOf(key, second))
        store.add(unfinished, key, source, 500)

        // When
        val listed = store.completeChapters()

        // Then: the hashed folder names cannot be read backwards, so the
        // identity has to come out of the manifests, and it does.
        assertEquals(listOf(id, other), listed.map { it.id })
        assertEquals(listOf(settings, otherSettings), listed.map { it.settings })
        assertEquals(store.chapterDirectory(id), listed.first().folder)
        assertEquals(4_000L, listed.first().totalBytes)
        assertTrue(listed.none { it.id == unfinished }, "a partly prepared chapter is never listed")
    }

    @Test fun `nothing prepared lists nothing`() {
        assertEquals(emptyList(), store.completeChapters())
    }

    private fun manifest() = PreparedChapterManifest(bookId = id.bookId, serverId = id.serverId,
        chapterHref = id.chapterHref, voiceId = settings.voiceId, modelVersion = null, rate = 1f,
        pitch = 1f, createdTimeMs = clock, sentences = listOf(PreparedSentenceEntry(key)))
}
