package com.retro99.reader.ui.tts

import java.io.File

/** A synthesizer whose every call writes a WAV after doing whatever the test says. */
internal class PreparationTestSynth(var onCall: suspend (String) -> Unit = {}) : TtsSynthesizer {
    val order = mutableListOf<String>()
    override fun isReady() = true
    override suspend fun awaitReady(timeoutMs: Long) = true
    override fun availableVoices() = emptyList<TtsVoice>()
    override fun defaultVoice(): TtsVoice? = null
    override fun activeModelVersion(voiceId: String?) = "v1"
    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
        outputFile: File,
    ): TtsSynthesisResult {
        order += text
        // Written first, as a real engine streams into its output before it is done.
        outputFile.writeBytes(wavBytes(88_200))
        onCall(text)
        return TtsSynthesisResult(TtsSynthesisStatus.SUCCESS, outputFile, durationMs = 1_000)
    }
    override fun stop() = Unit
    override suspend fun release() = Unit
}

internal class PreparationTestChapters : TtsPreparationChapterStore {
    val begun = mutableMapOf<PreparedChapterId, List<String>>()
    val prepared = mutableSetOf<String>()
    val completed = mutableListOf<PreparedChapterId>()

    override fun begin(id: PreparedChapterId, settings: PreparedVoiceSettings, keys: List<String>) {
        begun[id] = keys
    }
    override fun isPrepared(id: PreparedChapterId, key: String) = key in prepared
    override fun markComplete(id: PreparedChapterId) { completed += id }
    override fun enforceLimit(active: PreparedChapterId) = Unit
}

/** A sentence source that says how long each sentence took, and can be held mid-sentence. */
internal class PreparationTestSource(
    private val chapters: PreparationTestChapters,
    private val workMs: (String) -> Long? = { text -> text.length * 30L },
) : TtsPreparationSentenceSource {
    val attempted = mutableListOf<String>()
    val prepared = mutableListOf<String>()
    var onPrepare: suspend (String) -> Unit = {}

    override fun key(text: String, settings: PreparedVoiceSettings) = "key-$text-${settings.voiceId}"

    override suspend fun prepare(
        id: PreparedChapterId,
        text: String,
        settings: PreparedVoiceSettings,
    ): PreparedAudioEncoding = prepareMeasured(id, text, settings).encoding

    override suspend fun prepareMeasured(
        id: PreparedChapterId,
        text: String,
        settings: PreparedVoiceSettings,
    ): PreparedSentenceWork {
        attempted += text
        onPrepare(text)
        prepared += text
        chapters.prepared += key(text, settings)
        return PreparedSentenceWork(PreparedAudioEncoding.Success(File("$text.m4a"), 1_000), workMs(text))
    }
}
