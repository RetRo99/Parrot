package com.retro99.reader.domain.linked

/**
 * "Stay here" answers to the resume prompt, as `<targetKey>|<sourceKey>|<source observedAt>`.
 * The newest 200 are kept.
 */
interface LinkedResumeDismissals {
    suspend fun isDismissed(entry: String): Boolean

    suspend fun dismiss(entry: String)

    companion object {
        const val MAX_ENTRIES = 200
    }
}
