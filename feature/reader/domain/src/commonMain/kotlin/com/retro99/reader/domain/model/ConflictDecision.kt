package com.retro99.reader.domain.model

import com.retro99.sync.domain.ObservedTime

/**
 * Why a same-copy position difference is settled the way it is: the dialog is only for
 * genuinely ambiguous candidates; obvious answers settle themselves with, at most, a
 * quiet bar in the reader (§1 of the position-prompt spec).
 */
sealed interface ConflictDecision {

    /** Both sides agree within one percent: keep this device's position, say nothing. */
    data object Silence : ConflictDecision

    /**
     * This device is newer and further. The reader keeps its position and shows the
     * settle bar offering the other side once ("Kept your place · 86% / Use 78%").
     */
    data class KeepThis(
        /** The other position, applied when the person taps the bar's action. */
        val otherPosition: PositionDomainModel,
    ) : ConflictDecision

    /**
     * The other position is newer and further. The reader moves to it and shows the
     * settle bar offering the way back once ("Moved to 86% / Go back to 78%").
     */
    data class MoveToOther(
        /** The losing position, restored when the person taps the bar's action. */
        val losingPosition: PositionDomainModel,
    ) : ConflictDecision

    /** Mixed (one newer, the other further), a missing timestamp, or a missing percent. */
    data object Ask : ConflictDecision

    /** True when the difference needs the interactive dialog; false settles itself. */
    val isInteractive: Boolean
        get() = this is Ask
}

/** How far apart two percentages must be, in percent points, before it is a conflict. */
const val POSITION_PERCENT_TOLERANCE: Double = 1.0

/**
 * Compares the two positions on which is newer (the last real reading time) and which
 * is further (the percentage):
 * - this device newer and further → [ConflictDecision.KeepThis]
 * - other newer and further → [ConflictDecision.MoveToOther]
 * - mixed, or a timestamp or percentage is missing → [ConflictDecision.Ask]
 * - differences under [POSITION_PERCENT_TOLERANCE] → [ConflictDecision.Silence]
 */
fun conflictDecision(
    localPosition: PositionDomainModel,
    remotePosition: PositionDomainModel,
): ConflictDecision {
    val localPercent = localPosition.totalProgression?.coerceIn(0.0, 1.0)
        ?: return ConflictDecision.Ask
    val remotePercent = remotePosition.totalProgression?.coerceIn(0.0, 1.0)
        ?: return ConflictDecision.Ask
    if (kotlin.math.abs(localPercent - remotePercent) * 100.0 < POSITION_PERCENT_TOLERANCE) {
        return ConflictDecision.Silence
    }
    val localMillis = localPosition.epochObservedAtMillis() ?: return ConflictDecision.Ask
    val remoteMillis = remotePosition.epochObservedAtMillis() ?: return ConflictDecision.Ask
    val localNewer = localMillis > remoteMillis
    val remoteNewer = remoteMillis > localMillis
    val localFurther = localPercent > remotePercent
    val remoteFurther = remotePercent > localPercent
    return when {
        localNewer && localFurther -> ConflictDecision.KeepThis(remotePosition)
        remoteNewer && remoteFurther -> ConflictDecision.MoveToOther(localPosition)
        else -> ConflictDecision.Ask
    }
}

/** [PositionDomainModel.observedAt] as epoch millis, or null when missing/unparseable. */
private fun PositionDomainModel.epochObservedAtMillis(): Long? =
    ObservedTime.toEpochMillis(observedAt)

