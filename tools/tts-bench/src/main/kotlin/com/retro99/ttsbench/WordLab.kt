package com.retro99.ttsbench

import android.content.Context
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Isolated words for the by-ear single-word check. The heteronyms come first: one spelling,
 * two senses, and no engine can pick the sense from a bare word — expect the default one.
 */
val LISTEN_WORDS = listOf(
    // Heteronyms.
    "read", "lead", "wind", "bass", "tear", "live", "close", "wound",
    "bear", "bow", "record", "present",
    // Internal marks.
    "don't", "o'clock", "well-known",
    // All-caps acronyms.
    "NATO", "HTML",
    // Irregular and inflected surface forms.
    "mice", "went", "children", "ran", "running", "cats",
    // Shape variety.
    "echo", "queue", "subtle", "island", "photography", "schedule", "garage",
    "coffee", "water", "comfortable", "refrigerator", "algorithm",
    // Digits.
    "1984",
)

enum class WordEngine(val id: String) {
    SYSTEM("system"),
    KOKORO("kokoro"),
    SUPERTONIC("supertonic"),
}

/** One generated word clip. [withPeriod] separates the "word" and "word." variants. */
data class WordClip(
    val word: String,
    val withPeriod: Boolean,
    val text: String,
    val file: File,
    val generationMs: Long,
    val audioMs: Long,
)

/**
 * Generates every word plain and with a trailing full stop (the clipped-tail question) on the
 * chosen engine, keeps the WAVs as a sample run, and records per-word generation time.
 */
class WordLab(
    private val context: Context,
    private val store: ModelStore,
    private val samples: SampleStore,
    private val log: (String) -> Unit,
) {

    private val device = DeviceInfo.read().summary()

    suspend fun prepareWordClips(engine: WordEngine): List<WordClip> {
        val runDir = samples.newRun(SampleStore.stamp(), "word-clips-${engine.id}")
        return when (engine) {
            WordEngine.SYSTEM -> prepareSystem(runDir)
            WordEngine.KOKORO -> prepareNeural(runDir, ModelKind.KOKORO)
            WordEngine.SUPERTONIC -> prepareNeural(runDir, ModelKind.SUPERTONIC)
        }
    }

    private suspend fun prepareNeural(runDir: File, kind: ModelKind): List<WordClip> {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val (dir, version) = withContext(Dispatchers.IO) { store.ensure(kind.id) }
        val engine = withContext(Dispatchers.IO) {
            when (kind) {
                ModelKind.KOKORO -> BenchEngine.loadKokoro(dir, version, threads)
                ModelKind.SUPERTONIC -> BenchEngine.loadSupertonic(dir, version, threads)
            }
        }
        log(
            "LOAD ${kind.id} for word clips: load=${engine.loadMs}ms warmup=${engine.warmupMs}ms",
        )
        val steps = if (kind == ModelKind.SUPERTONIC) APP_GENERATION_STEPS else 0
        return engine.use { bench ->
            withContext(Dispatchers.Default) {
                allTexts().map { (word, text, withPeriod) ->
                    val startedAt = SystemClock.elapsedRealtime()
                    val audio = bench.generate(text, steps)
                    val generationMs = SystemClock.elapsedRealtime() - startedAt
                    val audioMs = audio.samples.size * MS_PER_SECOND / audio.sampleRate
                    val name = clipFileName(kind.id, word, withPeriod)
                    audio.save(File(runDir, name).absolutePath)
                    samples.append(
                        runDir,
                        SampleRow(
                            file = name,
                            model = kind.id,
                            version = bench.version,
                            threads = bench.threads,
                            steps = steps,
                            passage = KIND_WORD,
                            passageText = text,
                            generationMs = generationMs,
                            audioMs = audioMs,
                            sampleRate = audio.sampleRate,
                            device = device,
                            kind = KIND_LISTENING,
                            label = "$text · ${kind.id}",
                        ),
                    )
                    log("WORDCLIP ${kind.id} \"$text\" gen=${generationMs}ms audio=${audioMs}ms")
                    WordClip(word, withPeriod, text, File(runDir, name), generationMs, audioMs)
                }
            }
        }
    }

    private suspend fun prepareSystem(runDir: File): List<WordClip> {
        val engine = SystemTtsEngine.create(context)
        if (engine == null) {
            log("WORDCLIP system: TTS engine failed to bind")
            return emptyList()
        }
        log(
            "LOAD system for word clips: bind=${engine.bindMs}ms " +
                "engine=${engine.engineName} voice=${engine.voiceId}",
        )
        return engine.use { tts ->
            // One unrecorded word so the recorded ones measure the steady state.
            tts.synthesizeToFile(WORD_TEXT, File(context.cacheDir, "word-warmup.wav"))
            allTexts().map { (word, text, withPeriod) ->
                val name = clipFileName(WordEngine.SYSTEM.id, word, withPeriod)
                val target = File(runDir, name)
                val startedAt = SystemClock.elapsedRealtime()
                val success = tts.synthesizeToFile(text, target)
                val generationMs = SystemClock.elapsedRealtime() - startedAt
                val info = if (success) readWavInfo(target) else null
                if (info == null) {
                    target.delete()
                    log("WORDCLIP system \"$text\" FAILED after ${generationMs}ms")
                    return@map null
                }
                samples.append(
                    runDir,
                    SampleRow(
                        file = name,
                        model = WordEngine.SYSTEM.id,
                        version = engine.engineName,
                        threads = 0,
                        steps = 0,
                        passage = KIND_WORD,
                        passageText = text,
                        generationMs = generationMs,
                        audioMs = info.durationMs,
                        sampleRate = info.sampleRate,
                        device = device,
                        kind = KIND_LISTENING,
                        label = "$text · system",
                    ),
                )
                log(
                    "WORDCLIP system \"$text\" gen=${generationMs}ms audio=${info.durationMs}ms",
                )
                WordClip(word, withPeriod, text, target, generationMs, info.durationMs)
            }.filterNotNull()
        }
    }

    private fun allTexts(): List<WordText> = LISTEN_WORDS.flatMap { word ->
        listOf(WordText(word, word, false), WordText(word, "$word.", true))
    }

    private data class WordText(val word: String, val text: String, val withPeriod: Boolean)

    private fun clipFileName(engine: String, word: String, withPeriod: Boolean): String {
        val slug = word.map { char -> if (char.isLetterOrDigit()) char else '_' }
            .joinToString("")
            .lowercase()
        return "word-$engine-$slug${if (withPeriod) "-dot" else ""}.wav"
    }

    private inline fun <T> BenchEngine.use(block: (BenchEngine) -> T): T = try {
        block(this)
    } finally {
        release()
    }

    private companion object {
        const val MS_PER_SECOND = 1000L
    }
}
