package com.retro99.reader.ui.reader

import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes position writes, coalesces newer locators, and pauses automatic writes after failure. */
internal class ReaderPositionSaveCoordinator<T>(
    private val scope: CoroutineScope,
    private val save: suspend (T) -> CompletableResult,
    private val onAttempted: (isRetry: Boolean, entryPoint: String) -> Unit,
    private val onSucceeded: (isRetry: Boolean, recoveredFailure: Boolean, entryPoint: String) -> Unit,
    private val onFailed: (error: AppError, isRetry: Boolean, entryPoint: String) -> Unit,
    private val onCancelled: (isRetry: Boolean, entryPoint: String) -> Unit,
) {
    private val saveMutex = Mutex()
    private var latestValue: T? = null
    private var latestVersion = 0L
    private var hasFailure = false
    private var isSaving = false
    private var isClosing = false

    fun submit(value: T) {
        if (isClosing) return
        latestValue = value
        latestVersion += 1
        if (!hasFailure) startSave(isRetry = false)
    }

    /** Retries the latest position, which may be newer than the position that originally failed. */
    fun retry(): Boolean {
        if (isClosing || !hasFailure || isSaving || latestValue == null) return false
        startSave(isRetry = true)
        return true
    }

    /** Persists the final in-memory locator after any active write, despite Reader teardown. */
    suspend fun saveForClose(value: T) {
        latestValue = value
        latestVersion += 1
        isClosing = true
        saveMutex.withLock {
            isSaving = true
            try {
                saveOne(value, isRetry = false, entryPoint = "reader_close")
            } catch (_: CancellationException) {
                // A repository may surface an expected cancellation result/exception. It must
                // not abort the remaining Reader close bookkeeping running in NonCancellable.
                onCancelled(false, "reader_close")
            } finally {
                isSaving = false
            }
        }
    }

    private fun startSave(isRetry: Boolean) {
        if (isSaving) return
        val value = latestValue ?: return
        val versionAtStart = latestVersion
        isSaving = true
        val entryPoint = if (isRetry) "retry" else "position_change"

        scope.launch {
            var didSucceed = false
            var wasCancelled = false
            var wasSuperseded = false
            try {
                saveMutex.withLock {
                    if (isClosing) return@withLock
                    if (versionAtStart != latestVersion) {
                        wasSuperseded = true
                        return@withLock
                    }
                    didSucceed = saveOne(value, isRetry, entryPoint)
                }
            } catch (cancellation: CancellationException) {
                wasCancelled = true
                onCancelled(isRetry, entryPoint)
                throw cancellation
            } finally {
                isSaving = false
                if ((didSucceed || wasCancelled || wasSuperseded) && !hasFailure && !isClosing &&
                    latestVersion > versionAtStart && scope.isActive
                ) {
                    startSave(isRetry = false)
                }
            }
        }
    }

    private suspend fun saveOne(value: T, isRetry: Boolean, entryPoint: String): Boolean {
        onAttempted(isRetry, entryPoint)
        val result = try {
            save(value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            com.github.michaelbull.result.Err(AppError.UnknownError(error))
        }

        var didSucceed = false
        result
            .onSuccess {
                val recoveredFailure = hasFailure
                hasFailure = false
                didSucceed = true
                onSucceeded(isRetry, recoveredFailure, entryPoint)
            }
            .onFailure { error ->
                if (error is AppError.AuthError && error.isCancellation) {
                    onCancelled(isRetry, entryPoint)
                } else {
                    hasFailure = true
                    onFailed(error, isRetry, entryPoint)
                }
            }
        return didSucceed
    }
}
