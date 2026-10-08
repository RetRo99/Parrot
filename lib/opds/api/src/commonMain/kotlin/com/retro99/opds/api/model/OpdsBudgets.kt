package com.retro99.opds.api.model

/**
 * Initial budgets (plan §4). Tunables, not OPDS specification limits;
 * device-QA validation is a later-phase task.
 */
object OpdsBudgets {
    /** Decoded feed/description response budget. */
    const val MAX_RESPONSE_BYTES: Long = 5L * 1024 * 1024 // 5 MiB

    /** Ceiling for one downloaded book file. Separate from the feed budget above. */
    const val MAX_DOWNLOAD_BYTES: Long = 512L * 1024 * 1024 // 512 MiB

    /** Maximum XML/JSON structural nesting depth. */
    const val MAX_NESTING_DEPTH: Int = 64

    /** Maximum entries/publications per response. */
    const val MAX_ITEMS_PER_RESPONSE: Int = 2_000

    /** Maximum redirects a single fetch may follow (plan §4). */
    const val MAX_REDIRECTS: Int = 5

    /** In-memory parsed-document cache entries per profile (§10.10 budgets). */
    const val MAX_CACHE_ENTRIES: Int = 20

    /** In-memory parsed-document cache byte budget per profile (§10.10 budgets). */
    const val MAX_CACHE_BYTES: Long = 25L * 1024 * 1024 // 25 MiB
}
