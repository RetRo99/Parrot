package com.retro99.ttsbench

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig

enum class ModelKind(val id: String) {
    SUPERTONIC("supertonic"),
    KOKORO("kokoro"),
}

/** A loaded model plus what loading it cost. */
class BenchEngine private constructor(
    val kind: ModelKind,
    val version: String,
    val threads: Int,
    private val tts: OfflineTts,
    val loadMs: Long,
    val warmupMs: Long,
) {
    val sampleRate: Int = tts.sampleRate()

    /** [steps] only applies to Supertonic; Kokoro ignores it. */
    fun generate(text: String, steps: Int, speed: Float = 1f): GeneratedAudio = when (kind) {
        ModelKind.SUPERTONIC -> tts.generateWithConfig(
            text,
            GenerationConfig(
                speed = speed,
                sid = 0,
                numSteps = steps,
                extra = mapOf("lang" to "en"),
            ),
        )
        ModelKind.KOKORO -> tts.generate(text, 0, speed)
    }

    fun release() {
        tts.release()
    }

    companion object {
        private const val WARMUP_TEXT = "a"
        private const val WARMUP_STEPS = 8

        fun loadSupertonic(dir: java.io.File, version: String, threads: Int): BenchEngine {
            val files = SupertonicFiles(dir)
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
                    numThreads = threads,
                    debug = false,
                ),
            )
            return load(ModelKind.SUPERTONIC, version, threads, config)
        }

        fun loadKokoro(dir: java.io.File, version: String, threads: Int): BenchEngine {
            val files = KokoroFiles(dir)
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = files.model.absolutePath,
                        voices = files.voices.absolutePath,
                        tokens = files.tokens.absolutePath,
                        dataDir = files.dataDir.absolutePath,
                    ),
                    numThreads = threads,
                    debug = false,
                ),
            )
            return load(ModelKind.KOKORO, version, threads, config)
        }

        private fun load(
            kind: ModelKind,
            version: String,
            threads: Int,
            config: OfflineTtsConfig,
        ): BenchEngine {
            val loadStart = SystemClock.elapsedRealtime()
            val tts = OfflineTts(config = config)
            val loadMs = SystemClock.elapsedRealtime() - loadStart

            val warmupStart = SystemClock.elapsedRealtime()
            when (kind) {
                ModelKind.SUPERTONIC -> tts.generateWithConfig(
                    WARMUP_TEXT,
                    GenerationConfig(
                        speed = 1f,
                        sid = 0,
                        numSteps = WARMUP_STEPS,
                        extra = mapOf("lang" to "en"),
                    ),
                )
                ModelKind.KOKORO -> tts.generate(WARMUP_TEXT, 0, 1f)
            }
            val warmupMs = SystemClock.elapsedRealtime() - warmupStart
            return BenchEngine(kind, version, threads, tts, loadMs, warmupMs)
        }
    }
}
