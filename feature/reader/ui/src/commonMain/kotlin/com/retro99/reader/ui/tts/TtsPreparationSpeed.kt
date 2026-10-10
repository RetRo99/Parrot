package com.retro99.reader.ui.tts

/**
 * One sentence the preparation job really generated: how long synthesis and encoding took,
 * and how long the sentence was. Never the text, and never time spent waiting for a turn.
 */
data class TtsPreparationSample(
    val workMs: Long,
    val characters: Int,
    /** Length of the audio the sentence became, at normal speed; zero when not known. */
    val audioMs: Long = 0,
)

/**
 * Milliseconds of work per character of text, or null with fewer than [minSamples] usable
 * measurements.
 *
 * Per character rather than per sentence: synthesis time follows the length of the audio,
 * and sentences in prose run from one word to a paragraph, so a per-sentence average says
 * little about a chapter of long ones. One sentence far slower than the rest (the engine
 * loading, the phone busy) is counted as no more than three times the usual speed, so it
 * cannot throw the figure off.
 */
internal fun preparationMsPerCharacter(
    samples: List<TtsPreparationSample>,
    minSamples: Int = PREPARATION_MIN_SAMPLES,
): Double? {
    val usable = samples.filter { sample -> sample.workMs > 0 && sample.characters > 0 }
    if (usable.size < minSamples) return null
    val speeds = usable.map { sample -> sample.workMs.toDouble() / sample.characters }.sorted()
    val ceiling = speeds[speeds.size / 2] * OUTLIER_FACTOR
    val work = usable.sumOf { sample ->
        minOf(sample.workMs.toDouble(), ceiling * sample.characters)
    }
    return work / usable.sumOf { sample -> sample.characters }
}

/** What this device has measured, voice by voice. A small rolling window per voice. */
data class TtsPreparationSpeedRecord(
    val samplesByVoice: Map<String, List<TtsPreparationSample>> = emptyMap(),
) {
    fun with(voiceId: String?, sample: TtsPreparationSample): TtsPreparationSpeedRecord {
        val key = voiceId.orEmpty()
        val samples = (samplesByVoice[key].orEmpty() + sample).takeLast(PREPARATION_MAX_SAMPLES_PER_VOICE)
        return TtsPreparationSpeedRecord(samplesByVoice + (key to samples))
    }

    /** Null when this voice has not been measured enough; another voice's record never answers. */
    fun msPerCharacter(voiceId: String?): Double? =
        preparationMsPerCharacter(samplesByVoice[voiceId.orEmpty()].orEmpty())
}

/** One measurement per line: work, characters, audio length, then the voice id as it is. */
internal fun encodePreparationSpeedRecord(record: TtsPreparationSpeedRecord): String =
    record.samplesByVoice.entries.joinToString("") { (voiceId, samples) ->
        val voice = voiceId.replace('\n', ' ')
        samples.joinToString("") { sample ->
            "${sample.workMs} ${sample.characters} ${sample.audioMs} $voice\n"
        }
    }

/** Fail-closed line by line: anything that is not a measurement is skipped, never guessed at. */
internal fun decodePreparationSpeedRecord(text: String?): TtsPreparationSpeedRecord {
    var record = TtsPreparationSpeedRecord()
    text.orEmpty().split('\n').forEach { line ->
        val fields = line.split(' ', limit = FIELDS_PER_LINE)
        if (fields.size != FIELDS_PER_LINE) return@forEach
        val workMs = fields[0].toLongOrNull()?.takeIf { it > 0 } ?: return@forEach
        val characters = fields[1].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
        val audioMs = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@forEach
        record = record.with(fields[3], TtsPreparationSample(workMs, characters, audioMs))
    }
    return record
}

internal const val PREPARATION_MIN_SAMPLES = 3
internal const val PREPARATION_MAX_SAMPLES_PER_VOICE = 60
private const val OUTLIER_FACTOR = 3.0
private const val FIELDS_PER_LINE = 4
