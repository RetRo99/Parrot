package com.retro99.reader.domain.recap

/** Recaps the reader shouldn't offer again. Dismissing never regenerates. */
interface RecapBannerDismissals {
    suspend fun isDismissed(sessionId: String): Boolean

    suspend fun dismiss(sessionId: String)

    companion object {
        /**
         * Safety bound only. Entries are pruned to recaps that still exist, so
         * the cap must not evict one and make a dismissed chip resurface.
         */
        const val MAX_ENTRIES = 1_000
    }
}
