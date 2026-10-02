package com.retro99.ttsbench

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val TAG = "TtsBench"

data class Passage(val name: String, val text: String)

val PASSAGES = listOf(
    Passage("short", "The old lighthouse keeper climbed the narrow stairs one last time."),
    Passage(
        "medium",
        "When the storm finally passed, the village woke to a silence so deep that even the " +
            "gulls seemed afraid to speak, and the sea lay flat and grey beyond the harbour wall.",
    ),
    Passage(
        "long",
        "She had carried the letter for eleven years, through two moves and one long winter, " +
            "never quite brave enough to open it, and now, standing at the edge of the field " +
            "where he had promised to wait, she finally broke the seal, unfolded the yellowed " +
            "paper, and began to read.",
    ),
)

data class Measurement(
    val model: String,
    val version: String,
    val threads: Int,
    val steps: Int,
    val passage: String,
    val chars: Int,
    val run: Int,
    val generationMs: Long,
    val audioMs: Long,
    val sampleRate: Int,
    val thermal: Int,
    val charging: Boolean,
    /** File name of the saved WAV; only run 1 is kept. */
    val sampleFile: String = "",
) {
    /** Seconds spent generating per second of audio (the design's r). */
    val ratio: Double get() = generationMs.toDouble() / audioMs.coerceAtLeast(1L)
}

data class Summary(
    val model: String,
    val threads: Int,
    val steps: Int,
    val passage: String,
    val medianGenerationMs: Long,
    val medianAudioMs: Long,
    val medianRatio: Double,
)

enum class BenchMode(val extra: String, val label: String) {
    FULL("full", "Supertonic matrix"),
    QUICK("quick", "Supertonic quick"),
    KOKORO("kokoro", "Kokoro baseline"),
    SAMPLES("samples", "Samples to hear"),
    ALL("all", "Everything"),
}

data class BenchUiState(
    val running: Boolean = false,
    val status: String = "Idle",
    val log: List<String> = emptyList(),
    val summaries: List<Summary> = emptyList(),
    val csvPath: String? = null,
)

/** Runs the Phase 0 measurements and publishes progress to the UI. */
class BenchController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val device = DeviceInfo.read()
    private val store = ModelStore(appContext, ::log)
    private var job: Job? = null

    private val _state = MutableStateFlow(BenchUiState())
    val state: StateFlow<BenchUiState> = _state

    val samples = SampleStore(appContext)
    val player = SamplePlayer()
    val lab = ListeningLab(store, samples, ::log)

    fun start(mode: BenchMode) {
        if (job?.isActive == true) return
        _state.value = BenchUiState(running = true, status = "Starting ${mode.label}")
        val stamp = SampleStore.stamp()
        job = scope.launch {
            val runDir = samples.newRun(stamp, mode.extra)
            val measurements = mutableListOf<Measurement>()
            val loads = mutableListOf<String>()
            try {
                if (mode != BenchMode.KOKORO) runSupertonic(mode, runDir, measurements, loads)
                if (mode == BenchMode.KOKORO || mode == BenchMode.ALL) {
                    runKokoro(mode, runDir, measurements, loads)
                }
                val path = writeCsv(stamp, measurements, loads)
                _state.update { current ->
                    current.copy(
                        running = false,
                        status = "Done: ${measurements.size} measurements",
                        summaries = summarize(measurements),
                        csvPath = path,
                    )
                }
                log("DONE csv=$path")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Bench failed", error)
                log("FAILED: ${error.message}")
                if (measurements.isNotEmpty()) {
                    log("PARTIAL csv=${writeCsv(stamp, measurements, loads)}")
                }
                _state.update { current -> current.copy(running = false, status = "Failed") }
            }
        }
    }

    fun stop() {
        job?.cancel()
        _state.update { current -> current.copy(running = false, status = "Stopped") }
    }

    private suspend fun runSupertonic(
        mode: BenchMode,
        runDir: File,
        measurements: MutableList<Measurement>,
        loads: MutableList<String>,
    ) {
        val cores = Runtime.getRuntime().availableProcessors()
        val quick = mode == BenchMode.QUICK
        // Threads change speed, not sound, so quick and samples runs use the default count only.
        val defaultThreads = mode == BenchMode.QUICK || mode == BenchMode.SAMPLES
        val allThreads = if (defaultThreads) listOf(cores.coerceIn(2, 4)) else listOf(2, 4, cores)
        val threadCounts = allThreads
            .filter { count -> count <= cores }
            .distinct()
        val stepCounts = if (quick) listOf(8) else listOf(8, 6, 4)
        val repeats = if (defaultThreads) 1 else REPEATS

        setStatus("Preparing Supertonic model")
        val (dir, version) = withContext(Dispatchers.IO) { store.ensure(ModelKind.SUPERTONIC.id) }
        for (threads in threadCounts) {
            setStatus("Loading Supertonic, $threads threads")
            val engine = withContext(Dispatchers.IO) {
                BenchEngine.loadSupertonic(dir, version, threads)
            }
            recordLoad(engine, loads)
            try {
                for (steps in stepCounts) {
                    measureConfig(engine, steps, repeats, mode, runDir, measurements)
                }
            } finally {
                engine.release()
            }
        }
    }

    private suspend fun runKokoro(
        mode: BenchMode,
        runDir: File,
        measurements: MutableList<Measurement>,
        loads: MutableList<String>,
    ) {
        setStatus("Preparing Kokoro model")
        val (dir, version) = withContext(Dispatchers.IO) { store.ensure(ModelKind.KOKORO.id) }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        setStatus("Loading Kokoro, $threads threads")
        val engine = withContext(Dispatchers.IO) { BenchEngine.loadKokoro(dir, version, threads) }
        recordLoad(engine, loads)
        try {
            val repeats = if (mode == BenchMode.SAMPLES) 1 else REPEATS
            measureConfig(engine, 0, repeats, mode, runDir, measurements)
        } finally {
            engine.release()
        }
    }

    private suspend fun measureConfig(
        engine: BenchEngine,
        steps: Int,
        repeats: Int,
        mode: BenchMode,
        runDir: File,
        into: MutableList<Measurement>,
    ) {
        for (passage in PASSAGES) {
            setStatus(
                "${engine.kind.id} t=${engine.threads} steps=$steps ${passage.name}",
            )
            if (mode != BenchMode.SAMPLES) {
                // One unrecorded pass so the recorded runs are warm.
                withContext(Dispatchers.Default) { engine.generate(passage.text, steps) }
            }
            for (run in 1..repeats) {
                val measurement = withContext(Dispatchers.Default) {
                    measureOnce(engine, steps, passage, run, runDir)
                }
                into += measurement
                log(
                    "%s t=%d steps=%d %s run=%d gen=%dms audio=%dms r=%.2f thermal=%d".format(
                        Locale.US,
                        engine.kind.id,
                        engine.threads,
                        steps,
                        passage.name,
                        run,
                        measurement.generationMs,
                        measurement.audioMs,
                        measurement.ratio,
                        measurement.thermal,
                    ),
                )
            }
        }
    }

    private fun measureOnce(
        engine: BenchEngine,
        steps: Int,
        passage: Passage,
        run: Int,
        runDir: File,
    ): Measurement {
        val startedAt = SystemClock.elapsedRealtime()
        val audio = engine.generate(passage.text, steps)
        val generationMs = SystemClock.elapsedRealtime() - startedAt
        val audioMs = audio.samples.size * MS_PER_SECOND / audio.sampleRate
        val thermal = DeviceState.thermalStatus(appContext)
        val charging = DeviceState.isCharging(appContext)
        // The clock is stopped, so writing the file never counts as generation time.
        val sampleFile = if (run == 1) {
            saveSample(runDir, engine, steps, passage, audio, generationMs, audioMs)
        } else {
            ""
        }
        return Measurement(
            model = engine.kind.id,
            version = engine.version,
            threads = engine.threads,
            steps = steps,
            passage = passage.name,
            chars = passage.text.length,
            run = run,
            generationMs = generationMs,
            audioMs = audioMs,
            sampleRate = audio.sampleRate,
            thermal = thermal,
            charging = charging,
            sampleFile = sampleFile,
        )
    }

    private fun saveSample(
        runDir: File,
        engine: BenchEngine,
        steps: Int,
        passage: Passage,
        audio: GeneratedAudio,
        generationMs: Long,
        audioMs: Long,
    ): String {
        val name = SampleStore.fileName(engine.kind.id, engine.threads, steps, passage.name)
        audio.save(File(runDir, name).absolutePath)
        samples.append(
            runDir,
            SampleRow(
                file = name,
                model = engine.kind.id,
                version = engine.version,
                threads = engine.threads,
                steps = steps,
                passage = passage.name,
                passageText = passage.text,
                generationMs = generationMs,
                audioMs = audioMs,
                sampleRate = audio.sampleRate,
                device = device.summary(),
            ),
        )
        return name
    }

    private fun recordLoad(engine: BenchEngine, loads: MutableList<String>) {
        loads += "${engine.kind.id},${engine.version},${engine.threads},${engine.sampleRate}," +
            "${engine.loadMs},${engine.warmupMs}"
        log(
            "LOAD ${engine.kind.id} threads=${engine.threads} rate=${engine.sampleRate} " +
                "load=${engine.loadMs}ms warmup=${engine.warmupMs}ms",
        )
    }

    private fun summarize(measurements: List<Measurement>): List<Summary> = measurements
        .groupBy { row -> listOf(row.model, row.threads, row.steps, row.passage) }
        .map { (_, rows) ->
            val first = rows.first()
            Summary(
                model = first.model,
                threads = first.threads,
                steps = first.steps,
                passage = first.passage,
                medianGenerationMs = median(rows.map { row -> row.generationMs.toDouble() })
                    .toLong(),
                medianAudioMs = median(rows.map { row -> row.audioMs.toDouble() }).toLong(),
                medianRatio = median(rows.map { row -> row.ratio }),
            )
        }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    private fun writeCsv(
        stamp: String,
        measurements: List<Measurement>,
        loads: List<String>,
    ): String {
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir

        val results = File(dir, "results-$stamp.csv")
        results.bufferedWriter().use { writer ->
            writer.appendLine("# ${device.summary()}")
            writer.appendLine(
                "model,version,threads,steps,passage,chars,run,generation_ms,audio_ms," +
                    "ratio,sample_rate,thermal,charging,sample_file",
            )
            for (row in measurements) {
                writer.appendLine(
                    "${row.model},${row.version},${row.threads},${row.steps},${row.passage}," +
                        "${row.chars},${row.run},${row.generationMs},${row.audioMs}," +
                        "${"%.3f".format(Locale.US, row.ratio)},${row.sampleRate}," +
                        "${row.thermal},${row.charging},${row.sampleFile}",
                )
            }
        }
        val loadsFile = File(dir, "loads-$stamp.csv")
        loadsFile.bufferedWriter().use { writer ->
            writer.appendLine("# ${device.summary()}")
            writer.appendLine("model,version,threads,sample_rate,load_ms,warmup_ms")
            loads.forEach { line -> writer.appendLine(line) }
        }
        return results.absolutePath
    }

    private fun setStatus(status: String) {
        _state.update { current -> current.copy(status = status) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        _state.update { current -> current.copy(log = (current.log + line).takeLast(MAX_LOG)) }
    }

    private companion object {
        const val REPEATS = 3
        const val MS_PER_SECOND = 1000L
        const val MAX_LOG = 200
    }
}
