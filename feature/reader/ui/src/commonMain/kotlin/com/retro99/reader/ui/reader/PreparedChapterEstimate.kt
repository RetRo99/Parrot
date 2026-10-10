package com.retro99.reader.ui.reader

import com.retro99.reader.ui.tts.NeuralVoicePackage
import com.retro99.reader.ui.tts.TtsVoice

/**
 * What "Prepare this chapter" is about to cost, so the user decides before it starts
 * rather than after. Pure, so every figure and every rounding boundary is a test.
 */
internal data class PreparedChapterEstimate(
    /** Rounded whole minutes. Zero means "under a minute". */
    val minutes: Int,
    val bytes: Long,
)

/** The three kinds of voice that prepare at visibly different speeds and sizes. */
internal enum class PreparedVoiceKind { SYSTEM, KOKORO, SUPERTONIC }

internal fun preparedVoiceKind(voice: TtsVoice?): PreparedVoiceKind = when {
    voice == null || !voice.isNeural -> PreparedVoiceKind.SYSTEM
    voice.neuralVoicePackage == NeuralVoicePackage.SUPERTONIC -> PreparedVoiceKind.SUPERTONIC
    else -> PreparedVoiceKind.KOKORO
}

/**
 * What this device has actually seen, when it has prepared anything with this voice before.
 * Either figure may be absent, and then the fixed one for the voice kind is used.
 */
data class PreparedChapterMeasured(
    val msPerSentence: Long? = null,
    val bytesPerSentence: Long? = null,
    /** Synthesis and encoding time per character of text, from this device's own record. */
    val msPerCharacter: Double? = null,
)

/**
 * The starting figures, from the six-sentence measurements on the Samsung in
 * `docs/tts-prepared-chapters.md` ("Step 1: Samsung measurements and format gate").
 * Bytes are the mean of that table's M4A column, which is the format the store keeps
 * (`PREPARED_AUDIO_EXTENSION`). Milliseconds are the mean of its M4A duration column:
 * the document holds no measurement of synthesis *speed*, so spoken length stands in for
 * preparation time until a device run replaces it with [PreparedChapterMeasured].
 * Supertonic was never in that table; it takes Kokoro's figures, being the other neural
 * engine.
 */
private val FIXED_MS_PER_SENTENCE = mapOf(
    PreparedVoiceKind.SYSTEM to 2_500L,
    PreparedVoiceKind.KOKORO to 2_200L,
    PreparedVoiceKind.SUPERTONIC to 2_200L,
)

private val FIXED_BYTES_PER_SENTENCE = mapOf(
    PreparedVoiceKind.SYSTEM to 16_000L,
    PreparedVoiceKind.KOKORO to 14_000L,
    PreparedVoiceKind.SUPERTONIC to 14_000L,
)

/**
 * Null when the sentence count is not known yet, which is the usual state before read-aloud
 * has loaded the chapter: an estimate from a guessed count would be worse than none.
 */
internal fun preparedChapterEstimate(
    sentenceCount: Int?,
    voiceKind: PreparedVoiceKind,
    measured: PreparedChapterMeasured? = null,
    characterCount: Int? = null,
): PreparedChapterEstimate? {
    if (sentenceCount == null || sentenceCount <= 0) return null
    val msPerSentence = measured?.msPerSentence?.takeIf { it > 0 }
        ?: FIXED_MS_PER_SENTENCE.getValue(voiceKind)
    // The device's own speed for this voice is per character, so it needs the chapter's
    // characters; without both, the fixed figure per sentence stands.
    val measuredMs = measured?.msPerCharacter?.takeIf { it > 0 }?.let { msPerCharacter ->
        characterCount?.takeIf { it > 0 }?.let { characters -> (characters * msPerCharacter).toLong() }
    }
    val bytesPerSentence = measured?.bytesPerSentence?.takeIf { it > 0 }
        ?: FIXED_BYTES_PER_SENTENCE.getValue(voiceKind)
    return PreparedChapterEstimate(
        minutes = roundedMinutes(measuredMs ?: (sentenceCount * msPerSentence)),
        bytes = sentenceCount * bytesPerSentence,
    )
}

/**
 * Rounded the way a person says a duration: under three quarters of a minute is "under a
 * minute", then whole minutes while they are few enough to matter, then fives, then tens.
 */
private fun roundedMinutes(totalMs: Long): Int {
    val seconds = totalMs / 1_000
    if (seconds < 45) return 0
    val minutes = ((seconds + 30) / 60).toInt().coerceAtLeast(1)
    return when {
        minutes < 10 -> minutes
        minutes < 60 -> (minutes + 2) / 5 * 5
        else -> (minutes + 5) / 10 * 10
    }
}

/**
 * Coarser than [preparedChapterSizeLabel]: an estimate that says "9.1 MB" claims a
 * precision it does not have, so whole megabytes above a megabyte and tens of kilobytes
 * below it.
 */
internal fun preparedChapterEstimateSizeLabel(bytes: Long): String = if (bytes >= 1_000_000) {
    "${(bytes + 500_000) / 1_000_000} MB"
} else {
    "${((bytes + 5_000) / 10_000 * 10).coerceAtLeast(10L)} kB"
}
