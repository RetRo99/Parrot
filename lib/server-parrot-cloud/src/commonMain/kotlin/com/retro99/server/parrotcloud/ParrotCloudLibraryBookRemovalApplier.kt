package com.retro99.server.parrotcloud

import com.retro99.database.api.books.PositionDatabase
import com.retro99.sync.data.LocalBookUuidResolver
import com.retro99.sync.data.LibraryBookSyncApplier
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Applies an explicit server book-removal tombstone to the local mirror. */
@Single
class ParrotCloudLibraryBookRemovalApplier(
    @Provided private val libraryBookSyncApplier: LibraryBookSyncApplier,
    @Provided private val localBookUuidResolver: LocalBookUuidResolver,
    @Provided private val positionDatabase: PositionDatabase,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun apply(payload: JsonElement, revision: Long?) {
        apply(json.encodeToString(payload), revision)
    }

    suspend fun apply(payload: String, revision: Long?) {
        val book = json.decodeFromString<ParrotCloudBookPayload>(payload)
        require(!book.cloudBookId.isNullOrBlank()) {
            "Cloud book-removal tombstones must include a Cloud book ID"
        }
        require(!book.deletedAt.isNullOrBlank()) {
            "Cloud book-removal tombstones must include deleted_at"
        }
        require(revision != null || book.remoteRevision != null) {
            "Cloud book-removal tombstones must include a revision"
        }
        val applied = libraryBookSyncApplier.applyRemote(
            book.toSyncLibraryBookSnapshot(revision),
        )
        if (!applied) return

        val cloudBookId = requireNotNull(book.cloudBookId)
        val localBookUuid = localBookUuidResolver.resolve(
            libraryBookId = book.libraryBookId,
            cloudBookId = cloudBookId,
            fallback = book.libraryBookId,
        )
        positionDatabase.deleteRemotePosition(localBookUuid)
        if (book.libraryBookId != localBookUuid) {
            positionDatabase.deleteRemotePosition(book.libraryBookId)
        }
        if (cloudBookId != localBookUuid && cloudBookId != book.libraryBookId) {
            positionDatabase.deleteRemotePosition(cloudBookId)
        }
    }
}
