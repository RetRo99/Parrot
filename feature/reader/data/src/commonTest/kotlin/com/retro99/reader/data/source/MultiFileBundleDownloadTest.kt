package com.retro99.reader.data.source

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiFileBundleDownloadTest {
    @Test
    fun secondFileFailureLeavesNoCachedBundleAndRetryPublishesCompleteBundle() = runTest {
        val store = FakeBundleStore()
        store.failedSourcePath = "second"

        val failedResult = store.download(paths = listOf("first", "second"))

        assertEquals("failed:second", failedResult)
        assertFalse(store.isCached())
        assertEquals(emptyMap(), store.stagedFiles())

        store.failedSourcePath = null
        val retryResult = store.download(paths = listOf("first", "second"))

        assertEquals("cached:/cache/book", retryResult)
        assertTrue(store.isCached())
        assertEquals(
            mapOf("01" to "downloaded:first", "02" to "downloaded:second"),
            store.cachedFiles(),
        )
    }

    @Test
    fun failedReplacementPreservesPreviouslyCachedBundle() = runTest {
        val store = FakeBundleStore().apply {
            seedCachedFiles("old-first", "old-second")
            failedSourcePath = "second"
        }

        val result = store.download(paths = listOf("first", "second"))

        assertEquals("failed:second", result)
        assertTrue(store.isCached())
        assertEquals(mapOf("01" to "old-first", "02" to "old-second"), store.cachedFiles())
        assertEquals(emptyMap(), store.stagedFiles())
    }

    @Test
    fun cancelledReplacementCleansStagingAndPreservesPreviouslyCachedBundle() = runTest {
        val store = FakeBundleStore().apply {
            seedCachedFiles("old-first", "old-second")
            suspendAtSourcePath = "second"
        }

        val download = launch {
            store.download(paths = listOf("first", "second"))
        }
        runCurrent()
        download.cancelAndJoin()

        assertTrue(store.isCached())
        assertEquals(mapOf("01" to "old-first", "02" to "old-second"), store.cachedFiles())
        assertEquals(emptyMap(), store.stagedFiles())
    }

    private class FakeBundleStore {
        private val targetDirectory = "/cache/book"
        private val stagingDirectory = "/cache/.book.download"
        private val files = mutableMapOf<String, String>()
        var failedSourcePath: String? = null
        var suspendAtSourcePath: String? = null

        suspend fun download(paths: List<String>): String = downloadMultiFileBundle(
            filePaths = paths,
            destinationPath = { index -> "$stagingDirectory/${fileName(index)}" },
            prepareStagingDirectory = { deleteStagingFiles() },
            downloadFile = { sourcePath, destinationPath, _ ->
                if (sourcePath == failedSourcePath) {
                    files[destinationPath] = "partial:$sourcePath"
                    "failed:$sourcePath"
                } else {
                    files[destinationPath] = "downloaded:$sourcePath"
                    if (sourcePath == suspendAtSourcePath) {
                        awaitCancellation()
                    } else {
                        "ok"
                    }
                }
            },
            isFailure = { result -> result.startsWith("failed:") },
            promoteStagingDirectory = { promoteStagingDirectory() },
            deleteStagingDirectory = { deleteStagingFiles() },
            successResult = { "cached:$targetDirectory" },
        )

        fun seedCachedFiles(first: String, second: String) {
            files["$targetDirectory/01"] = first
            files["$targetDirectory/02"] = second
        }

        fun isCached(): Boolean = files.keys.any { path -> path.startsWith("$targetDirectory/") }

        fun cachedFiles(): Map<String, String> = files
            .filterKeys { path -> path.startsWith("$targetDirectory/") }
            .mapKeys { (path, _) -> path.substringAfterLast('/') }

        fun stagedFiles(): Map<String, String> = files
            .filterKeys { path -> path.startsWith("$stagingDirectory/") }

        private fun deleteStagingFiles() {
            files.keys.removeAll { path -> path.startsWith("$stagingDirectory/") }
        }

        private fun promoteStagingDirectory() {
            files.keys.removeAll { path -> path.startsWith("$targetDirectory/") }
            val stagedFiles = stagedFiles()
            deleteStagingFiles()
            stagedFiles.forEach { (path, contents) ->
                files["$targetDirectory/${path.substringAfterLast('/')}"] = contents
            }
        }

        private fun fileName(index: Int): String = (index + 1).toString().padStart(2, '0')
    }
}
