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
    WORD("word", "One-word latency"),
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
    val wordLab = WordLab(appContext, store, samples, ::log)

    fun start(mode: BenchMode) {
        if (job?.isActive == true) return
        _state.value = BenchUiState(running = true, status = "Starting ${mode.label}")
        val stamp = SampleStore.stamp()
        job = scope.launch {
            val runDir = samples.newRun(stamp, mode.extra)
            val measurements = mutableListOf<Measurement>()
            val wordMeasurements = mutableListOf<WordMeasurement>()
            val loads = mutableListOf<String>()
            try {
                when (mode) {
                    BenchMode.WORD -> runWordBench(runDir, wordMeasurements, loads)
                    else -> {
                        if (mode != BenchMode.KOKORO) runSupertonic(mode, runDir, measurements, loads)
                        if (mode == BenchMode.KOKORO || mode == BenchMode.ALL) {
                            runKokoro(mode, runDir, measurements, loads)
                        }
                        if (mode == BenchMode.ALL) runWordBench(runDir, wordMeasurements, loads)
                    }
                }
                val path = writeCsv(stamp, measurements, loads)
                val wordsPath = writeWordsCsv(stamp, wordMeasurements)
                _state.update { current ->
                    current.copy(
                        running = false,
                        status = "Done: ${measurements.size} measurements, " +
                            "${wordMeasurements.size} word rows",
                        summaries = summarize(measurements),
                        csvPath = path,
                    )
                }
                log("DONE csv=$path")
                wordsPath?.let { path -> log("DONE words csv=$path") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Bench failed", error)
                log("FAILED: ${error.message}")
                if (measurements.isNotEmpty()) {
                    log("PARTIAL csv=${writeCsv(stamp, measurements, loads)}")
                }
                if (wordMeasurements.isNotEmpty()) {
                    log("PARTIAL words csv=${writeWordsCsv(stamp, wordMeasurements)}")
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

    /**
     * One-word latency, cold and warm: what a speaker tap on the dictionary strip pays per
     * engine. Cold is the first word right after load (`BenchEngine.load` has already run the
     * app's warm-up pass, matching `ensureLoaded`); warm is [WORD_REPEATS] repeats.
     */
    private suspend fun runWordBench(
        runDir: File,
        words: MutableList<WordMeasurement>,
        loads: MutableList<String>,
    ) {
        setStatus("One-word: system TTS")
        measureSystemWord(runDir, words, loads)

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        setStatus("One-word: Supertonic")
        val (supertonicDir, supertonicVersion) =
            withContext(Dispatchers.IO) { store.ensure(ModelKind.SUPERTONIC.id) }
        val supertonic = withContext(Dispatchers.IO) {
            BenchEngine.loadSupertonic(supertonicDir, supertonicVersion, threads)
        }
        recordLoad(supertonic, loads)
        try {
            measureNeuralWord(supertonic, APP_GENERATION_STEPS, runDir, words)
        } finally {
            supertonic.release()
        }

        setStatus("One-word: Kokoro")
        val (kokoroDir, kokoroVersion) =
            withContext(Dispatchers.IO) { store.ensure(ModelKind.KOKORO.id) }
        val kokoro = withContext(Dispatchers.IO) {
            BenchEngine.loadKokoro(kokoroDir, kokoroVersion, threads)
        }
        recordLoad(kokoro, loads)
        try {
            measureNeuralWord(kokoro, 0, runDir, words)
        } finally {
            kokoro.release()
        }

        logWordSummary(words)
    }

    private suspend fun measureNeuralWord(
        engine: BenchEngine,
        steps: Int,
        runDir: File,
        into: MutableList<WordMeasurement>,
    ) {
        val coldStart = SystemClock.elapsedRealtime()
        val audio = withContext(Dispatchers.Default) { engine.generate(WORD_TEXT, steps) }
        val coldMs = SystemClock.elapsedRealtime() - coldStart
        val audioMs = audio.samples.size * MS_PER_SECOND / audio.sampleRate
        val sampleFile = SampleStore.fileName(engine.kind.id, engine.threads, steps, "word-cold")
        audio.save(File(runDir, sampleFile).absolutePath)
        into += wordMeasurement(
            engine = engine.kind.id,
            version = engine.version,
            threads = engine.threads,
            steps = steps,
            phase = PHASE_COLD,
            run = 1,
            generationMs = coldMs,
            audioMs = audioMs,
            sampleRate = audio.sampleRate,
            sampleFile = sampleFile,
        )
        log("WORD ${engine.kind.id} cold gen=${coldMs}ms audio=${audioMs}ms")
        for (run in 1..WORD_REPEATS) {
            val warmStart = SystemClock.elapsedRealtime()
            val warmAudio = withContext(Dispatchers.Default) { engine.generate(WORD_TEXT, steps) }
            val warmMs = SystemClock.elapsedRealtime() - warmStart
            val warmAudioMs = warmAudio.samples.size * MS_PER_SECOND / warmAudio.sampleRate
            into += wordMeasurement(
                engine = engine.kind.id,
                version = engine.version,
                threads = engine.threads,
                steps = steps,
                phase = PHASE_WARM,
                run = run,
                generationMs = warmMs,
                audioMs = warmAudioMs,
                sampleRate = warmAudio.sampleRate,
                sampleFile = "",
            )
            log("WORD ${engine.kind.id} warm run=$run gen=${warmMs}ms audio=${warmAudioMs}ms")
        }
    }

    private suspend fun measureSystemWord(
        runDir: File,
        into: MutableList<WordMeasurement>,
        loads: MutableList<String>,
    ) {
        val engine = SystemTtsEngine.create(appContext)
        if (engine == null) {
            log("WORD system: TTS engine failed to bind")
            return
        }
        loads += "system,${engine.engineName},0,0,${engine.bindMs},0"
        log(
            "LOAD system engine=${engine.engineName} voice=${engine.voiceId} " +
                "bind=${engine.bindMs}ms",
        )
        try {
            val coldFile = File(runDir, SampleStore.fileName(SYSTEM_MODEL_ID, 0, 0, "word-cold"))
            val coldStart = SystemClock.elapsedRealtime()
            val coldOk = engine.synthesizeToFile(WORD_TEXT, coldFile)
            val coldMs = SystemClock.elapsedRealtime() - coldStart
            val coldInfo = if (coldOk) readWavInfo(coldFile) else null
            if (coldInfo == null) {
                coldFile.delete()
                log("WORD system cold FAILED after ${coldMs}ms")
                return
            }
            into += wordMeasurement(
                engine = SYSTEM_MODEL_ID,
                version = engine.engineName,
                threads = 0,
                steps = 0,
                phase = PHASE_COLD,
                run = 1,
                generationMs = coldMs,
                audioMs = coldInfo.durationMs,
                sampleRate = coldInfo.sampleRate,
                sampleFile = coldFile.name,
            )
            log("WORD system cold gen=${coldMs}ms audio=${coldInfo.durationMs}ms")

            val warmFile = File(appContext.cacheDir, "word-warm.wav")
            for (run in 1..WORD_REPEATS) {
                val warmStart = SystemClock.elapsedRealtime()
                val warmOk = engine.synthesizeToFile(WORD_TEXT, warmFile)
                val warmMs = SystemClock.elapsedRealtime() - warmStart
                val warmInfo = if (warmOk) readWavInfo(warmFile) else null
                if (warmInfo == null) {
                    log("WORD system warm run=$run FAILED after ${warmMs}ms")
                    continue
                }
                into += wordMeasurement(
                    engine = SYSTEM_MODEL_ID,
                    version = engine.engineName,
                    threads = 0,
                    steps = 0,
                    phase = PHASE_WARM,
                    run = run,
                    generationMs = warmMs,
                    audioMs = warmInfo.durationMs,
                    sampleRate = warmInfo.sampleRate,
                    sampleFile = "",
                )
                log("WORD system warm run=$run gen=${warmMs}ms audio=${warmInfo.durationMs}ms")
            }
            warmFile.delete()
        } finally {
            engine.close()
        }
    }

    private fun wordMeasurement(
        engine: String,
        version: String,
        threads: Int,
        steps: Int,
        phase: String,
        run: Int,
        generationMs: Long,
        audioMs: Long,
        sampleRate: Int,
        sampleFile: String,
    ): WordMeasurement = WordMeasurement(
        engine = engine,
        version = version,
        threads = threads,
        steps = steps,
        phase = phase,
        run = run,
        generationMs = generationMs,
        audioMs = audioMs,
        sampleRate = sampleRate,
        thermal = DeviceState.thermalStatus(appContext),
        charging = DeviceState.isCharging(appContext),
        sampleFile = sampleFile,
    )

    private fun logWordSummary(words: List<WordMeasurement>) {
        words.groupBy { row -> row.engine }.forEach { (engine, rows) ->
            val cold = rows.firstOrNull { row -> row.phase == PHASE_COLD }?.generationMs
            val warm = rows.filter { row -> row.phase == PHASE_WARM }
            val warmMedian = warm
                .takeIf { rows -> rows.isNotEmpty() }
                ?.let { rows -> median(rows.map { row -> row.generationMs.toDouble() }).toLong() }
            val audioMs = rows.firstOrNull()?.audioMs
            log(
                "WORDS %s cold=%s warm-median=%s audio=%s".format(
                    Locale.US,
                    engine,
                    cold?.let { ms -> "${ms}ms" } ?: "-",
                    warmMedian?.let { ms -> "${ms}ms" } ?: "-",
                    audioMs?.let { ms -> "${ms}ms" } ?: "-",
                ),
            )
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

    /** `words-<stamp>.csv`; null when the run produced no word rows. */
    private fun writeWordsCsv(stamp: String, words: List<WordMeasurement>): String? {
        if (words.isEmpty()) return null
        val dir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val results = File(dir, "words-$stamp.csv")
        results.bufferedWriter().use { writer ->
            writer.appendLine("# ${device.summary()}")
            writer.appendLine(
                "engine,version,threads,steps,phase,run,generation_ms,audio_ms,sample_rate," +
                    "thermal,charging,sample_file",
            )
            for (row in words) {
                writer.appendLine(
                    "${row.engine},${row.version},${row.threads},${row.steps},${row.phase}," +
                        "${row.run},${row.generationMs},${row.audioMs},${row.sampleRate}," +
                        "${row.thermal},${row.charging},${row.sampleFile}",
                )
            }
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
        const val WORD_REPEATS = 5
        const val SYSTEM_MODEL_ID = "system"
        const val MS_PER_SECOND = 1000L
        const val MAX_LOG = 200
    }
}
