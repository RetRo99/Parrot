package com.retro99.reader.ui.tts

import java.io.File
import kotlinx.serialization.Serializable

@Serializable
internal data class PreparedChapterId(val bookId: String, val serverId: String?, val chapterHref: String)

@Serializable
internal data class PreparedVoiceSettings(val voiceId: String?, val modelVersion: String?, val rate: Float, val pitch: Float)

@Serializable
internal data class PreparedSentenceEntry(val key: String, val durationMs: Long? = null, val bytes: Long = 0)

@Serializable
internal data class PreparedChapterManifest(
    val formatVersion: Int = 1,
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

/** Context-free store. Planned keys retain sentence order and resumable total, never text. */
internal class TtsPreparedStore(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val publish: (File, File) -> Unit = { _, _ -> error("not implemented") },
) {
    fun chapterDirectory(id: PreparedChapterId): File = error("not implemented")
    fun begin(id: PreparedChapterId, settings: PreparedVoiceSettings, keys: List<String>): PreparedChapterManifest = error("not implemented")
    fun state(id: PreparedChapterId, settings: PreparedVoiceSettings): PreparedChapterState = PreparedChapterState.NotPrepared
    fun lookup(key: String): PreparedSentenceAudio? = null
    fun add(id: PreparedChapterId, key: String, audio: File, durationMs: Long) { error("not implemented") }
    fun markComplete(id: PreparedChapterId) { error("not implemented") }
    fun delete(id: PreparedChapterId) { error("not implemented") }
    fun deleteAll() { error("not implemented") }
    fun totalSize(): Long = 0
    fun enforceLimit(active: PreparedChapterId? = null, maxBytes: Long = 1024L * 1024 * 1024) { error("not implemented") }
}
