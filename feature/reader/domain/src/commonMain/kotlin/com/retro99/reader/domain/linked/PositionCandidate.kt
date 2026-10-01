package com.retro99.reader.domain.linked

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.sync.domain.ObservedTime

/** One copy's position, as a candidate for the latest real reading (P1). */
data class PositionCandidate(
    val copy: LinkedCopy,
    val position: PositionDomainModel,
) {
    val observedAtMillis: Long? =
        ObservedTime.toEpochMillis(position.observedAt ?: position.updatedAt)

    val isRealReading: Boolean get() = position.origin.isRealReading
}

/** The newest real reading among [candidates], by observation time. */
fun latestRealReading(candidates: List<PositionCandidate>): PositionCandidate? =
    candidates
        .filter { candidate -> candidate.isRealReading && candidate.observedAtMillis != null }
        .maxByOrNull { candidate -> requireNotNull(candidate.observedAtMillis) }
