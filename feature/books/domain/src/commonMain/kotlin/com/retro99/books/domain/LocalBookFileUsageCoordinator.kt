package com.retro99.books.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/** Coordinates access to local book files with operations that may remove them. */
@Single
class LocalBookFileUsageCoordinator {
    private val locksMutex = Mutex()
    private val locksByPath = mutableMapOf<String, PathLock>()

    /** Waits for other access to this path to finish and keeps it in use until released. */
    suspend fun acquireUse(path: String): LocalBookFileUsageLease = lockFor(path).acquireUse()

    /** Claims exclusive removal access when no other operation currently uses this path. */
    suspend fun tryAcquireRemoval(path: String): LocalBookFileUsageLease? =
        lockFor(path).tryAcquireRemoval()

    /** Waits for current uses to finish, then prevents any new use until released. */
    suspend fun acquireRemoval(path: String): LocalBookFileUsageLease =
        lockFor(path).acquireRemoval()

    private suspend fun lockFor(path: String): PathLock = locksMutex.withLock {
        locksByPath.getOrPut(path) { PathLock() }
    }

    private class PathLock {
        private val admissionGate = Mutex()
        private val usePermits = Semaphore(MAX_CONCURRENT_USES)

        suspend fun acquireUse(): LocalBookFileUsageLease {
            while (true) {
                admissionGate.lock()
                if (usePermits.tryAcquire()) {
                    admissionGate.unlock()
                    return LocalBookFileUsageLease { usePermits.release() }
                }
                admissionGate.unlock()

                usePermits.acquire()
                usePermits.release()
            }
        }

        suspend fun tryAcquireRemoval(): LocalBookFileUsageLease? {
            if (!admissionGate.tryLock()) return null

            var acquiredPermits = 0
            while (acquiredPermits < MAX_CONCURRENT_USES) {
                if (!usePermits.tryAcquire()) {
                    repeat(acquiredPermits) { usePermits.release() }
                    admissionGate.unlock()
                    return null
                }
                acquiredPermits++
            }

            return LocalBookFileUsageLease {
                repeat(MAX_CONCURRENT_USES) { usePermits.release() }
                admissionGate.unlock()
            }
        }

        suspend fun acquireRemoval(): LocalBookFileUsageLease {
            admissionGate.lock()
            var acquiredPermits = 0
            try {
                while (acquiredPermits < MAX_CONCURRENT_USES) {
                    usePermits.acquire()
                    acquiredPermits++
                }
            } catch (exception: Throwable) {
                repeat(acquiredPermits) { usePermits.release() }
                admissionGate.unlock()
                throw exception
            }

            return LocalBookFileUsageLease {
                repeat(MAX_CONCURRENT_USES) { usePermits.release() }
                admissionGate.unlock()
            }
        }
    }

    private companion object {
        const val MAX_CONCURRENT_USES = 256
    }
}

/** A held use or removal claim. Call [release] exactly once. */
class LocalBookFileUsageLease internal constructor(
    private val releaseAction: () -> Unit,
) {
    fun release() {
        releaseAction()
    }
}
