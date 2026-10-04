package com.retro99.reader.domain.recap

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class RecapPresentation { AFTER_BREAK, EVERY_TIME, NEVER }

/**
 * Recap consent. Cloud recaps send read text to the recap API, so they are
 * off until the user opts in. A future offline engine gets its own key.
 */
interface RecapSettings {
    fun observeFeatureAvailable(): Flow<Boolean> = flowOf(false)
    fun observeSignedIn(): Flow<Boolean> = flowOf(false)
    fun observePresentation(): Flow<RecapPresentation> = flowOf(RecapPresentation.AFTER_BREAK)
    suspend fun setPresentation(value: RecapPresentation) {}
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
