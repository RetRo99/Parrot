package com.retro99.database.api.links

/** What the user decided about a pair of copies that was suggested as the same book. */
data class BookLinkDecisionEntity(
    val pairKey: String,
    val decision: String,
    val decidedAt: String,
    val remoteRevision: Long? = null,
) {
    companion object {
        const val DECISION_NEVER = "never"
        const val DECISION_SKIP = "skip"
    }
}
