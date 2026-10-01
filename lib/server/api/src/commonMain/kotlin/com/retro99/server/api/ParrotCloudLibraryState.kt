package com.retro99.server.api

import kotlinx.coroutines.flow.Flow

/** Whether the current profile has an active Parrot Cloud account. */
interface ParrotCloudLibraryState {
    fun observeIsActive(): Flow<Boolean>
}
