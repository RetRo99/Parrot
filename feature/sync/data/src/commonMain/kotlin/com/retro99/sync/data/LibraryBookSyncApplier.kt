package com.retro99.sync.data

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Applies normalized library-book changes without knowing a backend wire
 * format. Payload decoding remains with the destination transport adapter.
 */
@Single
class LibraryBookSyncApplier(
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
) {
    suspend fun applyRemote(snapshot: SyncLibraryBookSnapshot) {
        val existing = snapshot.cloudBookId
            ?.let { cloudBookId -> libraryBooksDatabase.getLibraryBookByCloudBookId(cloudBookId) }
            ?: libraryBooksDatabase.getLibraryBookByContentHash(
                snapshot.contentHashAlgorithm,
                snapshot.contentHash,
            )
        val libraryBookId = existing?.libraryBookId ?: snapshot.libraryBookId

        libraryBooksDatabase.upsertLibraryBook(
            SyncLibraryBookEntity(
                libraryBookId = libraryBookId,
                cloudBookId = snapshot.cloudBookId,
                contentHash = snapshot.contentHash,
                contentHashAlgorithm = snapshot.contentHashAlgorithm,
                title = snapshot.title,
                author = snapshot.author,
                format = snapshot.format,
                remoteRevision = snapshot.remoteRevision,
                metadataJson = snapshot.metadataJson,
            ),
        )
    }

    suspend fun applyAccepted(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
        snapshot: SyncLibraryBookSnapshot,
    ) {
        val cloudBookId = response.cloudBookId ?: snapshot.cloudBookId ?: return
        val existing = libraryBooksDatabase.getLibraryBookById(entry.entityId)
        val revision = response.revision ?: snapshot.remoteRevision
        val entity = if (existing == null) {
            SyncLibraryBookEntity(
                libraryBookId = snapshot.libraryBookId,
                cloudBookId = cloudBookId,
                contentHash = snapshot.contentHash,
                contentHashAlgorithm = snapshot.contentHashAlgorithm,
                title = snapshot.title,
                author = snapshot.author,
                format = snapshot.format,
                remoteRevision = revision,
                metadataJson = snapshot.metadataJson,
            )
        } else {
            SyncLibraryBookEntity(
                libraryBookId = existing.libraryBookId,
                cloudBookId = cloudBookId,
                contentHash = existing.contentHash,
                contentHashAlgorithm = existing.contentHashAlgorithm,
                title = existing.title,
                author = existing.author,
                format = existing.format,
                remoteRevision = revision,
                deletedAt = existing.deletedAt,
                metadataJson = existing.metadataJson,
            )
        }
        libraryBooksDatabase.upsertLibraryBook(entity)
    }
}

data class SyncLibraryBookSnapshot(
    val libraryBookId: String,
    val cloudBookId: String?,
    val contentHash: String,
    val contentHashAlgorithm: String,
    val title: String,
    val author: String?,
    val format: String,
    val remoteRevision: Long?,
    val metadataJson: String?,
)

private data class SyncLibraryBookEntity(
    override val libraryBookId: String,
    override val cloudBookId: String?,
    override val contentHash: String?,
    override val contentHashAlgorithm: String?,
    override val title: String,
    override val author: String?,
    override val format: String,
    override val remoteRevision: Long?,
    override val deletedAt: String? = null,
    override val metadataJson: String? = null,
) : LibraryBookEntity
