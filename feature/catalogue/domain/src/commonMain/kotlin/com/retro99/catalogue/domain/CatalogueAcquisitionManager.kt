package com.retro99.catalogue.domain

import kotlinx.coroutines.flow.Flow

/**
 * The durable queue of catalogue downloads for the profile that is open.
 *
 * Downloads keep running after the screen that started them closes, never into another
 * profile. After Parrot restarts nothing starts by itself: whatever was running is
 * [AcquisitionState.Interrupted], and waiting downloads stay waiting until [request],
 * [retry], [startAgain], [signedIn] or [start] is called.
 */
interface CatalogueAcquisitionManager {
    /** Every stored download of the open profile in queue order, with live byte counts. */
    fun observeAcquisitions(): Flow<List<CatalogueAcquisition>>

    /**
     * Queues a download and starts the queue. Asking again for a file that has an unfinished
     * request returns that request; nothing is queued twice.
     */
    suspend fun request(request: CatalogueAcquisitionRequest): CatalogueAcquisition

    /** Deletes the request, its row and its partial file. False when it is finished or gone. */
    suspend fun cancel(requestId: String): Boolean

    /** Queues a failed download again. Only for connection, storage and refused failures. */
    suspend fun retry(requestId: String): Boolean

    /** Removes a failed download that retrying cannot fix: too large, invalid, protected. */
    suspend fun dismiss(requestId: String): Boolean

    /** Queues an interrupted download again, from zero. */
    suspend fun startAgain(requestId: String): Boolean

    /** Account details were saved for [sourceId]: its sign-in failures are queued again. */
    suspend fun signedIn(sourceId: String)

    /** Lets waiting downloads run. Call it for a user action that means "carry on". */
    suspend fun start()

    /**
     * Call once when Parrot starts. Marks whatever was downloading, checking or adding when
     * it closed as interrupted and removes the partial files. Starts nothing.
     */
    suspend fun restoreAfterRestart()

    /** Finished downloads completed in the last 24 hours, newest first. */
    suspend fun finishedInLast24Hours(): List<CatalogueAcquisition>

    /** Forgets every finished download: the Downloads screen was left. Books are untouched. */
    suspend fun purgeFinished()

    /** Forgets finished downloads older than 24 hours. */
    suspend fun purgeExpired()
}
