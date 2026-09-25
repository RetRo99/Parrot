package com.retro99.server.parrotcloud

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.CompletableResult
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Queues a Cloud book tombstone after the last Cloud file has been removed. */
class ParrotCloudBookRemovalService(
    private val libraryBooksDatabase: LibraryBooksDatabase,
    private val syncOutboxDatabase: SyncOutboxDatabase,
    private val cloudFilesDatabase: CloudFilesDatabase,
) {
    private val json = Json {
        encodeDefaults = true
    }

    suspend fun deleteCloudBook(
        libraryBookId: String,
        expectedCloudBookId: String? = null,
        expectedSourceRevision: String? = null,
        cloudUserId: String? = null,
    ): CompletableResult {
        return try {
            val book = libraryBooksDatabase.getLibraryBookById(libraryBookId)
                ?: return Err(AppError.NotFoundError("Cloud book not found: $libraryBookId"))
            if (expectedCloudBookId != null && book.cloudBookId != expectedCloudBookId) {
                return Err(AppError.NotFoundError("Cloud book identity changed"))
            }
            if (expectedSourceRevision != null &&
                book.remoteRevision?.toString() != expectedSourceRevision
            ) {
                return Err(AppError.NotFoundError("Cloud book source revision changed"))
            }
            if (book.deletedAt != null) return Ok(Unit)
            val cloudBookId = book.cloudBookId
                ?: return Err(AppError.UnknownError(
                    IllegalStateException("The book has no Cloud identity"),
                ))
            val remoteRevision = book.remoteRevision
                ?: return Err(AppError.UnknownError(
                    IllegalStateException("The Cloud book has not finished syncing"),
                ))
            if (cloudFilesDatabase.getFileStates(libraryBookId).isNotEmpty()) {
                return Err(AppError.UnknownError(
                    IllegalStateException("Remove all Cloud files before removing this book"),
                ))
            }
            val contentHash = book.contentHash?.takeIf { hash -> hash.isNotBlank() } ?: return Err(
                AppError.UnknownError(
                    IllegalStateException("The Cloud book has no content hash"),
                ),
            )
            val contentHashAlgorithm = book.contentHashAlgorithm
                ?: DEFAULT_CONTENT_HASH_ALGORITHM
            if (libraryBookId != "$contentHashAlgorithm:$contentHash") {
                return Err(AppError.NotFoundError("Cloud book content identity changed"))
            }
            val alreadyQueued = cloudUserId?.let { userId ->
                syncOutboxDatabase.getPending(userId).any { mutation ->
                    mutation.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK &&
                        mutation.entityId == libraryBookId &&
                        mutation.operation == SyncOutboxEntry.OPERATION_DELETE &&
                        mutation.state in QUEUED_DELETE_STATES
                }
            } == true
            if (alreadyQueued) return Ok(Unit)
            syncOutboxDatabase.coalesce(
                entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                entityId = libraryBookId,
                entry = SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK,
                    entityId = libraryBookId,
                    operation = SyncOutboxEntry.OPERATION_DELETE,
                    baseRevision = remoteRevision,
                    cloudUserId = cloudUserId,
                    payload = json.encodeToString(
                        ParrotCloudBookPayload(
                            libraryBookId = libraryBookId,
                            cloudBookId = cloudBookId,
                            contentHash = contentHash,
                            contentHashAlgorithm = contentHashAlgorithm,
                            title = book.title,
                            author = book.author,
                            format = book.format,
                            metadataJson = book.metadataJson,
                            remoteRevision = remoteRevision,
                        ),
                    ),
                ),
            )
            Ok(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            Err(AppError.UnknownError(exception))
        }
    }

    private companion object {
        const val DEFAULT_CONTENT_HASH_ALGORITHM = "sha-256-v1"
        val QUEUED_DELETE_STATES = setOf(
            SyncOutboxEntry.STATE_PENDING,
            SyncOutboxEntry.STATE_DISPATCHED,
        )
    }
}
