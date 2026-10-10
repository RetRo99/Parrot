package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Prepared audio is AAC in an MPEG-4 container; version 2 manifests describe `.m4a` files. */
internal const val PREPARED_AUDIO_EXTENSION = "m4a"
internal const val PREPARED_MANIFEST_VERSION = 2

@Serializable
internal data class PreparedChapterId(val bookId: String, val serverId: String?, val chapterHref: String)

@Serializable
internal data class PreparedVoiceSettings(val voiceId: String?, val modelVersion: String?, val rate: Float, val pitch: Float)

@Serializable
internal data class PreparedSentenceEntry(val key: String, val durationMs: Long? = null, val bytes: Long = 0)

@Serializable
internal data class PreparedChapterManifest(
    @Required val formatVersion: Int = PREPARED_MANIFEST_VERSION,
    val bookId: String,
    val serverId: String?,
    val chapterHref: String,
    val voiceId: String?,
    val modelVersion: String?,
    val rate: Float,
    val pitch: Float,
    val createdTimeMs: Long,
    val sentences: List<PreparedSentenceEntry>,
    val complete: Boolean = false,
    val totalBytes: Long = 0,
    val lastUsedTimeMs: Long? = null,
)

internal sealed interface PreparedChapterState {
    data object NotPrepared : PreparedChapterState
    data class Partial(val done: Int, val total: Int) : PreparedChapterState
    data class Ready(val bytes: Long) : PreparedChapterState
    data class OtherSettings(val settings: PreparedVoiceSettings, val complete: Boolean, val done: Int, val total: Int) : PreparedChapterState
}

internal data class PreparedSentenceAudio(val file: File, val durationMs: Long)

/**
 * One finished chapter as the backup sweep needs it. The folder names are
 * hashes and cannot be read backwards, but every manifest carries the book id,
 * server id, chapter href and settings it was made for, so the sweep recovers
 * the full identity from the store itself rather than from anything on screen.
 */
internal data class PreparedChapterSummary(
    val id: PreparedChapterId,
    val settings: PreparedVoiceSettings,
    val folder: File,
    val totalBytes: Long,
)

/** Context-free store. Planned keys retain sentence order and resumable total, never text. */
internal class TtsPreparedStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val publish: (File, File) -> Unit = { staging, destination ->
        Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
) {
    private val json = Json { encodeDefaults = true }

    fun chapterDirectory(id: PreparedChapterId): File {
        val server = id.serverId.orEmpty()
        val book = File(root, hash("${id.serverId?.length ?: -1}:$server${id.bookId}"))
        val chapter = File(book, hash(id.chapterHref))
        require(!Files.isSymbolicLink(book.toPath()) && !Files.isSymbolicLink(chapter.toPath())) { "Unsafe prepared path" }
        require(chapter.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Prepared path escaped root" }
        return chapter
    }

    @Synchronized
    fun begin(id: PreparedChapterId, settings: PreparedVoiceSettings, keys: List<String>): PreparedChapterManifest {
        require(keys.isNotEmpty() && keys.all(::validKey)) { "Invalid sentence keys" }
        require(settings.rate.isFinite() && settings.pitch.isFinite() && settings.rate > 0 && settings.pitch > 0)
        val folder = chapterDirectory(id)
        val existing = read(folder)
        if (existing != null && sameSettings(existing.settings(), settings) && existing.sentences.map { it.key } == keys) return existing
        remove(folder)
        check(folder.mkdirs() || folder.isDirectory)
        return PreparedChapterManifest(
            bookId = id.bookId, serverId = id.serverId, chapterHref = id.chapterHref,
            voiceId = settings.voiceId, modelVersion = settings.modelVersion, rate = settings.rate, pitch = settings.pitch,
            createdTimeMs = now(), sentences = keys.map(::PreparedSentenceEntry),
        ).also { write(folder, it) }
    }

    @Synchronized
    fun state(id: PreparedChapterId, settings: PreparedVoiceSettings): PreparedChapterState {
        val manifest = read(chapterDirectory(id)) ?: return PreparedChapterState.NotPrepared
        val done = manifest.sentences.count { it.durationMs != null }
        if (!sameSettings(manifest.settings(), settings)) {
            return PreparedChapterState.OtherSettings(manifest.settings(), manifest.complete, done, manifest.sentences.size)
        }
        return if (manifest.complete) PreparedChapterState.Ready(manifest.totalBytes)
        else PreparedChapterState.Partial(done, manifest.sentences.size)
    }

    @Synchronized
    fun lookup(id: PreparedChapterId, key: String, markUsed: Boolean = false): PreparedSentenceAudio? {
        if (!validKey(key)) return null
        val folder = chapterDirectory(id)
        val manifest = read(folder) ?: return null
        val sentence = manifest.sentences.firstOrNull { it.key == key && it.durationMs != null } ?: return null
        if (markUsed) write(folder, manifest.copy(lastUsedTimeMs = now()))
        return PreparedSentenceAudio(File(folder, "$key.$PREPARED_AUDIO_EXTENSION"), checkNotNull(sentence.durationMs))
    }

    @Synchronized
    fun lookup(key: String, markUsed: Boolean = true): PreparedSentenceAudio? {
        if (!validKey(key)) return null
        for ((folder, manifest) in chapters()) {
            val sentence = manifest.sentences.firstOrNull { it.key == key && it.durationMs != null } ?: continue
            if (markUsed) write(folder, manifest.copy(lastUsedTimeMs = now()))
            return PreparedSentenceAudio(File(folder, "$key.$PREPARED_AUDIO_EXTENSION"), checkNotNull(sentence.durationMs))
        }
        return null
    }

    @Synchronized
    fun add(id: PreparedChapterId, key: String, audio: File, durationMs: Long) {
        require(validKey(key)) { "Invalid sentence key" }
        require(audio.isFile && audio.length() > 0 && durationMs > 0)
        val folder = chapterDirectory(id)
        val manifest = checkNotNull(read(folder)) { "Chapter has not begun" }
        require(manifest.sentences.any { it.key == key }) { "Unplanned sentence key" }
        if (manifest.sentences.any { it.key == key && it.durationMs != null }) return
        val staging = File.createTempFile("audio-", ".part", folder)
        try {
            Files.copy(audio.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val size = staging.length()
            check(size > 0)
            publish(staging, File(folder, "$key.$PREPARED_AUDIO_EXTENSION"))
            val entries = manifest.sentences.map { if (it.key == key) PreparedSentenceEntry(key, durationMs, size) else it }
            write(folder, manifest.copy(sentences = entries, complete = false, totalBytes = payloadBytes(entries)))
        } finally { staging.delete() }
    }

    @Synchronized
    fun markComplete(id: PreparedChapterId) {
        val folder = chapterDirectory(id)
        val manifest = checkNotNull(read(folder))
        check(manifest.sentences.all { it.durationMs != null }) { "Chapter still has missing sentences" }
        write(folder, manifest.copy(complete = true))
    }

    @Synchronized
    fun delete(id: PreparedChapterId) { remove(chapterDirectory(id)) }

    @Synchronized
    fun deleteAll() { root.listFiles()?.forEach(::remove) }

    /**
     * Every chapter that finished preparing, newest last. Only complete ones:
     * a partly prepared chapter is never packed or uploaded.
     */
    @Synchronized
    fun completeChapters(): List<PreparedChapterSummary> = chapters()
        .filter { (_, manifest) -> manifest.complete }
        .sortedBy { (_, manifest) -> manifest.createdTimeMs }
        .map { (folder, manifest) ->
            PreparedChapterSummary(
                id = PreparedChapterId(manifest.bookId, manifest.serverId, manifest.chapterHref),
                settings = manifest.settings(),
                folder = folder,
                totalBytes = manifest.totalBytes,
            )
        }

    /** Includes manifests, not just the payload total in each manifest. */
    @Synchronized
    fun totalSize(): Long = chapters().sumOf { (folder, manifest) -> manifest.totalBytes + File(folder, "manifest.json").length() }

    @Synchronized
    fun enforceLimit(active: PreparedChapterId? = null, maxBytes: Long = 1024L * 1024 * 1024) {
        require(maxBytes >= 0)
        val chapters = chapters()
        var total = chapters.sumOf { (folder, manifest) -> manifest.totalBytes + File(folder, "manifest.json").length() }
        val activeFolder = active?.let(::chapterDirectory)
        val keepAfter = now() - 600_000
        for ((folder, manifest) in chapters.sortedBy { it.second.createdTimeMs }) {
            if (total <= maxBytes) break
            if (!manifest.complete || folder == activeFolder || manifest.lastUsedTimeMs?.let { it >= keepAfter } == true) continue
            val size = manifest.totalBytes + File(folder, "manifest.json").length()
            remove(folder)
            if (!folder.exists()) total -= size
        }
    }

    private fun chapters(): List<Pair<File, PreparedChapterManifest>> = buildList {
        for (book in root.listFiles().orEmpty()) {
            if (Files.isSymbolicLink(book.toPath())) { book.delete(); continue }
            if (!book.isDirectory) { book.delete(); continue }
            for (folder in book.listFiles().orEmpty()) {
                if (Files.isSymbolicLink(folder.toPath())) { folder.delete(); continue }
                if (!folder.isDirectory) { folder.delete(); continue }
                read(folder)?.let { add(folder to it) }
            }
        }
    }

    private fun read(folder: File): PreparedChapterManifest? {
        if (!folder.exists()) return null
        val file = File(folder, "manifest.json")
        val manifest = try {
            require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..8_388_608)
            val parsed = json.decodeFromString<PreparedChapterManifest>(file.readText())
            require(parsed.formatVersion == PREPARED_MANIFEST_VERSION && parsed.sentences.isNotEmpty())
            require(parsed.rate.isFinite() && parsed.pitch.isFinite() && parsed.rate > 0 && parsed.pitch > 0)
            require(parsed.totalBytes >= 0 && parsed.sentences.all {
                validKey(it.key) && (it.durationMs == null && it.bytes == 0L || it.durationMs != null && it.durationMs > 0 && it.bytes > 0)
            })
            require(chapterDirectory(PreparedChapterId(parsed.bookId, parsed.serverId, parsed.chapterHref)).canonicalFile == folder.canonicalFile)
            require(parsed.totalBytes == payloadBytes(parsed.sentences))
            require(parsed.sentences.groupBy { it.key }.values.all { entries -> entries.distinct().size == 1 })
            parsed
        } catch (_: Exception) {
            runCatching { remove(folder) }
            return null
        }
        val entries = manifest.sentences.map { entry ->
            val audio = File(folder, "${entry.key}.$PREPARED_AUDIO_EXTENSION")
            if (entry.durationMs != null && !Files.isSymbolicLink(audio.toPath()) && audio.isFile && audio.length() == entry.bytes) entry
            else PreparedSentenceEntry(entry.key)
        }
        val listed = entries.filter { it.durationMs != null }.map { "${it.key}.$PREPARED_AUDIO_EXTENSION" }.toSet() + "manifest.json"
        folder.listFiles()?.filter { it.name !in listed }?.forEach(::remove)
        val reconciled = manifest.copy(
            sentences = entries, complete = manifest.complete && entries.all { it.durationMs != null }, totalBytes = payloadBytes(entries),
        )
        if (reconciled != manifest) write(folder, reconciled)
        return reconciled
    }

    private fun write(folder: File, manifest: PreparedChapterManifest) {
        val staging = File.createTempFile("manifest-", ".part", folder)
        try {
            staging.writeText(json.encodeToString(manifest))
            Files.move(staging.toPath(), File(folder, "manifest.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { staging.delete() }
    }

    private fun remove(file: File) {
        if (Files.isSymbolicLink(file.toPath())) {
            check(file.delete()) { "Cannot remove prepared symlink" }
            return
        }
        if (file.isDirectory) file.listFiles()?.forEach(::remove)
        check(!file.exists() || file.delete()) { "Cannot remove prepared file" }
    }

    private fun PreparedChapterManifest.settings() = PreparedVoiceSettings(voiceId, modelVersion, rate, pitch)
    private fun sameSettings(first: PreparedVoiceSettings, second: PreparedVoiceSettings) =
        first.voiceId.orEmpty() == second.voiceId.orEmpty() && first.modelVersion.orEmpty() == second.modelVersion.orEmpty() &&
            (first.rate * 100).toInt() == (second.rate * 100).toInt() && (first.pitch * 100).toInt() == (second.pitch * 100).toInt()
    private fun payloadBytes(entries: List<PreparedSentenceEntry>) = entries.distinctBy { it.key }.sumOf { it.bytes }
    private fun validKey(key: String) = key.matches(Regex("[a-f0-9]{64}"))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
