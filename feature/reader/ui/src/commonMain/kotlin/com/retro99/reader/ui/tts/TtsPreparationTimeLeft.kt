package com.retro99.reader.ui.tts

/**
 * Work still to do, from what this run has measured so far. Null until the run has
 * generated [PREPARATION_MIN_SAMPLES] sentences, so the first number is not a wild guess.
 */
internal fun preparationRemainingMs(samples: List<TtsPreparationSample>, remainingCharacters: Int): Long? {
    val msPerCharacter = preparationMsPerCharacter(samples) ?: return null
    return (remainingCharacters.coerceAtLeast(0) * msPerCharacter).toLong()
}

/** The time left as it is shown, and when it was last changed. */
internal data class TtsPreparationTimeLeft(val remainingMs: Long, val shownAtMs: Long)

/**
 * The value to show next, given a fresh estimate. Steady on purpose: nothing new more often
 * than every five seconds, and a value that only goes down unless the one shown was wrong by
 * a lot (a quarter more, and at least half a minute).
 */
internal fun nextPreparationTimeLeft(
    shown: TtsPreparationTimeLeft?,
    estimateMs: Long?,
    nowMs: Long,
): TtsPreparationTimeLeft? {
    if (estimateMs == null) return shown
    if (shown == null) return TtsPreparationTimeLeft(estimateMs, nowMs)
    if (nowMs - shown.shownAtMs < MIN_UPDATE_INTERVAL_MS) return shown
    val wrongByALot = estimateMs > shown.remainingMs * CORRECTION_FACTOR &&
        estimateMs - shown.remainingMs >= MIN_CORRECTION_MS
    return if (estimateMs <= shown.remainingMs || wrongByALot) {
        TtsPreparationTimeLeft(estimateMs, nowMs)
    } else {
        shown
    }
}

private const val MIN_UPDATE_INTERVAL_MS = 5_000L
private const val CORRECTION_FACTOR = 1.25
private const val MIN_CORRECTION_MS = 30_000L
