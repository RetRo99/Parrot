package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudFileTransferStateGuardTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun persistedCancellationRejectsALateCompletion() = runBlocking {
        insertTransfer(state = "finalizing")

        updateTransfer(state = "cancelled", expectedStates = ACTIVE_TRANSFER_STATES)
        updateTransfer(state = "completed", expectedStates = ACTIVE_TRANSFER_STATES)

        assertEquals("cancelled", database.cloudFileTransferQueries
            .getCloudFileTransfer(TRANSFER_ID)
            .executeAsOne()
            .state)
    }

    @Test
    fun exactCloudFileDeletionLeavesAnotherSameMediaResource() = runBlocking {
        insertCloudFile(cloudBookFileId = "first-file", relativePath = "")
        insertCloudFile(cloudBookFileId = "selected-file", relativePath = "selected.epub")

        database.cloudBookFileStateQueries.deleteCloudBookFileStateByCloudBookFileId(
            library_book_id = LIBRARY_BOOK_ID,
            cloud_book_file_id = "selected-file",
        )

        assertEquals(
            listOf("first-file"),
            database.cloudBookFileStateQueries.getCloudBookFileStates(LIBRARY_BOOK_ID)
                .executeAsList()
                .map { file -> file.cloud_book_file_id },
        )
    }

    private fun insertTransfer(state: String) {
        database.cloudFileTransferQueries.insertCloudFileTransfer(
            transfer_id = TRANSFER_ID,
            server_id = "parrot-cloud",
            direction = "upload",
            library_book_id = "sha-256-v1:hash",
            cloud_book_id = "cloud-book",
            cloud_book_file_id = "cloud-file",
            media_type = "ebook",
            local_source_uuid = "local-book",
            staging_path = null,
            size_bytes = 12L,
            bytes_transferred = 12L,
            content_hash = "hash",
            content_hash_algorithm = "sha-256-v1",
            upload_id = "upload-id",
            storage_path = "storage-path",
            tus_upload_url = "https://cloud.example/session",
            tus_expires_at = null,
            rights_attestation = "{}",
            state = state,
            attempt_count = 0L,
            next_attempt_at = null,
            last_error = null,
            created_at = "before",
            updated_at = "before",
        )
    }

    private fun insertCloudFile(cloudBookFileId: String, relativePath: String) {
        database.cloudBookFileStateQueries.upsertCloudBookFileState(
            library_book_id = LIBRARY_BOOK_ID,
            cloud_book_id = "cloud-book",
            cloud_book_file_id = cloudBookFileId,
            media_type = "ebook",
            relative_path = relativePath,
            file_name = "${cloudBookFileId}.epub",
            status = "available",
            size_bytes = 12L,
            content_hash = "hash",
            content_hash_algorithm = "sha-256-v1",
            remote_revision = 1L,
            updated_at = "before",
        )
    }

    private fun updateTransfer(state: String, expectedStates: List<String>) {
        database.cloudFileTransferQueries.updateCloudFileTransferStateIfExpected(
            server_id = "parrot-cloud",
            direction = "upload",
            library_book_id = "sha-256-v1:hash",
            cloud_book_id = "cloud-book",
            cloud_book_file_id = "cloud-file",
            media_type = "ebook",
            local_source_uuid = "local-book",
            staging_path = null,
            size_bytes = 12L,
            bytes_transferred = 12L,
            content_hash = "hash",
            content_hash_algorithm = "sha-256-v1",
            upload_id = "upload-id",
            storage_path = "storage-path",
            tus_upload_url = "https://cloud.example/session",
            tus_expires_at = null,
            rights_attestation = "{}",
            state = state,
            attempt_count = 0L,
            next_attempt_at = null,
            last_error = null,
            created_at = "before",
            updated_at = state,
            transfer_id = TRANSFER_ID,
            state_ = expectedStates,
        )
    }

    private companion object {
        const val TRANSFER_ID = "transfer-1"
        const val LIBRARY_BOOK_ID = "sha-256-v1:hash"
        val ACTIVE_TRANSFER_STATES = listOf("pending", "transferring", "verifying", "finalizing")
    }
}
