package com.retro99.reader.ui.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.retro99.analytics.api.Analytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Single
class AndroidSystemTtsSynthesizer(
    @Provided private val context: Context,
    @Provided private val analytics: Analytics,
) : TtsSynthesizer {

    private var textToSpeech: TextToSpeech? = null
    private var initDeferred: CompletableDeferred<Boolean>? = null
    private var initialized = false
    private var released = false

    private val utteranceCounter = AtomicInteger(0)
    private val pendingResults =
        ConcurrentHashMap<String, CompletableDeferred<TtsSynthesisResult>>()
    private val pendingFiles = ConcurrentHashMap<String, File>()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            val deferred = pendingResults.remove(id) ?: return
            val file = pendingFiles.remove(id)
            if (file != null && file.exists() && file.length() > 0L) {
                deferred.complete(TtsSynthesisResult(TtsSynthesisStatus.SUCCESS, file))
            } else {
                file?.delete()
                deferred.complete(
                    TtsSynthesisResult(
                        status = TtsSynthesisStatus.ERROR,
                        error = "TTS produced an empty file",
                    ),
                )
            }
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            fail(utteranceId, "TTS synthesis error")
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            fail(utteranceId, "TTS synthesis error ($errorCode)")
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            val id = utteranceId ?: return
            val deferred = pendingResults.remove(id) ?: return
            pendingFiles.remove(id)?.delete()
            deferred.complete(
                TtsSynthesisResult(status = TtsSynthesisStatus.CANCELLED, error = "TTS stopped"),
            )
        }
    }

    override fun isReady(): Boolean = initialized && !released

    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (isReady()) return true
        return withContext(Dispatchers.Main.immediate) {
            if (isReady()) return@withContext true

            val deferred = initDeferred ?: CompletableDeferred<Boolean>().also { initDeferred = it }

            if (textToSpeech == null) {
                textToSpeech = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        initialized = true
                        textToSpeech?.setOnUtteranceProgressListener(progressListener)
                    }
                    deferred.complete(status == TextToSpeech.SUCCESS)
                }
            }

            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        }
    }

    override fun availableVoices(): List<TtsVoice> {
        val voices = textToSpeech?.voices ?: return emptyList()
        return voices
            .filter { voice -> !voice.isNetworkConnectionRequired }
            .map { voice ->
                val locale = voice.locale
                TtsVoice(
                    id = voice.name,
                    name = buildVoiceName(voice),
                    locale = locale?.toLanguageTag().orEmpty(),
                    quality = voice.quality,
                    latency = voice.latency,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }
            .sortedWith(
                compareByDescending<TtsVoice> { voice -> voice.isHighQuality }
                    .thenBy { voice -> voice.latency }
                    .thenBy { voice -> voice.name },
            )
    }

    override fun defaultVoice(): TtsVoice? {
        val engine = textToSpeech ?: return null
        val voice = resolveOfflineVoice(engine) ?: return null
        return TtsVoice(
            id = voice.name,
            name = buildVoiceName(voice),
            locale = voice.locale?.toLanguageTag().orEmpty(),
            quality = voice.quality,
            latency = voice.latency,
            requiresNetwork = voice.isNetworkConnectionRequired,
        )
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
        outputFile: File,
    ): TtsSynthesisResult {
        if (!awaitReady()) {
            return TtsSynthesisResult(
                status = TtsSynthesisStatus.ERROR,
                error = "TTS engine not available",
            )
        }

        return withContext(Dispatchers.Main.immediate) {
            val engine = textToSpeech ?: return@withContext TtsSynthesisResult(
                status = TtsSynthesisStatus.ERROR,
                error = "TTS engine not available",
            )

            applyVoice(engine, voiceId)
            engine.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
            engine.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH))

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val utteranceId = "tts-${utteranceCounter.incrementAndGet()}"
            val deferred = CompletableDeferred<TtsSynthesisResult>()
            pendingResults[utteranceId] = deferred
            pendingFiles[utteranceId] = outputFile

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }

            val result = engine.synthesizeToFile(text, params, outputFile, utteranceId)
            if (result == TextToSpeech.ERROR) {
                pendingResults.remove(utteranceId)
                pendingFiles.remove(utteranceId)
                outputFile.delete()
                return@withContext TtsSynthesisResult(
                    status = TtsSynthesisStatus.ERROR,
                    error = "synthesizeToFile rejected the request",
                )
            }

            try {
                withTimeoutOrNull(CALLBACK_TIMEOUT_MS) { deferred.await() } ?: run {
                    stop()
                    TtsSynthesisResult(
                        status = TtsSynthesisStatus.TIMEOUT,
                        error = "TTS synthesis timed out",
                    )
                }
            } catch (error: CancellationException) {
                stop()
                throw error
            }
        }
    }

    override fun stop() {
        textToSpeech?.stop()
        pendingFiles.values.forEach { file -> file.delete() }
        pendingResults.values.forEach { deferred ->
            deferred.complete(
                TtsSynthesisResult(status = TtsSynthesisStatus.CANCELLED, error = "TTS stopped"),
            )
        }
        pendingResults.clear()
        pendingFiles.clear()
    }

    override suspend fun release() {
        released = true
        initialized = false
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        initDeferred = null
    }

    private fun applyVoice(engine: TextToSpeech, voiceId: String?) {
        val selectedVoice = voiceId
            ?.takeUnless { id -> id.isBlank() }
            ?.let { id ->
                engine.voices?.firstOrNull { candidate ->
                    candidate.name == id && !candidate.isNetworkConnectionRequired
                }
            }
            ?: resolveOfflineVoice(engine)
        selectedVoice?.let { voice -> engine.voice = voice }
    }

    private fun resolveOfflineVoice(engine: TextToSpeech): android.speech.tts.Voice? {
        return engine.defaultVoice?.takeUnless { voice -> voice.isNetworkConnectionRequired }
            ?: engine.voices?.firstOrNull { voice -> !voice.isNetworkConnectionRequired }
    }

    private fun buildVoiceName(voice: android.speech.tts.Voice): String {
        val language = voice.locale?.displayLanguage ?: voice.locale?.toLanguageTag() ?: "Unknown"
        val region = voice.locale?.displayCountry.orEmpty()
        val label = if (region.isBlank()) language else "$language ($region)"
        val distinctLabel = voice.name.takeIf { name -> name.isNotBlank() }
            ?.let { name -> "$label · $name" }
            ?: label
        val qualityTag = when {
            voice.quality >= QUALITY_VERY_HIGH -> "Excellent"
            voice.quality >= QUALITY_HIGH -> "High"
            else -> null
        }
        return if (qualityTag == null) distinctLabel else "$distinctLabel - $qualityTag"
    }

    private fun fail(utteranceId: String?, message: String) {
        val id = utteranceId ?: return
        val deferred = pendingResults.remove(id) ?: return
        pendingFiles.remove(id)?.delete()
        deferred.complete(TtsSynthesisResult(status = TtsSynthesisStatus.ERROR, error = message))
    }

    private companion object {
        const val CALLBACK_TIMEOUT_MS = 30_000L
        const val MIN_RATE = TtsSpeechRate.MIN
        const val MAX_RATE = TtsSpeechRate.MAX
        const val MIN_PITCH = 0.25f
        const val MAX_PITCH = 4.0f
        const val QUALITY_VERY_HIGH = 500
        const val QUALITY_HIGH = 400
    }
}
