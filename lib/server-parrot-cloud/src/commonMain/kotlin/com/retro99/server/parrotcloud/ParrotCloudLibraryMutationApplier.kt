package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.data.LibraryMutationApplier
import com.retro99.sync.data.LibraryBookSyncApplier
import com.retro99.sync.data.SyncLibraryBookSnapshot
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Applies Parrot library-mutation responses to shared local state.
 * The shared library-mutation engine owns outbox state transitions and retry decisions.
 */
@Single
class ParrotCloudLibraryMutationApplier(
    @Provided private val libraryBookSyncApplier: LibraryBookSyncApplier,
    private val bookLinkSync: ParrotCloudBookLinkSync,
    private val readerSettingsSync: ParrotCloudReaderSettingsSync,
    private val savedItemSync: ParrotCloudSavedItemSync,
) : LibraryMutationApplier {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override suspend fun onAccepted(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        if (entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS) {
            readerSettingsSync.onAccepted(entry, response)
            return
        }
        // Reading sessions are append-only ledger rows: acceptance needs no
        // local metadata update, the outbox entry is simply acknowledged.
        if (entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_SESSION) return
        if (entry.entityType in BOOK_LINK_ENTITY_TYPES) {
            bookLinkSync.onAccepted(entry, response)
            return
        }
        if (entry.entityType == SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM) {
            savedItemSync.onAccepted(entry, response)
            return
        }
        val payload = json.decodeFromString<ParrotCloudBookPayload>(entry.payload)
        libraryBookSyncApplier.applyAccepted(
            entry = entry,
            response = response,
            snapshot = payload.toSyncLibraryBookSnapshot(response.revision),
        )
    }

    override suspend fun onConflict(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        if (entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS) {
            readerSettingsSync.onConflict(entry, response)
            return
        }
        if (entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READING_SESSION) return
        if (entry.entityType in BOOK_LINK_ENTITY_TYPES) {
            bookLinkSync.onConflict(entry, response)
            return
        }
        if (entry.entityType == SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM) {
            savedItemSync.onConflict(response)
            return
        }
        response.payload?.let { payload ->
            val book = json.decodeFromString<ParrotCloudBookPayload>(payload)
            libraryBookSyncApplier.applyRemote(
                book.toSyncLibraryBookSnapshot(response.revision),
            )
        }
    }

    override fun discardsConflict(entry: SyncOutboxEntry): Boolean =
        entry.entityType == SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK ||
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS ||
            // The newer server version was applied locally; there is nothing left to send.
            entry.entityType == SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM

    override suspend fun onDuplicate(
        entry: SyncOutboxEntry,
        response: SyncMutationResponse,
    ) {
        val payload = response.payload ?: return
        val book = json.decodeFromString<ParrotCloudBookPayload>(payload)
        libraryBookSyncApplier.applyRemote(book.toSyncLibraryBookSnapshot(book.remoteRevision))
    }

    private companion object {
        val BOOK_LINK_ENTITY_TYPES = setOf(
            SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK,
            SyncOutboxEntry.ENTITY_TYPE_BOOK_LINK_DECISION,
        )
    }
}

internal fun ParrotCloudBookPayload.toSyncLibraryBookSnapshot(
    revision: Long?,
): SyncLibraryBookSnapshot {
    return SyncLibraryBookSnapshot(
        libraryBookId = libraryBookId,
        sourceContentHash = sourceContentHash,
        sourceContentHashAlgorithm = sourceContentHashAlgorithm,
        title = title,
        author = author,
        format = format,
        remoteRevision = revision ?: remoteRevision,
        metadataJson = metadataJson,
    )
}
