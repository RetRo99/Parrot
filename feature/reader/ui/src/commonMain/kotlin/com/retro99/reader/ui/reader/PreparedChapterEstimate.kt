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
    /** Rounded length of the audio the chapter becomes; null when the row does not say it. */
    val audioMinutes: Int? = null,
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
    /** Length of the audio per character of text at normal speed, from the same record. */
    val audioMsPerCharacter: Double? = null,
)

/**
 * Roughly how long the chapter will be to listen to at [rate], rounded as the preparation
 * time is. Null when the sentence count is not known.
 */
internal fun preparedChapterAudioMinutes(
    sentenceCount: Int?,
    characterCount: Int?,
    voiceKind: PreparedVoiceKind,
    measured: PreparedChapterMeasured? = null,
    rate: Float = 1f,
): Int? = preparedChapterAudioMs(sentenceCount, characterCount, voiceKind, measured, rate)
    ?.let { audioMs -> roundedMinutes(audioMs) }

/**
 * How long the prepared audio will be, unrounded. The rate is applied at synthesis, so this
 * is both how long the chapter is to listen to and how long the files really are, which is
 * what their size follows from.
 */
private fun preparedChapterAudioMs(
    sentenceCount: Int?,
    characterCount: Int?,
    voiceKind: PreparedVoiceKind,
    measured: PreparedChapterMeasured?,
    rate: Float,
): Long? {
    if (sentenceCount == null || sentenceCount <= 0) return null
    // The fixed per-sentence figures are spoken lengths at normal speed, as the record is.
    val atNormalSpeed = measuredAudioMs(characterCount, measured)
        ?: (sentenceCount * FIXED_AUDIO_MS_PER_SENTENCE.getValue(voiceKind)).toDouble()
    return (atNormalSpeed / rate.coerceAtLeast(MIN_AUDIO_RATE)).toLong()
}

/** This device's own audio length for the selected voice; it needs the chapter's characters. */
private fun measuredAudioMs(characterCount: Int?, measured: PreparedChapterMeasured?): Double? =
    measured?.audioMsPerCharacter?.takeIf { it > 0 }?.let { audioMsPerCharacter ->
        characterCount?.takeIf { it > 0 }?.let { characters -> characters * audioMsPerCharacter }
    }

/**
 * Prepared audio is AAC-LC mono at a steady bit rate, so its size is its length. A count of
 * sentences is the wrong unit: sentences vary in length, a second of audio does not.
 */
private fun preparedChapterBytes(sentenceCount: Int, audioMs: Long): Long =
    audioMs * PREPARED_BITS_PER_SECOND / (MS_PER_SECOND * BITS_PER_BYTE) +
        sentenceCount * PREPARED_CONTAINER_BYTES_PER_FILE

/** `AndroidTtsPreparedAacEncoder` configures `MediaFormat.KEY_BIT_RATE` at exactly this. */
private const val PREPARED_BITS_PER_SECOND = 48_000L
private const val BITS_PER_BYTE = 8L
private const val MS_PER_SECOND = 1_000L

/**
 * What the `.m4a` container costs on top of the audio itself, per file. Each row of the
 * six-sentence Samsung table in `docs/tts-prepared-chapters.md` ("Step 1: Samsung
 * measurements and format gate"), less its measured duration at 48 kbit/s, leaves 954 to
 * 1,190 bytes of container, mean 1,046.
 */
private const val PREPARED_CONTAINER_BYTES_PER_FILE = 1_050L

private const val MIN_AUDIO_RATE = 0.1f

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

/**
 * Spoken length of one sentence at normal speed, where the device has no record of its own.
 * The six-sentence probe above cannot give this: its three short Gutenberg sentences averaged
 * 2.2 s, and real chapter prose is longer. The one real chapter measured on a device (the
 * Samsung's nine Kokoro sentences, 219 kB of AAC on disk, which at 48 kbit/s less the
 * container is about 35 s of audio) averages 3.9 s a sentence, and its playback on the
 * emulator — sentence 7 of 9 within 25 s — agrees. A system voice keeps the probe's
 * system-to-Kokoro ratio of 1.14.
 */
private val FIXED_AUDIO_MS_PER_SENTENCE = mapOf(
    PreparedVoiceKind.SYSTEM to 4_400L,
    PreparedVoiceKind.KOKORO to 3_900L,
    PreparedVoiceKind.SUPERTONIC to 3_900L,
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
    rate: Float = 1f,
): PreparedChapterEstimate? {
    if (sentenceCount == null || sentenceCount <= 0) return null
    val msPerSentence = measured?.msPerSentence?.takeIf { it > 0 }
        ?: FIXED_MS_PER_SENTENCE.getValue(voiceKind)
    // The device's own speed for this voice is per character, so it needs the chapter's
    // characters; without both, the fixed figure per sentence stands.
    val measuredMs = measured?.msPerCharacter?.takeIf { it > 0 }?.let { msPerCharacter ->
        characterCount?.takeIf { it > 0 }?.let { characters -> (characters * msPerCharacter).toLong() }
    }
    // A flat bytes-a-sentence this device measured is kept, but only where it has no audio
    // length of its own: that length knows this chapter's text, where an average cannot.
    val perSentence = measured?.bytesPerSentence?.takeIf { it > 0 }
        ?.takeIf { measuredAudioMs(characterCount, measured) == null }
    val audioMs = preparedChapterAudioMs(sentenceCount, characterCount, voiceKind, measured, rate)
    return PreparedChapterEstimate(
        minutes = roundedMinutes(measuredMs ?: (sentenceCount * msPerSentence)),
        bytes = perSentence?.let { sentenceCount * it }
            ?: preparedChapterBytes(sentenceCount, audioMs ?: 0L),
    )
}

/**
 * Rounded the way a person says a duration: under three quarters of a minute is "under a
 * minute", then whole minutes while they are few enough to matter, then fives, then tens.
 */
internal fun roundedMinutes(totalMs: Long): Int {
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
