package com.retro99.reader.ui.reader

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPositionSaveCoordinatorTest {

    @Test
    fun `failure pauses autosaves and retry persists the latest position`() = runTest {
        val attemptedPositions = mutableListOf<String>()
        val failures = mutableListOf<Pair<AppError, Boolean>>()
        val successes = mutableListOf<Pair<Boolean, Boolean>>()
        var retryAttempts = 0
        var shouldFail = true
        val coordinator = ReaderPositionSaveCoordinator<String>(
            scope = backgroundScope,
            save = { position ->
                attemptedPositions += position
                if (shouldFail) Err(AppError.DatabaseError(IllegalStateException())) else Ok(Unit)
            },
            onAttempted = { isRetry, _ -> if (isRetry) retryAttempts += 1 },
            onSucceeded = { isRetry, recovered, _ -> successes += isRetry to recovered },
            onFailed = { error, isRetry, _ -> failures += error to isRetry },
            onCancelled = { isRetry, _ -> error("Unexpected cancellation (retry=$isRetry)") },
        )

        coordinator.submit("page-one")
        runCurrent()
        assertEquals(listOf("page-one"), attemptedPositions)
        assertEquals(1, failures.size)
        assertFalse(failures.single().second)

        coordinator.submit("page-two")
        runCurrent()
        assertEquals(listOf("page-one"), attemptedPositions, "automatic retries stay paused after a failure")

        shouldFail = false
        assertTrue(coordinator.retry())
        runCurrent()

        assertEquals(listOf("page-one", "page-two"), attemptedPositions)
        assertEquals(1, retryAttempts)
        assertEquals(listOf(true to true), successes)
        assertFalse(coordinator.retry(), "a successful retry clears the failure state")
    }

    @Test
    fun `writes are serialized and newer locators are coalesced`() = runTest {
        val attemptedPositions = mutableListOf<String>()
        val failures = mutableListOf<AppError>()
        val successes = mutableListOf<Pair<Boolean, Boolean>>()
        val coordinator = ReaderPositionSaveCoordinator<String>(
            scope = backgroundScope,
            save = { position ->
                attemptedPositions += position
                Ok(Unit)
            },
            onAttempted = { _, _ -> },
            onSucceeded = { isRetry, recovered, _ -> successes += isRetry to recovered },
            onFailed = { error, _, _ -> failures += error },
            onCancelled = { isRetry, _ -> error("Unexpected cancellation (retry=$isRetry)") },
        )

        coordinator.submit("page-one")
        coordinator.submit("page-two")
        coordinator.submit("page-three")
        runCurrent()

        assertEquals(listOf("page-three"), attemptedPositions)
        assertEquals(emptyList(), failures)
        assertEquals(listOf(false to false), successes)
    }

    @Test
    fun `cancellation is not reported as a failed save`() = runTest {
        val cancellations = mutableListOf<Boolean>()
        val failures = mutableListOf<AppError>()
        var cancelFirstSave = true
        val coordinator = ReaderPositionSaveCoordinator<String>(
            scope = backgroundScope,
            save = {
                if (cancelFirstSave) {
                    cancelFirstSave = false
                    throw CancellationException("expected cancellation")
                }
                Ok(Unit)
            },
            onAttempted = { _, _ -> },
            onSucceeded = { _, _, _ -> },
            onFailed = { error, _, _ -> failures += error },
            onCancelled = { isRetry, _ -> cancellations += isRetry },
        )

        coordinator.submit("page-one")
        runCurrent()
        coordinator.submit("page-two")
        runCurrent()

        assertEquals(listOf(false), cancellations)
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `close save runs after an active checkpoint and persists the final locator`() = runTest {
        val activeSave = CompletableDeferred<Unit>()
        val attemptedPositions = mutableListOf<String>()
        val attemptEntryPoints = mutableListOf<String>()
        val coordinator = ReaderPositionSaveCoordinator<String>(
            scope = backgroundScope,
            save = { position ->
                attemptedPositions += position
                if (position == "page-27") activeSave.await()
                Ok(Unit)
            },
            onAttempted = { _, entryPoint -> attemptEntryPoints += entryPoint },
            onSucceeded = { _, _, _ -> },
            onFailed = { error, _, _ -> error("Unexpected failure: $error") },
            onCancelled = { isRetry, _ -> error("Unexpected cancellation (retry=$isRetry)") },
        )

        coordinator.submit("page-27")
        runCurrent()
        val closeSave = async { coordinator.saveForClose("page-28") }
        runCurrent()
        assertEquals(listOf("page-27"), attemptedPositions)

        activeSave.complete(Unit)
        runCurrent()
        closeSave.await()

        assertEquals(listOf("page-27", "page-28"), attemptedPositions)
        assertEquals(listOf("position_change", "reader_close"), attemptEntryPoints)
    }

    @Test
    fun `close skips a queued stale page write`() = runTest {
        val attemptedPositions = mutableListOf<String>()
        val attemptEntryPoints = mutableListOf<String>()
        val coordinator = ReaderPositionSaveCoordinator<String>(
            scope = backgroundScope,
            save = { position ->
                attemptedPositions += position
                Ok(Unit)
            },
            onAttempted = { _, entryPoint -> attemptEntryPoints += entryPoint },
            onSucceeded = { _, _, _ -> },
            onFailed = { error, _, _ -> error("Unexpected failure: $error") },
            onCancelled = { isRetry, _ -> error("Unexpected cancellation (retry=$isRetry)") },
        )

        coordinator.submit("page-30")
        coordinator.saveForClose("page-31")
        runCurrent()

        assertEquals(listOf("page-31"), attemptedPositions)
        assertEquals(listOf("reader_close"), attemptEntryPoints)
    }

    @Test
    fun `close save cancellation is recorded without aborting the caller`() = runTest {
        val cancellations = mutableListOf<Pair<Boolean, String>>()
        val coordinator = ReaderPositionSaveCoordinator<String>(
            scope = backgroundScope,
            save = { throw CancellationException("expected repository cancellation") },
            onAttempted = { _, _ -> },
            onSucceeded = { _, _, _ -> },
            onFailed = { error, _, _ -> error("Unexpected failure: $error") },
            onCancelled = { isRetry, entryPoint -> cancellations += isRetry to entryPoint },
        )

        coordinator.saveForClose("final-page")

        assertEquals(listOf(false to "reader_close"), cancellations)
    }
}
