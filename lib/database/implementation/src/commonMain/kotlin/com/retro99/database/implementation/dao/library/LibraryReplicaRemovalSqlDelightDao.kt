package com.retro99.database.implementation.dao.library

import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibraryReplicaRemovalDatabase
import com.retro99.database.api.library.LibraryReplicaRemovalIntent
import com.retro99.database.api.library.LibraryReplicaRemovalPhase
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Library_replica_removal_intents
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal class LibraryReplicaRemovalSqlDelightDao(
    private val profileSession: ProfileDatabaseSession,
    private val databaseProvider: () -> AppDatabase,
) : LibraryReplicaRemovalDatabase {
    override suspend fun beginRemoval(
        intent: LibraryReplicaRemovalIntent,
    ): LibraryReplicaRemovalIntent = profileSession.withProfile(intent.profileId.value) {
        withContext(Dispatchers.IO) {
            val database = databaseProvider()
            database.transactionWithResult {
                val sourceKey = LibrarySourceKeyCodec.encode(intent.source.key)
                database.libraryReplicaRemovalQueries.insertLibraryReplicaRemovalIntent(
                    operation_id = intent.operationId,
                    profile_id = intent.profileId.value,
                    group_id = intent.groupId.value,
                    source_key = sourceKey,
                    execution_connection_id = requireNotNull(intent.source.connectionId).value,
                    legacy_library_book_id = intent.source.legacyLibraryBookId?.value,
                    resource_native_id = intent.resource.nativeResourceId,
                    resource_revision_present = if (intent.resource.revision == null) 0L else 1L,
                    resource_revision_value = intent.resource.revision.orEmpty(),
                    asset_id = intent.assetId.value,
                    replica_id = intent.replicaId.value,
                    storage_reference = intent.storageRef.value,
                    requested_at = intent.requestedAt,
                    phase = intent.phase.name,
                )
                val recorded = database.libraryReplicaRemovalQueries
                    .getLibraryReplicaRemovalByOperationId(
                        intent.profileId.value,
                        intent.operationId,
                    ).executeAsOneOrNull()?.toIntent()
                if (recorded != null) {
                    check(recorded.sameTarget(intent)) {
                        "A removal operation ID cannot be reused for another target"
                    }
                    return@transactionWithResult recorded
                }
                val active = database.libraryReplicaRemovalQueries
                    .getActiveLibraryReplicaRemovalForTarget(
                        profile_id = intent.profileId.value,
                        source_key = sourceKey,
                        resource_native_id = intent.resource.nativeResourceId,
                        resource_revision_present =
                            if (intent.resource.revision == null) 0L else 1L,
                        resource_revision_value = intent.resource.revision.orEmpty(),
                        storage_reference = intent.storageRef.value,
                    ).executeAsOneOrNull()?.toIntent()
                checkNotNull(active) { "Could not persist the device replica removal intent" }
                active
            }
        }
    }

    override suspend fun getRemoval(
        profileId: LibraryProfileId,
        operationId: String,
    ): LibraryReplicaRemovalIntent? = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().libraryReplicaRemovalQueries
                .getLibraryReplicaRemovalByOperationId(profileId.value, operationId)
                .executeAsOneOrNull()
                ?.toIntent()
        }
    }

    override suspend fun getPendingRemovals(
        profileId: LibraryProfileId,
    ): List<LibraryReplicaRemovalIntent> = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().libraryReplicaRemovalQueries
                .getPendingLibraryReplicaRemovals(profileId.value)
                .executeAsList()
                .map { row -> row.toIntent() }
        }
    }

    override suspend fun markRemovalEffectApplied(
        profileId: LibraryProfileId,
        operationId: String,
    ) = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().libraryReplicaRemovalQueries
                .markLibraryReplicaRemovalEffectApplied(profileId.value, operationId)
        }
    }

    override suspend fun completeRemoval(
        profileId: LibraryProfileId,
        operationId: String,
    ) = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().libraryReplicaRemovalQueries
                .completeLibraryReplicaRemoval(profileId.value, operationId)
        }
    }

    private fun Library_replica_removal_intents.toIntent(): LibraryReplicaRemovalIntent {
        val profileId = LibraryProfileId(profile_id)
        val sourceKey = LibrarySourceKeyCodec.decode(profileId, source_key)
        val source = SourceBookRef(
            key = sourceKey,
            connectionId = SourceConnectionId(execution_connection_id),
            legacyLibraryBookId = legacy_library_book_id?.let {
                com.retro99.server.api.library.LegacyLibraryBookId(it)
            },
        )
        return LibraryReplicaRemovalIntent(
            operationId = operation_id,
            groupId = LibraryGroupId(group_id),
            source = source,
            resource = SourceResourceRef(
                book = sourceKey,
                nativeResourceId = resource_native_id,
                revision = resource_revision_value.takeIf {
                    resource_revision_present == 1L
                },
            ),
            assetId = MediaAssetId(asset_id),
            replicaId = StorageReplicaId(replica_id),
            storageRef = DeviceStorageRef(storage_reference),
            requestedAt = requested_at,
            phase = LibraryReplicaRemovalPhase.valueOf(phase),
        )
    }

    private fun LibraryReplicaRemovalIntent.sameTarget(
        other: LibraryReplicaRemovalIntent,
    ): Boolean =
        profileId == other.profileId &&
            source.key == other.source.key &&
            resource == other.resource &&
            replicaId == other.replicaId &&
            storageRef == other.storageRef
}
