package com.retro99.reader.domain.write

import kotlinx.coroutines.flow.Flow

/** "Update my other servers as I read". On by default. */
interface LinkedCopyPropagationSetting {
    fun observeEnabled(): Flow<Boolean>

    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)
}
