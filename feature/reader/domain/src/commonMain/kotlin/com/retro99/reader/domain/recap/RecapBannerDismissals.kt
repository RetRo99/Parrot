package com.retro99.reader.domain.recap

/** Recaps the reader shouldn't offer again. Dismissing never regenerates. */
interface RecapBannerDismissals {
    suspend fun isDismissed(sessionId: String): Boolean

    suspend fun dismiss(sessionId: String)

    companion object {
        const val MAX_ENTRIES = 200
    }
}
