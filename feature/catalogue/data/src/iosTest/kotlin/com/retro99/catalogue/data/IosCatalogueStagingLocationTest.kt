package com.retro99.catalogue.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IosCatalogueStagingLocationTest {

    @Test
    fun `staging is in Application Support and not in the temporary directory`() {
        // When
        val path = IosCatalogueStagingFiles().newPartPath("profile-1")

        // Then
        assertTrue("/Application Support/catalogue_staging/profile-1/" in path, path)
        assertFalse(path.startsWith(NSTemporaryDirectory()), "the system clears the temporary directory")
    }

    @Test
    fun `the staging folder is marked as not to be backed up once it is used`() = runTest {
        // Given
        val directory = "${NSTemporaryDirectory()}catalogue-backup-test-${NSUUID().UUIDString}"
        val files = PosixCatalogueStagingFiles("$directory/catalogue_staging", excludeFromBackup = true)
        val unmarked = PosixCatalogueStagingFiles("$directory/unmarked")
        try {
            // When
            files.openForWriting(files.newPartPath("profile-1")).close()
            unmarked.openForWriting(unmarked.newPartPath("profile-1")).close()

            // Then
            assertEquals(true, files.isExcludedFromBackup())
            assertEquals(false, unmarked.isExcludedFromBackup())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
        }
    }
}
