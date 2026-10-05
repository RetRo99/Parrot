package com.retro99.ttsbench

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device's system TTS engine: what a speaker tap pays when the user runs no neural voice.
 * [bindMs] is the engine bind time; synthesis goes through `synthesizeToFile` like the app,
 * at 1.0x rate and 1.0 pitch (the word feature's parameters).
 */
class SystemTtsEngine private constructor(
    private val engine: TextToSpeech,
    val bindMs: Long,
    val engineName: String,
    val voiceId: String,
    val voiceName: String,
) : AutoCloseable {

    private val utteranceCounter = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = complete(utteranceId, true)

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) = complete(utteranceId, false)

        override fun onError(utteranceId: String?, errorCode: Int) = complete(utteranceId, false)

        override fun onStop(utteranceId: String?, interrupted: Boolean) =
            complete(utteranceId, false)

        private fun complete(utteranceId: String?, success: Boolean) {
            val id = utteranceId ?: return
            pending.remove(id)?.complete(success)
        }
    }

    init {
        engine.setOnUtteranceProgressListener(listener)
    }

    /** Suspends until [onDone]; wall time around this call is the one-word latency. */
    suspend fun synthesizeToFile(text: String, outputFile: File): Boolean {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()
        val utteranceId = "word-${utteranceCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<Boolean>()
        pending[utteranceId] = deferred
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val result = engine.synthesizeToFile(text, params, outputFile, utteranceId)
        if (result == TextToSpeech.ERROR) {
            pending.remove(utteranceId)
            outputFile.delete()
            return false
        }
        val done = withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) { deferred.await() } ?: false
        if (!done) {
            pending.remove(utteranceId)
            outputFile.delete()
        }
        return done
    }

    override fun close() {
        engine.shutdown()
    }

    companion object {
        private const val SYNTHESIS_TIMEOUT_MS = 30_000L
        private const val INIT_TIMEOUT_MS = 30_000L

        /** Null when the engine fails to bind within [INIT_TIMEOUT_MS]. */
        suspend fun create(context: Context): SystemTtsEngine? {
            val startedAt = SystemClock.elapsedRealtime()
            val init = CompletableDeferred<Boolean>()
            val engine = TextToSpeech(context.applicationContext) { status ->
                init.complete(status == TextToSpeech.SUCCESS)
            }
            val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) { init.await() } ?: false
            if (!ready) {
                engine.shutdown()
                return null
            }
            val bindMs = SystemClock.elapsedRealtime() - startedAt
            val voice = pickVoice(engine)
            voice?.let { engine.voice = it }
            engine.setSpeechRate(1f)
            engine.setPitch(1f)
            return SystemTtsEngine(
                engine = engine,
                bindMs = bindMs,
                engineName = engine.defaultEngine.orEmpty(),
                voiceId = voice?.name.orEmpty(),
                voiceName = voice?.name ?: "default",
            )
        }

        /** The default voice when it is offline English, else the best offline English voice. */
        private fun pickVoice(engine: TextToSpeech): android.speech.tts.Voice? {
            val offline = engine.voices
                ?.filter { voice ->
                    !voice.isNetworkConnectionRequired && voice.locale?.language == "en"
                }
                .orEmpty()
            val default = engine.defaultVoice
            if (default != null && offline.any { voice -> voice.name == default.name }) {
                return default
            }
            return offline.sortedWith(
                compareByDescending<android.speech.tts.Voice> { voice -> voice.quality }
                    .thenBy { voice -> voice.latency }
                    .thenBy { voice -> voice.name },
            ).firstOrNull()
        }
    }
}
