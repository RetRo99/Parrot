package com.retro99.sync.data

/**
 * Stable identity captured for one synchronization pass.
 */
data class SyncExecutionContext(
    val localProfileId: String,
    val remoteAccountId: String,
)

interface SyncExecutionContextProvider {
    suspend fun <T> withPinnedContext(
        operation: suspend (SyncExecutionContext) -> T,
    ): SyncExecutionResult<T>
}

sealed interface SyncExecutionResult<out T> {
    data class Ready<T>(val value: T) : SyncExecutionResult<T>

    data object NotConfigured : SyncExecutionResult<Nothing>
    data object NotAuthenticated : SyncExecutionResult<Nothing>
    data object ProfileNotLinked : SyncExecutionResult<Nothing>
    data object SyncDisabled : SyncExecutionResult<Nothing>
    data class Failed(val message: String) : SyncExecutionResult<Nothing>
}
