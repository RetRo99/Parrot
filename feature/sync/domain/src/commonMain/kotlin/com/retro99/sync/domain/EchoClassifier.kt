package com.retro99.sync.domain

import kotlin.math.abs

/** What the write log remembers about this device's latest write into a copy. */
data class OwnWrite(
    /** Storyteller: the timestamp sent. Parrot Cloud: the acknowledged revision. */
    val marker: String?,
    val totalProgression: Double?,
)

/**
 * Tells whether a pulled position is an echo of this device's own write into that copy
 * (§1.4, guard 2). The one shared rule for sync pulls, the resume prompt and the positions
 * panel. Identity first, value as the fallback, as BookBridge does:
 * - When both the pulled position and our write carry a marker (Storyteller's timestamp,
 *   Parrot Cloud's revision), an equal marker is an echo and a different one is someone
 *   else's reading, however close the values.
 * - Otherwise (Audiobookshelf restamps every write), it's an echo when `totalProgression` is
 *   within 1% of what we wrote.
 */
object EchoClassifier {

    const val VALUE_TOLERANCE = 0.01

    fun isEcho(
        pulledMarker: String?,
        pulledTotalProgression: Double?,
        ownWrite: OwnWrite?,
    ): Boolean {
        if (ownWrite == null) return false
        if (pulledMarker != null && ownWrite.marker != null) {
            return pulledMarker == ownWrite.marker
        }
        val written = ownWrite.totalProgression ?: return false
        val pulled = pulledTotalProgression ?: return false
        return abs(pulled - written) < VALUE_TOLERANCE
    }
}
