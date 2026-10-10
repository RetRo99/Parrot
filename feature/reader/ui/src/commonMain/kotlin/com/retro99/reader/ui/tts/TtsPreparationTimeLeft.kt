package com.retro99.reader.ui.tts

/**
 * Work still to do, from what this run has measured so far. Null until the run has
 * generated [PREPARATION_MIN_SAMPLES] sentences, so the first number is not a wild guess.
 */
@Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
internal fun preparationRemainingMs(samples: List<TtsPreparationSample>, remainingCharacters: Int): Long? = null

/** The time left as it is shown, and when it was last changed. */
internal data class TtsPreparationTimeLeft(val remainingMs: Long, val shownAtMs: Long)

/** The value to show next, given a fresh estimate. */
@Suppress("UnusedParameter")
internal fun nextPreparationTimeLeft(
    shown: TtsPreparationTimeLeft?,
    estimateMs: Long?,
    nowMs: Long,
): TtsPreparationTimeLeft? = null
