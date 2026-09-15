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

    override suspend fun awaitReady(timeoutMs: Long): Boolean =
        systemSynthesizer.awaitReady(timeoutMs)

    override fun availableVoices(): List<TtsVoice> =
        systemSynthesizer.availableVoices() +
                kokoroSynthesizer.availableVoices() +
                supertonicSynthesizer.availableVoices()

    override fun defaultVoice(): TtsVoice =
        systemSynthesizer.defaultVoice() ?: kokoroSynthesizer.defaultVoice()

    override suspend fun prepareVoice(
        voiceId: String?,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean = when (voiceId.neuralVoicePackage()) {
        NeuralVoicePackage.KOKORO -> kokoroSynthesizer.prepareVoice(voiceId, onProgress)
        NeuralVoicePackage.SUPERTONIC -> {
            supertonicSynthesizer.prepareVoice(voiceId, onProgress)
        }

        null -> systemSynthesizer.awaitReady()
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
        timeoutMs: Long,
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
        return synthesizer.synthesize(text, voiceId, rate, pitch, outputFile, timeoutMs)
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
