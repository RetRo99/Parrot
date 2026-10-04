package com.retro99.reader.domain.recap

import kotlinx.coroutines.flow.Flow

/**
 * Recap consent. Cloud recaps send read text to the recap API, so they are
 * off until the user opts in. A future offline engine gets its own key.
 */
interface RecapSettings {
    fun observeCloudRecapsEnabled(): Flow<Boolean>

    /**
     * The consent alone, whether or not a usable Parrot Cloud session exists.
     * [observeCloudRecapsEnabled] also needs a live session, so it cannot tell
     * "turn Cloud recaps on" apart from "sign in again".
     */
    fun observeConsentGiven(): Flow<Boolean>

    suspend fun isCloudRecapsEnabled(): Boolean

    /** Persisted capture consent, independent of authentication restoration/delivery. */
    suspend fun isConsentGiven(accountId: String? = null): Boolean = isCloudRecapsEnabled()

    suspend fun setCloudRecapsEnabled(enabled: Boolean)
}
