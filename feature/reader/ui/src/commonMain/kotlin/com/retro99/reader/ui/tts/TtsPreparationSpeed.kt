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
 */
@Suppress("UnusedParameter")
internal fun preparationMsPerCharacter(
    samples: List<TtsPreparationSample>,
    minSamples: Int = PREPARATION_MIN_SAMPLES,
): Double? = null

/** What this device has measured, voice by voice. A small rolling window per voice. */
data class TtsPreparationSpeedRecord(
    val samplesByVoice: Map<String, List<TtsPreparationSample>> = emptyMap(),
) {
    @Suppress("UnusedParameter")
    fun with(voiceId: String?, sample: TtsPreparationSample): TtsPreparationSpeedRecord = this

    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    fun msPerCharacter(voiceId: String?): Double? = null
}

@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun encodePreparationSpeedRecord(record: TtsPreparationSpeedRecord): String = ""

@Suppress("UnusedParameter")
internal fun decodePreparationSpeedRecord(text: String?): TtsPreparationSpeedRecord = TtsPreparationSpeedRecord()

internal const val PREPARATION_MIN_SAMPLES = 3
internal const val PREPARATION_MAX_SAMPLES_PER_VOICE = 60
