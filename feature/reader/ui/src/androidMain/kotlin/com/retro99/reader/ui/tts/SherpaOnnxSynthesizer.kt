package com.retro99.reader.ui.tts

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.retro99.analytics.api.Analytics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@Single
class SherpaOnnxSynthesizer(
    @Provided private val analytics: Analytics,
    private val modelManager: TtsModelManager,
) : TtsSynthesizer {

    @Volatile
    private var engine: OfflineTts? = null
    private val loadMutex = Mutex()
    private val generationMutex = Mutex()
    private val cancellationGeneration = AtomicInteger(0)

    override fun isReady(): Boolean = engine != null

    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            ensureLoaded() != null
        } ?: false
    }

    override fun availableVoices(): List<TtsVoice> {
        val isDownloaded = modelManager.isKokoroModelDownloaded()
        return KOKORO_VOICES.map { voice -> voice.copy(isDownloaded = isDownloaded) }
    }

    override fun defaultVoice(): TtsVoice = availableVoices().first()

    override suspend fun prepareVoice(
        voiceId: String?,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean = ensureLoaded(onProgress) != null

    override suspend fun deleteNeuralVoicePackage(
        voicePackage: NeuralVoicePackage,
    ): Boolean {
        if (voicePackage != NeuralVoicePackage.KOKORO) return false
        return loadMutex.withLock {
            stop()
            releaseEngine()
            modelManager.deleteKokoroModel()
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
        if (ensureLoaded() == null) {
            return TtsSynthesisResult(
                status = TtsSynthesisStatus.ERROR,
                error = "Kokoro model unavailable",
            )
        }

        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val speakerId = parseSpeakerId(voiceId)
            val speed = rate.coerceIn(MIN_SPEED, MAX_SPEED)
            val requestJob = currentCoroutineContext()[Job]
            val requestGeneration = cancellationGeneration.get()
            val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1L)

            Log.i(TAG, "Kokoro synthesize start: sid=$speakerId speed=$speed chars=${text.length}")
            val startedAt = System.currentTimeMillis()
            val audio = generationMutex.withLock {
                withContext(Dispatchers.IO) {
                    val activeEngine = engine
                        ?: throw CancellationException("Kokoro engine released")
                    activeEngine.generate(text, speakerId, speed)
                }
            }
            if (requestJob?.isActive == false) {
                throw CancellationException("Kokoro synthesis cancelled")
            }
            if (cancellationGeneration.get() != requestGeneration) {
                outputFile.delete()
                return TtsSynthesisResult(
                    status = TtsSynthesisStatus.CANCELLED,
                    error = "Kokoro synthesis stopped",
                )
            }
            if (SystemClock.elapsedRealtime() >= deadlineMs) {
                outputFile.delete()
                return TtsSynthesisResult(
                    status = TtsSynthesisStatus.TIMEOUT,
                    error = "Kokoro synthesis timed out",
                )
            }
            val saved = withContext(Dispatchers.IO) {
                audio.save(outputFile.absolutePath)
            }
            Log.i(
                TAG,
                "Kokoro synthesize done in ${System.currentTimeMillis() - startedAt}ms, " +
                        "samples=${audio.samples.size}, file=${outputFile.length()} bytes",
            )

            if (saved && outputFile.exists() && outputFile.length() > 0) {
                TtsSynthesisResult(TtsSynthesisStatus.SUCCESS, outputFile)
            } else {
                TtsSynthesisResult(
                    status = TtsSynthesisStatus.ERROR,
                    error = "Kokoro failed to save audio",
                )
            }
        } catch (error: CancellationException) {
            outputFile.delete()
            throw error
        } catch (error: Exception) {
            outputFile.delete()
            analytics.logException(error, "Kokoro synthesis failed")
            TtsSynthesisResult(status = TtsSynthesisStatus.ERROR, error = error.message)
        }
    }

    override fun stop() {
        cancellationGeneration.incrementAndGet()
    }

    override suspend fun release() {
        loadMutex.withLock {
            stop()
            releaseEngine()
        }
    }

    private suspend fun releaseEngine() {
        generationMutex.withLock {
            engine?.release()
            engine = null
        }
    }

    private suspend fun ensureLoaded(
        onProgress: ((TtsPreparationProgress) -> Unit)? = null,
    ): OfflineTts? {
        engine?.let { existing -> return existing }
        return loadMutex.withLock {
            engine?.let { existing -> return@withLock existing }

            val files = modelManager.ensureKokoroModel(onProgress) ?: return@withLock null
            onProgress?.invoke(TtsPreparationProgress.Finalizing)

            try {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = files.model.absolutePath,
                            voices = files.voices.absolutePath,
                            tokens = files.tokens.absolutePath,
                            dataDir = files.dataDir.absolutePath,
                        ),
                        numThreads = NUM_THREADS,
                        debug = false,
                    ),
                )
                val tts = withContext(Dispatchers.IO) { OfflineTts(config = config) }
                engine = tts
                Log.i(
                    TAG,
                    "Kokoro loaded: sampleRate=${tts.sampleRate()} " +
                            "speakers=${tts.numSpeakers()}",
                )
                tts
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                analytics.logException(error, "Failed to load Kokoro model")
                null
            }
        }
    }

    private fun parseSpeakerId(voiceId: String?): Int {
        val raw = voiceId?.substringAfter(':', "")
        return raw?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private companion object {
        const val NUM_THREADS = 2
        const val MIN_SPEED = TtsSpeechRate.MIN
        const val MAX_SPEED = TtsSpeechRate.MAX
        const val TAG = "SherpaOnnxTts"

        val KOKORO_VOICES: List<TtsVoice> = listOf(
            "af" to "Heart (US female)",
            "af_bella" to "Bella (US female)",
            "af_nicole" to "Nicole (US female)",
            "af_sarah" to "Sarah (US female)",
            "af_sky" to "Sky (US female)",
            "am_adam" to "Adam (US male)",
            "am_michael" to "Michael (US male)",
            "bf_emma" to "Emma (UK female)",
            "bf_isabella" to "Isabella (UK female)",
            "bm_george" to "George (UK male)",
            "bm_lewis" to "Lewis (UK male)",
        ).mapIndexed { index, (_, label) ->
            TtsVoice(
                id = "$KOKORO_VOICE_PREFIX$index",
                name = label,
                locale = "en",
                quality = 500,
                latency = 500,
                requiresNetwork = false,
                isNeural = true,
                downloadSizeBytes = KOKORO_DOWNLOAD_SIZE_BYTES,
                isDownloaded = false,
            )
        }
    }
}
