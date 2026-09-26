package com.retro99.base.repository

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.result.logOnFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.supervisorScope
import org.koin.core.component.KoinComponent

/**
 * Base repository interface that provides common functionality for all repositories.
 * Uses KoinComponent to inject Analytics for exception logging.
 */
interface BaseRepository : KoinComponent {

    private val analytics: Analytics
        get() = getKoin().get()

    /**
     * Fetches data from cache and remote in parallel.
     * - Emits cached data first if available (as Ok)
     * - Emits remote data when it arrives and saves it to cache (as Ok)
     * - If remote fails and cache was empty, emits the error (as Err)
     * - If remote fails but cache was available, silently ignores the error
     *
     * @param cacheSource Suspend function that returns cached data result (null value means no cache)
     * @param remoteSource Suspend function that fetches data from remote
     * @param saveToCache Suspend function that saves remote data to cache and returns result
     * @return Flow that emits AppResult with cached data (if available) then remote data
     */
    fun <T : Any> cachedRemoteFlow(
        cacheSource: suspend () -> AppResult<T?>,
        remoteSource: suspend () -> AppResult<T>,
        saveToCache: suspend (T) -> CompletableResult,
    ): Flow<AppResult<T>> = flow {
        supervisorScope {
            val deferredCache = async { cacheSource() }
            val deferredRemote = async { remoteSource() }

            val cacheResult = deferredCache.await()
            val cachedData = cacheResult.getOrElse { cacheError ->
                // Log cache read failures for debugging - these were previously silent!
                analytics.logException(
                    cacheError.toThrowable(),
                    "Cache read failed, will rely on remote source",
                )
                null
            }
            if (cachedData != null) {
                emit(Ok(cachedData))
            }

            deferredRemote.await()
                .onSuccess { remoteData ->
                    // Skip the cache write when the remote payload matches what is already
                    // stored. These flows are polled continuously and the write fans out over
                    // several tables per book, so replaying it on every poll dominated the
                    // app's storage writes.
                    if (remoteData != cachedData) {
                        saveToCache(remoteData).logOnFailure(
                            analytics,
                            "Failed to save data to cache",
                        )
                    }
                    emit(Ok(remoteData))
                }
                .getOrElse { error ->
                    // Log the remote error for debugging, even when falling back to cache
                    analytics.logException(
                        error.toThrowable(),
                        "Remote fetch failed${if (cachedData != null) ", using cached data" else ""}",
                    )
                    if (cachedData == null) {
                        emit(Err(error))
                    }
                }
        }
    }

    /**
     * Fetches data from remote first, falls back to cache on failure.
     * - Tries remote source first
     * - If remote succeeds, saves to cache and returns the result
     * - If remote fails, tries to get data from cache
     * - If both fail, returns the remote error
     *
     * @param remoteSource Suspend function that fetches data from remote
     * @param cacheSource Suspend function that returns cached data result (null value means no cache)
     * @param saveToCache Suspend function that saves remote data to cache and returns result
     * @return AppResult with remote data, or cached data on remote failure, or error if both fail
     */
    suspend fun <T : Any> remoteWithCacheFallback(
        remoteSource: suspend () -> AppResult<T?>,
        cacheSource: suspend () -> AppResult<T?>,
        saveToCache: suspend (T) -> CompletableResult,
    ): AppResult<T?> {
        val remoteResult = remoteSource()

        remoteResult.onSuccess { remoteData ->
            if (remoteData != null) {
                saveToCache(remoteData).logOnFailure(
                    analytics,
                    "Failed to save data to cache",
                )
            }
        }

        return remoteResult.getOrElse { remoteError ->
            // Remote failed, try cache
            val cacheResult = cacheSource()
            val cachedData = cacheResult.getOrElse { cacheError ->
                // Log cache read failures for debugging - these were previously silent!
                analytics.logException(
                    cacheError.toThrowable(),
                    "Cache fallback read failed after remote error",
                )
                null
            }
            return if (cachedData != null) {
                Ok(cachedData)
            } else {
                Err(remoteError)
            }
        }.let { Ok(it) }
    }
}
