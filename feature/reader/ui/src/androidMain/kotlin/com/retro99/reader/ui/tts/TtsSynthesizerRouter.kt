package com.retro99.reader.ui.tts

import org.koin.core.annotation.Single

import java.io.File

@Single(binds = [TtsSynthesizer::class])
class TtsSynthesizerRouter(
    private val systemSynthesizer: AndroidSystemTtsSynthesizer,
    private val kokoroSynthesizer: SherpaOnnxSynthesizer,
    private val supertonicSynthesizer: SupertonicOnnxSynthesizer,
) : TtsSynthesizer {

    override fun isReady(): Boolean =
        systemSynthesizer.isReady() ||
                kokoroSynthesizer.isReady() ||
                supertonicSynthesizer.isReady()

    override suspend fun awaitReady(timeoutMs: Long): Boolean = awaitSynthesizerReady(
        timeoutMs = timeoutMs,
        isNeuralPackUsable = {
            kokoroSynthesizer.availableVoices().any { voice -> voice.isDownloaded } ||
                    supertonicSynthesizer.availableVoices().any { voice -> voice.isDownloaded }
        },
        awaitSystemReady = { timeout -> systemSynthesizer.awaitReady(timeout) },
    )

    override fun availableVoices(): List<TtsVoice> =
        systemSynthesizer.availableVoices() +
                kokoroSynthesizer.availableVoices() +
                supertonicSynthesizer.availableVoices()

    override fun defaultVoice(): TtsVoice =
        systemSynthesizer.defaultVoice() ?: kokoroSynthesizer.defaultVoice()

    override suspend fun prepareVoice(
        voiceId: String?,
        updateToLatest: Boolean,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean = when (voiceId.neuralVoicePackage()) {
        NeuralVoicePackage.KOKORO -> {
            kokoroSynthesizer.prepareVoice(voiceId, updateToLatest, onProgress)
        }

        NeuralVoicePackage.SUPERTONIC -> {
            supertonicSynthesizer.prepareVoice(voiceId, updateToLatest, onProgress)
        }

        null -> systemSynthesizer.awaitReady()
    }

    override fun activeModelVersion(voiceId: String?): String? =
        when (voiceId.neuralVoicePackage()) {
            NeuralVoicePackage.KOKORO -> kokoroSynthesizer.activeModelVersion(voiceId)
            NeuralVoicePackage.SUPERTONIC -> supertonicSynthesizer.activeModelVersion(voiceId)
            null -> null
        }

    override suspend fun warmUp(voiceId: String?): Boolean =
        when (voiceId.neuralVoicePackage()) {
            NeuralVoicePackage.KOKORO -> kokoroSynthesizer.warmUp(voiceId)
            NeuralVoicePackage.SUPERTONIC -> supertonicSynthesizer.warmUp(voiceId)
            null -> false
        }

    override suspend fun deleteNeuralVoicePackage(
        voicePackage: NeuralVoicePackage,
    ): Boolean = when (voicePackage) {
        NeuralVoicePackage.KOKORO -> {
            kokoroSynthesizer.deleteNeuralVoicePackage(voicePackage)
        }

        NeuralVoicePackage.SUPERTONIC -> {
            supertonicSynthesizer.deleteNeuralVoicePackage(voicePackage)
        }
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
        outputFile: File,
    ): TtsSynthesisResult {
        val synthesizer = when (voiceId.neuralVoicePackage()) {
            NeuralVoicePackage.KOKORO -> kokoroSynthesizer
            NeuralVoicePackage.SUPERTONIC -> supertonicSynthesizer
            null -> systemSynthesizer
        }
        val engineName = voiceId.neuralVoicePackage()?.name ?: "SYSTEM"
        android.util.Log.i(
            "TtsRouter",
            "synthesize voice=$voiceId engine=$engineName",
        )
        return synthesizer.synthesize(text, voiceId, rate, pitch, outputFile)
    }

    override fun stop() {
        systemSynthesizer.stop()
        kokoroSynthesizer.stop()
        supertonicSynthesizer.stop()
    }

    override suspend fun release() {
        systemSynthesizer.release()
        kokoroSynthesizer.release()
        supertonicSynthesizer.release()
    }
}

/**
 * Whether anything can speak, for the readiness gate in front of the word speaker and the
 * voice list. The router's three engines all need Android, so the decision lives here, apart
 * from them, and is tested on the host.
 *
 * An installed, usable neural pack is ready at once: it needs no system engine, and waiting on
 * one that is missing or slow to initialise is what hid the word speaker on a neural-only
 * phone. With no neural pack the system engine is waited for, as before.
 */
internal suspend fun awaitSynthesizerReady(
    timeoutMs: Long,
    isNeuralPackUsable: () -> Boolean,
    awaitSystemReady: suspend (Long) -> Boolean,
): Boolean = isNeuralPackUsable() || awaitSystemReady(timeoutMs)
