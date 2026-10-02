package com.retro99.ttsbench

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

const val KIND_BENCH = "bench"
const val KIND_LISTENING = "listening"

/** One saved WAV plus the numbers that produced it. One line of `samples.csv`. */
data class SampleRow(
    val file: String,
    val model: String,
    val version: String,
    val threads: Int,
    val steps: Int,
    val passage: String,
    val passageText: String,
    val generationMs: Long,
    val audioMs: Long,
    val sampleRate: Int,
    val device: String,
    /** Player speed for stretched listening clips; 1.0 for everything else. */
    val speed: Float = 1f,
    val kind: String = KIND_BENCH,
    /** Only set for listening clips, which have no model/steps label of their own. */
    val label: String = "",
) {
    val ratio: Double get() = generationMs.toDouble() / audioMs.coerceAtLeast(1L)

    fun displayLabel(): String = label.ifEmpty {
        val name = model.replaceFirstChar { char -> char.uppercase() }
        if (steps > 0) "$name, $steps steps, $threads threads" else "$name, $threads threads"
    }
}

data class SampleRun(val dir: File, val mode: String, val rows: List<SampleRow>) {
    val stamp: String get() = dir.name
}

/** Run folders under `<external files>/samples/<stamp>/`, each with a `samples.csv` index. */
class SampleStore(context: Context) {

    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "samples")

    private val _version = MutableStateFlow(0)

    /** Bumps whenever a run is added to, appended to or deleted. */
    val version: StateFlow<Int> = _version

    fun newRun(stamp: String, mode: String): File {
        val dir = File(root, stamp)
        dir.mkdirs()
        File(dir, MODE_FILE).writeText(mode)
        _version.update { count -> count + 1 }
        return dir
    }

    fun append(runDir: File, row: SampleRow) {
        val index = File(runDir, INDEX_FILE)
        val isNew = !index.exists()
        if (isNew) index.appendText(HEADER + "\n")
        index.appendText(row.toCsv() + "\n")
        _version.update { count -> count + 1 }
    }

    fun listRuns(): List<SampleRun> = root.listFiles { file -> file.isDirectory }
        .orEmpty()
        .sortedByDescending { dir -> dir.name }
        .map { dir ->
            val mode = File(dir, MODE_FILE).takeIf { file -> file.exists() }?.readText()?.trim()
            SampleRun(dir, mode ?: "unknown", readRows(dir))
        }
        .filter { run -> run.rows.isNotEmpty() }

    fun delete(run: SampleRun) {
        run.dir.deleteRecursively()
        _version.update { count -> count + 1 }
    }

    private fun readRows(dir: File): List<SampleRow> {
        val index = File(dir, INDEX_FILE)
        if (!index.exists()) return emptyList()
        return index.readLines(Charsets.UTF_8)
            .drop(1)
            .filter { line -> line.isNotBlank() }
            .mapNotNull { line -> parseRow(splitCsv(line)) }
            .filter { row -> File(dir, row.file).exists() }
    }

    private fun parseRow(cells: List<String>): SampleRow? = runCatching {
        SampleRow(
            file = cells[COL_FILE],
            model = cells[COL_MODEL],
            version = cells[COL_VERSION],
            threads = cells[COL_THREADS].toInt(),
            steps = cells[COL_STEPS].toInt(),
            passage = cells[COL_PASSAGE],
            passageText = cells[COL_PASSAGE_TEXT],
            generationMs = cells[COL_GENERATION_MS].toLong(),
            audioMs = cells[COL_AUDIO_MS].toLong(),
            sampleRate = cells[COL_SAMPLE_RATE].toInt(),
            device = cells[COL_DEVICE],
            speed = cells[COL_SPEED].toFloat(),
            kind = cells[COL_KIND],
            label = cells[COL_LABEL],
        )
    }.getOrNull()

    private fun SampleRow.toCsv(): String = listOf(
        file,
        model,
        version,
        threads.toString(),
        steps.toString(),
        passage,
        passageText,
        generationMs.toString(),
        audioMs.toString(),
        "%.3f".format(Locale.US, ratio),
        sampleRate.toString(),
        device,
        speed.toString(),
        kind,
        label,
    ).joinToString(",") { cell -> quote(cell) }

    private fun quote(cell: String): String = "\"" + cell.replace("\"", "\"\"") + "\""

    private fun splitCsv(line: String): List<String> {
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quoted && char == '"' && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    cells += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        cells += current.toString()
        return cells
    }

    companion object {
        private const val INDEX_FILE = "samples.csv"
        private const val MODE_FILE = "run.txt"
        private const val HEADER = "file,model,model_version,threads,steps,passage,passage_text," +
            "generation_ms,audio_ms,r,sample_rate,device,speed,kind,label"
        private const val COL_FILE = 0
        private const val COL_MODEL = 1
        private const val COL_VERSION = 2
        private const val COL_THREADS = 3
        private const val COL_STEPS = 4
        private const val COL_PASSAGE = 5
        private const val COL_PASSAGE_TEXT = 6
        private const val COL_GENERATION_MS = 7
        private const val COL_AUDIO_MS = 8
        private const val COL_SAMPLE_RATE = 10
        private const val COL_DEVICE = 11
        private const val COL_SPEED = 12
        private const val COL_KIND = 13
        private const val COL_LABEL = 14

        /** Same `yyyyMMdd-HHmmss` stamp the result CSVs use. */
        fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        /** `<model>-t<threads>-s<steps>-<passage>.wav`; Kokoro uses steps 0. */
        fun fileName(model: String, threads: Int, steps: Int, passage: String): String =
            "$model-t$threads-s$steps-$passage.wav"
    }
}
