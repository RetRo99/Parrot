package com.retro99.books.domain.model.links

import kotlin.time.Instant

enum class LinkDecisionType(val value: String) {
    /** "Not the same book": the pair is never suggested again. */
    Never("never"),

    /** "Skip": the pair is hidden for a while. */
    Skip("skip"),
    ;

    companion object {
        fun fromValue(value: String): LinkDecisionType? =
            entries.firstOrNull { entry -> entry.value == value }
    }
}

/** What the user decided about a suggested pair. [pairKey] comes from [pairKey]. */
data class LinkDecision(
    val pairKey: String,
    val type: LinkDecisionType,
    val decidedAt: Instant,
)
