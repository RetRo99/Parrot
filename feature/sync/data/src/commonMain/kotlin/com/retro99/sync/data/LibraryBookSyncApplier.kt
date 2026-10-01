package com.retro99.sync.data

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock

/**
 * Applies normalized library-book changes without knowing a backend wire
 * format. Payload decoding remains with the destination transport adapter.
 */
@Single
class LibraryBookSyncApplier(
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
) {
    /**
     * Saves a book as the server has it, by its id. Fields only this device knows
     * (cover, description, when it was added and opened) are kept. A new book has no
     * device file until one is downloaded.
     */
    suspend fun applyRemote(snapshot: SyncLibraryBookSnapshot) {
        val existing = libraryBooksDatabase.getLibraryBookById(snapshot.libraryBookId)
        val book = existing?.copy(
            title = snapshot.title,
            author = snapshot.author,
            sourceContentHash = snapshot.sourceContentHash ?: existing.sourceContentHash,
            sourceContentHashAlgorithm = snapshot.sourceContentHashAlgorithm
                ?: existing.sourceContentHashAlgorithm,
            remoteRevision = snapshot.remoteRevision,
            metadataJson = snapshot.metadataJson ?: existing.metadataJson,
        ) ?: LibraryBookEntity(
            libraryBookId = snapshot.libraryBookId,
            title = snapshot.title,
            author = snapshot.author,
            sourceContentHash = snapshot.sourceContentHash,
            sourceContentHashAlgorithm = snapshot.sourceContentHashAlgorithm,
            addedAt = Clock.System.now().toString(),
            remoteRevision = snapshot.remoteRevision,
            metadataJson = snapshot.metadataJson,
        )
        libraryBooksDatabase.upsertLibraryBook(book)
    }

    /** Records the server revision of a book this device pushed. */
    suspend fun applyAccepted(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
        snapshot: SyncLibraryBookSnapshot,
    ) {
        // A book deleted from this device while its upsert was in flight stays deleted.
        val existing = libraryBooksDatabase.getLibraryBookById(entry.entityId) ?: return
        val revision = response.revision ?: snapshot.remoteRevision
        libraryBooksDatabase.upsertLibraryBook(existing.copy(remoteRevision = revision))
    }
}

data class SyncLibraryBookSnapshot(
    val libraryBookId: String,
    val sourceContentHash: String?,
    val sourceContentHashAlgorithm: String?,
    val title: String,
    val author: String?,
    val format: String?,
    val remoteRevision: Long?,
    val metadataJson: String?,
)
