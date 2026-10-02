package com.retro99.ttsbench

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved clip that can be played, optionally time-stretched by the player. */
class Clip(
    val label: String,
    val file: File,
    val playbackSpeed: Float = 1f,
)

/** Produces clips for the two listening questions and saves them as sample runs. */
class ListeningLab(
    private val store: ModelStore,
    private val samples: SampleStore,
    private val log: (String) -> Unit,
) {

    private val device = DeviceInfo.read().summary()

    /** Same sentence at 8, 6 and 4 steps. */
    suspend fun prepareStepClips(): List<Clip> = withContext(Dispatchers.Default) {
        val runDir = samples.newRun(SampleStore.stamp(), MODE_STEPS)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        loadEngine(threads).use { engine ->
            listOf(8, 6, 4).map { steps ->
                val timed = generate(engine, steps, 1f)
                val name = "listening-s$steps.wav"
                val row = save(runDir, name, engine, timed, steps, 1f, "")
                log("Prepared $steps-step clip (${row.audioMs} ms)")
                Clip("$steps steps", File(runDir, name))
            }
        }
    }

    /** For each speed: model-native audio, then 1.0x audio stretched by the player. */
    suspend fun prepareSpeedClips(): List<Pair<Clip, Clip>> = withContext(Dispatchers.Default) {
        val runDir = samples.newRun(SampleStore.stamp(), MODE_SPEED)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        loadEngine(threads).use { engine ->
            val baseName = "listening-1.0x.wav"
            val base = generate(engine, LISTEN_STEPS, 1f)
            save(runDir, baseName, engine, base, LISTEN_STEPS, 1f, "1.0x")
            listOf(1.25f, 1.5f, 2.0f).map { speed ->
                val native = generate(engine, LISTEN_STEPS, speed)
                val nativeName = "listening-native-${speed}x.wav"
                val nativeLabel = "native ${speed}x"
                save(runDir, nativeName, engine, native, LISTEN_STEPS, 1f, nativeLabel)
                // The stretched clip is the 1.0x file played at [speed], so it has no file.
                val stretchedLabel = "stretched ${speed}x"
                save(runDir, baseName, engine, base, LISTEN_STEPS, speed, stretchedLabel)
                log("Prepared ${speed}x clips")
                Clip(nativeLabel, File(runDir, nativeName)) to
                    Clip(stretchedLabel, File(runDir, baseName), speed)
            }
        }
    }

    private suspend fun loadEngine(threads: Int): BenchEngine {
        val (dir, version) = withContext(Dispatchers.IO) { store.ensure(ModelKind.SUPERTONIC.id) }
        return BenchEngine.loadSupertonic(dir, version, threads)
    }

    private class Timed(val audio: GeneratedAudio, val generationMs: Long)

    private fun generate(engine: BenchEngine, steps: Int, speed: Float): Timed {
        val startedAt = SystemClock.elapsedRealtime()
        val audio = engine.generate(LISTEN_TEXT, steps, speed)
        return Timed(audio, SystemClock.elapsedRealtime() - startedAt)
    }

    /** Writes the WAV unless the file exists (the stretched row reuses the 1.0x file). */
    private fun save(
        runDir: File,
        name: String,
        engine: BenchEngine,
        timed: Timed,
        steps: Int,
        playbackSpeed: Float,
        label: String,
    ): SampleRow {
        val file = File(runDir, name)
        if (!file.exists()) timed.audio.save(file.absolutePath)
        val row = SampleRow(
            file = name,
            model = engine.kind.id,
            version = engine.version,
            threads = engine.threads,
            steps = steps,
            passage = KIND_LISTENING,
            passageText = LISTEN_TEXT,
            generationMs = timed.generationMs,
            audioMs = timed.audio.samples.size * MS_PER_SECOND / timed.audio.sampleRate,
            sampleRate = timed.audio.sampleRate,
            device = device,
            speed = playbackSpeed,
            kind = KIND_LISTENING,
            label = label,
        )
        samples.append(runDir, row)
        log("Saved %s (%s)".format(Locale.US, name, label.ifEmpty { "$steps steps" }))
        return row
    }

    private inline fun <T> BenchEngine.use(block: (BenchEngine) -> T): T = try {
        block(this)
    } finally {
        release()
    }

    private companion object {
        const val MODE_STEPS = "listening-steps"
        const val MODE_SPEED = "listening-speed"
        const val LISTEN_STEPS = 8
        const val MS_PER_SECOND = 1000L
        const val LISTEN_TEXT =
            "When the storm finally passed, the village woke to a silence so deep that even " +
                "the gulls seemed afraid to speak."
    }
}
