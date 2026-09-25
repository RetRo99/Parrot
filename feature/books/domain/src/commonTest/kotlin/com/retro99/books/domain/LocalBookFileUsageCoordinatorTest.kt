package com.retro99.books.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalBookFileUsageCoordinatorTest {
    @Test
    fun removalCannotAcquireAPathWhileItIsInUse() = runTest {
        // Given
        val coordinator = LocalBookFileUsageCoordinator()
        val useLease = coordinator.acquireUse("/books/book.epub")

        // When
        val removalLease = coordinator.tryAcquireRemoval("/books/book.epub")

        // Then
        assertNull(removalLease)

        useLease.release()
        assertNotNull(coordinator.tryAcquireRemoval("/books/book.epub")).release()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aHeldRemovalLeaseBlocksNewUseUntilItIsReleased() = runTest {
        // Given
        val coordinator = LocalBookFileUsageCoordinator()
        val removalLease = assertNotNull(coordinator.tryAcquireRemoval("/books/book.epub"))
        val useLease = async { coordinator.acquireUse("/books/book.epub") }

        // When
        runCurrent()

        // Then
        assertFalse(useLease.isCompleted)

        removalLease.release()
        useLease.await().release()
        assertTrue(useLease.isCompleted)
    }

    @Test
    fun unrelatedPathsCanBeUsedIndependently() = runTest {
        // Given
        val coordinator = LocalBookFileUsageCoordinator()
        val firstUse = coordinator.acquireUse("/books/first.epub")

        // When
        val secondRemoval = coordinator.tryAcquireRemoval("/books/second.epub")

        // Then
        assertNotNull(secondRemoval)
        firstUse.release()
        secondRemoval.release()
    }

    @Test
    fun multipleUsesCanShareAPathAndAllMustReleaseBeforeRemoval() = runTest {
        // Given
        val coordinator = LocalBookFileUsageCoordinator()
        val firstUse = coordinator.acquireUse("/books/book.epub")
        val secondUse = coordinator.acquireUse("/books/book.epub")

        // When
        firstUse.release()
        val removalBeforeLastUse = coordinator.tryAcquireRemoval("/books/book.epub")

        // Then
        assertNull(removalBeforeLastUse)
        secondUse.release()
        assertNotNull(coordinator.tryAcquireRemoval("/books/book.epub")).release()
    }
}
