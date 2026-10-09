package com.retro99.reader.ui.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
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
class SupertonicOnnxSynthesizer(
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
        val isDownloaded = modelManager.isSupertonicModelDownloaded()
        val downloadSizeBytes = modelManager.supertonicDownloadSizeBytes()
        val updateAvailable = modelManager.isSupertonicUpdateAvailable()
        val updateSizeBytes = modelManager.supertonicUpdateSizeBytes()
        return SUPERTONIC_VOICES.map { voice ->
            voice.copy(
                isDownloaded = isDownloaded,
                downloadSizeBytes = downloadSizeBytes,
                updateAvailable = updateAvailable,
                updateSizeBytes = updateSizeBytes,
            )
        }
    }

    override fun defaultVoice(): TtsVoice = availableVoices().first()

    override suspend fun prepareVoice(
        voiceId: String?,
        updateToLatest: Boolean,
        onProgress: (TtsPreparationProgress) -> Unit,
    ): Boolean {
        if (updateToLatest) {
            val updated = modelManager.ensureSupertonicModel(onProgress, updateToLatest = true) != null
            if (!updated) return false
            loadMutex.withLock {
                stop()
                releaseEngine()
            }
        }
        return ensureLoaded(onProgress) != null
    }

    override fun activeModelVersion(voiceId: String?): String? =
        modelManager.activeSupertonicVersion()

    override suspend fun warmUp(voiceId: String?): Boolean = ensureLoadedIfDownloaded() != null

    override suspend fun deleteNeuralVoicePackage(
        voicePackage: NeuralVoicePackage,
    ): Boolean {
        if (voicePackage != NeuralVoicePackage.SUPERTONIC) return false
        return loadMutex.withLock {
            stop()
            releaseEngine()
            modelManager.deleteSupertonicModel()
        }
    }

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
        outputFile: File,
    ): TtsSynthesisResult {
        if (ensureLoaded() == null) {
            return TtsSynthesisResult(
                status = TtsSynthesisStatus.ERROR,
                error = "Supertonic model unavailable",
            )
        }

        return try {
            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            val speakerId = parseSpeakerId(voiceId)
            val speed = rate.coerceIn(MIN_SPEED, MAX_SPEED)
            val generationConfig = GenerationConfig(
                speed = speed,
                sid = speakerId,
                numSteps = GENERATION_STEPS,
                extra = mapOf("lang" to DEFAULT_LANGUAGE),
            )
            val requestJob = currentCoroutineContext()[Job]
            val requestGeneration = cancellationGeneration.get()

            Log.i(
                TAG,
                "Supertonic synthesize start: sid=$speakerId speed=$speed chars=${text.length}",
            )
            val startedAt = System.currentTimeMillis()
            val audio = generationMutex.withLock {
                withContext(Dispatchers.IO) {
                    val activeEngine = engine
                        ?: throw CancellationException("Supertonic engine released")
                    activeEngine.generateWithConfigAndCallback(
                        text,
                        generationConfig,
                        neuralGenerationCallback {
                            requestJob?.isActive == false ||
                                    cancellationGeneration.get() != requestGeneration
                        },
                    )
                }
            }
            if (requestJob?.isActive == false) {
                throw CancellationException("Supertonic synthesis cancelled")
            }
            if (cancellationGeneration.get() != requestGeneration) {
                outputFile.delete()
                return TtsSynthesisResult(
                    status = TtsSynthesisStatus.CANCELLED,
                    error = "Supertonic synthesis stopped",
                )
            }
            val saved = withContext(Dispatchers.IO) {
                audio.save(outputFile.absolutePath)
            }
            Log.i(
                TAG,
                "Supertonic synthesize done in ${System.currentTimeMillis() - startedAt}ms, " +
                        "samples=${audio.samples.size}, file=${outputFile.length()} bytes",
            )

            if (saved && outputFile.exists() && outputFile.length() > 0) {
                TtsSynthesisResult(TtsSynthesisStatus.SUCCESS, outputFile)
            } else {
                TtsSynthesisResult(
                    status = TtsSynthesisStatus.ERROR,
                    error = "Supertonic failed to save audio",
                )
            }
        } catch (error: CancellationException) {
            outputFile.delete()
            throw error
        } catch (error: Exception) {
            outputFile.delete()
            analytics.logException(error, "Supertonic synthesis failed")
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
        val files = modelManager.ensureSupertonicModel(onProgress) ?: return null
        return loadEngine(files, onProgress)
    }

    /** Load-only variant for speak-word warm-up; can never reach the model download path. */
    private suspend fun ensureLoadedIfDownloaded(): OfflineTts? {
        engine?.let { existing -> return existing }
        val files = modelManager.supertonicModelFilesIfPresent() ?: return null
        return loadEngine(files, onProgress = null)
    }

    private suspend fun loadEngine(
        files: SupertonicModelFiles,
        onProgress: ((TtsPreparationProgress) -> Unit)?,
    ): OfflineTts? {
        return loadMutex.withLock {
            engine?.let { existing -> return@withLock existing }
            onProgress?.invoke(TtsPreparationProgress.Finalizing)

            try {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        supertonic = OfflineTtsSupertonicModelConfig(
                            durationPredictor = files.durationPredictor.absolutePath,
                            textEncoder = files.textEncoder.absolutePath,
                            vectorEstimator = files.vectorEstimator.absolutePath,
                            vocoder = files.vocoder.absolutePath,
                            ttsJson = files.ttsJson.absolutePath,
                            unicodeIndexer = files.unicodeIndexer.absolutePath,
                            voiceStyle = files.voiceStyle.absolutePath,
                        ),
                        numThreads = NUM_THREADS,
                        debug = false,
                    ),
                )
                val tts = withContext(Dispatchers.IO) { OfflineTts(config = config) }
                engine = tts
                // Warm up the runtime so the first spoken sentence does not pay the
                // one-time graph and kernel initialization costs.
                withContext(Dispatchers.IO) {
                    runCatching {
                        tts.generateWithConfig(
                            WARMUP_TEXT,
                            GenerationConfig(
                                speed = 1f,
                                sid = 0,
                                numSteps = GENERATION_STEPS,
                                extra = mapOf("lang" to DEFAULT_LANGUAGE),
                            ),
                        )
                    }
                }
                Log.i(
                    TAG,
                    "Supertonic loaded: sampleRate=${tts.sampleRate()} " +
                            "speakers=${tts.numSpeakers()}",
                )
                tts
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                analytics.logException(error, "Failed to load Supertonic model")
                null
            }
        }
    }

    private fun parseSpeakerId(voiceId: String?): Int {
        val raw = voiceId?.substringAfter(':', "")
        return raw?.toIntOrNull()?.coerceIn(SUPERTONIC_VOICES.indices) ?: 0
    }

    private companion object {
        val NUM_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        const val WARMUP_TEXT = "a"
        const val GENERATION_STEPS = 8
        const val DEFAULT_LANGUAGE = "en"
        const val MIN_SPEED = TtsSpeechRate.MIN
        const val MAX_SPEED = TtsSpeechRate.MAX
        const val TAG = "SupertonicOnnxTts"
    }
}

internal val SUPERTONIC_VOICES: List<TtsVoice> = listOf(
    "F1 (female)",
    "F2 (female)",
    "F3 (female)",
    "F4 (female)",
    "F5 (female)",
    "M1 (male)",
    "M2 (male)",
    "M3 (male)",
    "M4 (male)",
    "M5 (male)",
).mapIndexed { index, label ->
    TtsVoice(
        id = "$SUPERTONIC_VOICE_PREFIX$index",
        name = label,
        locale = "en",
        quality = 600,
        latency = 500,
        requiresNetwork = false,
        isNeural = true,
        isDownloaded = false,
    )
}
