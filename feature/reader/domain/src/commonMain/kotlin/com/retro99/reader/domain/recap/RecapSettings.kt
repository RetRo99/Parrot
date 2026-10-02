package com.retro99.reader.domain.recap

import kotlinx.coroutines.flow.Flow

/**
 * Recap consent. Cloud recaps send read text to the recap API, so they are
 * off until the user opts in. A future offline engine gets its own key.
 */
interface RecapSettings {
    fun observeCloudRecapsEnabled(): Flow<Boolean>

    suspend fun isCloudRecapsEnabled(): Boolean

    suspend fun setCloudRecapsEnabled(enabled: Boolean)
}
