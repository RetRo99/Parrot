package com.retro99.database.implementation.dao.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.DeviceReplicaRetirementResult
import com.retro99.database.api.library.LibraryBackfillResult
import com.retro99.database.api.library.LibraryEvidenceProvenance
import com.retro99.database.api.library.LibraryEvidenceProvenanceKind
import com.retro99.database.api.library.LibraryEvidenceRecord
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.api.library.RemoteReplicaRetirementResult
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Library_source_resources
import com.retro99.database.implementation.Library_source_snapshots
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.FingerprintScope
import com.retro99.server.api.library.FingerprintVerification
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryTransferId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.RemoteResourceRef
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookCollection
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceBookSnapshot
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourcePresence
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourceSnapshotStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal class LibrarySourceSnapshotsSqlDelightDao(
    private val profileSession: ProfileDatabaseSession,
    private val databaseProvider: () -> AppDatabase,
) : LibrarySourceSnapshotsDatabase {
    override fun observeSnapshotChanges(profileId: LibraryProfileId): Flow<Unit> = flow {
        val query = profileSession.withProfile(profileId.value) {
            databaseProvider().librarySourceSnapshotQueries
                .observeLibrarySourceSnapshotProjectionChanges(profile_id = profileId.value)
        }
        emitAll(
            query.asFlow()
                .mapToList(Dispatchers.IO)
                .map { Unit },
        )
    }

    override suspend fun saveSnapshot(snapshot: SourceBookSnapshot): LibraryGroupId {
        validateSnapshot(snapshot)
        val profileId = snapshot.source.key.profileId
        return profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val key = snapshot.source.key
                    val sourceKey = LibrarySourceKeyCodec.encode(key)
                    val existing = database.librarySourceSnapshotQueries
                        .getLibrarySourceSnapshot(profileId.value, sourceKey)
                        .executeAsOneOrNull()
                    val observedAtEpochMs = snapshot.status.observedAt.toEpochMilliseconds()
                    val observedAtSubmillisecondNs =
                        snapshot.status.observedAt.submillisecondNanoseconds()
                    val isStale = existing != null && (
                        existing.observed_at_epoch_ms > observedAtEpochMs ||
                            (
                                existing.observed_at_epoch_ms == observedAtEpochMs &&
                                    existing.observed_at_submillisecond_ns >
                                    observedAtSubmillisecondNs
                                )
                        )
                    val source = if (isStale) {
                        existing.toSourceBookRef(key)
                    } else {
                        snapshot.source
                    }
                    val groupId = database.ensureGroupForSource(
                        source = source,
                        proposedGroupId = LibraryGroupId(Uuid.random().toString()),
                        createdAt = snapshot.status.observedAt.toString(),
                    )
                    if (isStale) return@transactionWithResult groupId

                    when (snapshot.status.presence) {
                        SourcePresence.Unknown -> database.recordUnknownSnapshot(
                            snapshot,
                            sourceKey,
                            existing != null,
                        )
                        SourcePresence.Removed -> database.recordRemovedSnapshot(
                            snapshot,
                            sourceKey,
                            existing != null,
                        )
                        SourcePresence.Present -> database.recordPresentSnapshot(
                            snapshot,
                            sourceKey,
                            existing != null,
                        )
                    }

                    if (existing == null) {
                        database.replaceSnapshotPeople(snapshot, sourceKey)
                        database.replaceSnapshotSeries(snapshot, sourceKey)
                        database.replaceSnapshotTags(snapshot, sourceKey)
                        database.replaceSnapshotCollections(snapshot, sourceKey)
                        database.replaceSnapshotMediaTypes(snapshot, sourceKey)
                        database.replaceSnapshotResources(snapshot, sourceKey)
                    } else if (snapshot.status.presence == SourcePresence.Present) {
                        if (snapshot.status.isAuthoritative) {
                            val oldResources = database.snapshotResourceRows(profileId, sourceKey)
                            database.markSnapshotResourcesUnavailable(profileId, sourceKey)
                            database.replaceSnapshotPeople(snapshot, sourceKey)
                            database.replaceSnapshotSeries(snapshot, sourceKey)
                            database.replaceSnapshotTags(snapshot, sourceKey)
                            database.replaceSnapshotCollections(snapshot, sourceKey)
                            database.replaceSnapshotMediaTypes(snapshot, sourceKey)
                            database.addSnapshotResources(snapshot, sourceKey)
                            database.retireEvidenceForResourcesNoLongerPresent(
                                profileId = profileId,
                                oldResources = oldResources,
                                currentResources = snapshot.resources.map { resource ->
                                    resource.reference
                                }.toSet(),
                            )
                        } else {
                            database.addSnapshotPeople(snapshot, sourceKey)
                            database.addSnapshotSeries(snapshot, sourceKey)
                            database.addSnapshotTags(snapshot, sourceKey)
                            database.addSnapshotCollections(snapshot, sourceKey)
                            database.addSnapshotMediaTypes(snapshot, sourceKey)
                            database.addSnapshotResources(snapshot, sourceKey)
                        }
                    }

                    when (snapshot.status.presence) {
                        SourcePresence.Unknown -> Unit
                        SourcePresence.Removed -> {
                            database.markSnapshotResourcesUnavailable(
                                snapshot.source.key.profileId,
                                sourceKey,
                            )
                            database.librarySourceSnapshotQueries.retireEvidenceForSourceRemoval(
                                profileId.value,
                                sourceKey,
                                profileId.value,
                                sourceKey,
                                profileId.value,
                                sourceKey,
                            )
                        }
                        SourcePresence.Present -> {
                            if (snapshot.status.isAuthoritative) {
                                database.retireObsoleteSnapshotEvidence(snapshot)
                            }
                            snapshot.identityEvidence.forEach { evidence ->
                                database.recordSnapshotEvidence(snapshot, evidence)
                            }
                        }
                    }
                    groupId
                }
            }
        }
    }

    override suspend fun getSnapshot(source: SourceBookKey): SourceBookSnapshot? =
        profileSession.withProfile(source.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                val sourceKey = LibrarySourceKeyCodec.encode(source)
                database.librarySourceSnapshotQueries.getLibrarySourceSnapshot(
                    source.profileId.value,
                    sourceKey,
                ).executeAsOneOrNull()?.let { row -> database.toSnapshot(row) }
            }
        }

    override suspend fun getSnapshots(profileId: LibraryProfileId): List<SourceBookSnapshot> =
        profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.librarySourceSnapshotQueries.getLibrarySourceSnapshots(profileId.value)
                    .executeAsList()
                    .map { row -> database.toSnapshot(row) }
            }
        }

    override suspend fun retireDeviceReplica(
        source: SourceBookRef,
        resource: SourceResourceRef,
        expectedStorageRef: DeviceStorageRef,
    ): DeviceReplicaRetirementResult {
        require(resource.book == source.key)
        val profileId = source.key.profileId
        return profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val sourceKey = LibrarySourceKeyCodec.encode(source.key)
                    val row = database.libraryEvidenceQueries.getLibrarySourceResource(
                        profileId.value,
                        sourceKey,
                        resource.nativeResourceId,
                        if (resource.revision == null) 0L else 1L,
                        resource.revision.orEmpty(),
                    ).executeAsOneOrNull()
                    if (row == null) {
                        return@transactionWithResult DeviceReplicaRetirementResult.NotFound
                    }
                    val current = database.librarySourceSnapshotQueries
                        .getLibrarySnapshotResources(profileId.value, sourceKey)
                        .executeAsList()
                        .firstOrNull { snapshotResource ->
                            snapshotResource.resource_id == row.resource_id
                        } ?: return@transactionWithResult DeviceReplicaRetirementResult.NotFound
                    if (
                        current.availability ==
                            SourceResourceAvailability.Unavailable.name &&
                        current.local_storage_reference == null
                    ) {
                        return@transactionWithResult deviceReplicaRetirementResult(
                            baseResult = DeviceReplicaRetirementResult.AlreadyRemoved,
                            profileId = profileId.value,
                            storageRef = expectedStorageRef,
                            database = database,
                        )
                    }
                    if (current.local_storage_reference != expectedStorageRef.value) {
                        return@transactionWithResult DeviceReplicaRetirementResult.ReferenceChanged
                    }
                    database.librarySourceSnapshotQueries.retireLibrarySnapshotDeviceReplica(
                        profile_id = profileId.value,
                        source_key = sourceKey,
                        resource_id = row.resource_id,
                        local_storage_reference = expectedStorageRef.value,
                    )
                    deviceReplicaRetirementResult(
                        baseResult = DeviceReplicaRetirementResult.Removed,
                        profileId = profileId.value,
                        storageRef = expectedStorageRef,
                        database = database,
                    )
                }
            }
        }
    }

    private fun deviceReplicaRetirementResult(
        baseResult: DeviceReplicaRetirementResult,
        profileId: String,
        storageRef: DeviceStorageRef,
        database: AppDatabase,
    ): DeviceReplicaRetirementResult {
        val hasOtherReferences = database.librarySourceSnapshotQueries
            .countLibrarySnapshotDeviceReplicasForStorageReference(
                profileId,
                storageRef.value,
            ).executeAsOne() > 0L
        return when {
            !hasOtherReferences -> baseResult
            baseResult == DeviceReplicaRetirementResult.Removed ->
                DeviceReplicaRetirementResult.RemovedWithSharedReferences
            else -> DeviceReplicaRetirementResult.AlreadyRemovedWithSharedReferences
        }
    }

    override suspend fun retireRemoteReplica(
        resource: SourceResourceRef,
        expectedRemoteRef: RemoteResourceRef,
    ): RemoteReplicaRetirementResult {
        val profileId = resource.book.profileId
        return profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val sourceKey = LibrarySourceKeyCodec.encode(resource.book)
                    val snapshot = database.librarySourceSnapshotQueries
                        .getLibrarySourceSnapshot(profileId.value, sourceKey)
                        .executeAsOneOrNull()
                        ?: return@transactionWithResult RemoteReplicaRetirementResult.NotFound
                    val sourceResource = database.libraryEvidenceQueries.getLibrarySourceResource(
                        profileId.value,
                        sourceKey,
                        resource.nativeResourceId,
                        if (resource.revision == null) 0L else 1L,
                        resource.revision.orEmpty(),
                    ).executeAsOneOrNull()
                        ?: return@transactionWithResult RemoteReplicaRetirementResult.NotFound
                    val current = database.librarySourceSnapshotQueries
                        .getLibrarySnapshotResources(profileId.value, sourceKey)
                        .executeAsList()
                        .firstOrNull { row -> row.resource_id == sourceResource.resource_id }
                        ?: return@transactionWithResult RemoteReplicaRetirementResult.NotFound

                    when (current.remote_resource_reference) {
                        expectedRemoteRef.value -> {
                            database.librarySourceSnapshotQueries
                                .retireLibrarySnapshotRemoteReplica(
                                profile_id = profileId.value,
                                source_key = sourceKey,
                                resource_id = sourceResource.resource_id,
                                remote_resource_reference = expectedRemoteRef.value,
                            )
                            RemoteReplicaRetirementResult.Removed
                        }
                        null -> if (
                            current.local_storage_reference != null ||
                            current.availability ==
                                SourceResourceAvailability.Unavailable.name
                        ) {
                            RemoteReplicaRetirementResult.AlreadyRemoved
                        } else {
                            RemoteReplicaRetirementResult.ReferenceChanged
                        }
                        else -> RemoteReplicaRetirementResult.ReferenceChanged
                    }
                }
            }
        }
    }

    override suspend fun backfillGroups(
        profileId: LibraryProfileId,
        migrationId: String,
        startedAt: String,
        completedAt: String,
    ): LibraryBackfillResult {
        require(migrationId.isNotBlank())
        require(startedAt.isNotBlank() && completedAt.isNotBlank())
        return profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val queries = database.librarySourceSnapshotQueries
                    queries.insertLibraryMigrationState(profileId.value, migrationId, startedAt)
                    val state = checkNotNull(
                        queries.getLibraryMigrationState(profileId.value, migrationId)
                            .executeAsOneOrNull(),
                    )
                    if (state.completed_at != null) {
                        return@transactionWithResult LibraryBackfillResult(0, true)
                    }
                    val rows = queries.getLibrarySourceSnapshotsAfter(
                        profileId.value,
                        state.last_source_key.orEmpty(),
                        BACKFILL_BATCH_SIZE + 1L,
                    ).executeAsList()
                    val batch = rows.take(BACKFILL_BATCH_SIZE)
                    batch.forEach { row ->
                        val snapshot = database.toSnapshot(row)
                        database.ensureGroupForSource(
                            source = snapshot.source,
                            proposedGroupId = LibraryGroupId(Uuid.random().toString()),
                            createdAt = snapshot.status.observedAt.toString(),
                        )
                        queries.advanceLibraryMigrationState(
                            row.source_key,
                            profileId.value,
                            migrationId,
                        )
                    }
                    val isComplete = rows.size <= BACKFILL_BATCH_SIZE
                    if (isComplete) {
                        queries.completeLibraryMigrationState(
                            completedAt,
                            profileId.value,
                            migrationId,
                        )
                    }
                    LibraryBackfillResult(batch.size, isComplete)
                }
            }
        }
    }
}

private const val BACKFILL_BATCH_SIZE = 100

private fun validateSnapshot(snapshot: SourceBookSnapshot) {
    val sourceKey = snapshot.source.key
    val resourceRefs = snapshot.resources.map { resource -> resource.reference }
    require(resourceRefs.all { reference -> reference.book == sourceKey }) {
        "Snapshot resources must belong to its source book"
    }
    require(resourceRefs.distinct().size == resourceRefs.size) {
        "Snapshot resource references must be unique"
    }
    snapshot.identityEvidence.forEach { evidence ->
        val belongsToSnapshot = when (evidence) {
            is SourceIdentityEvidence.BookFingerprint -> evidence.book == sourceKey
            is SourceIdentityEvidence.FileFingerprint -> evidence.resource.book == sourceKey
            is SourceIdentityEvidence.AdapterCertified -> evidence.resource.book == sourceKey
            is SourceIdentityEvidence.CompletedTransfer ->
                evidence.source.book == sourceKey || evidence.destination.book == sourceKey
        }
        require(belongsToSnapshot) { "Snapshot evidence must reference its source book" }
    }
}

private fun AppDatabase.ensureGroupForSource(
    source: SourceBookRef,
    proposedGroupId: LibraryGroupId,
    createdAt: String,
): LibraryGroupId {
    val key = source.key
    val identity = key.toStorageIdentity()
    val queries = libraryGroupQueries
    val existing = queries.getLibraryGroupMembership(
        profile_id = key.profileId.value,
        adapter_id = key.adapterId.value,
        identity_kind = identity.kind,
        backend_id = identity.backendId,
        account_id = identity.accountId,
        unresolved_connection_id = identity.unresolvedConnectionId,
        native_book_id = key.nativeBookId.value,
    ).executeAsOneOrNull()
    if (existing != null) {
        queries.updateLibraryGroupMembershipSource(
            execution_connection_id = source.connectionId?.value,
            new_legacy_library_book_id = source.legacyLibraryBookId?.value,
            profile_id = key.profileId.value,
            adapter_id = key.adapterId.value,
            identity_kind = identity.kind,
            backend_id = identity.backendId,
            account_id = identity.accountId,
            unresolved_connection_id = identity.unresolvedConnectionId,
            native_book_id = key.nativeBookId.value,
        )
        return LibraryGroupId(existing.group_id)
    }
    check(queries.getLibraryGroup(key.profileId.value, proposedGroupId.value)
        .executeAsOneOrNull() == null) { "Proposed group ID already belongs to another group" }
    queries.insertLibraryGroup(key.profileId.value, proposedGroupId.value, createdAt)
    queries.insertLibraryGroupMembership(
        profile_id = key.profileId.value,
        adapter_id = key.adapterId.value,
        identity_kind = identity.kind,
        backend_id = identity.backendId,
        account_id = identity.accountId,
        unresolved_connection_id = identity.unresolvedConnectionId,
        native_book_id = key.nativeBookId.value,
        group_id = proposedGroupId.value,
        execution_connection_id = source.connectionId?.value,
        legacy_library_book_id = source.legacyLibraryBookId?.value,
    )
    return proposedGroupId
}

private data class SnapshotStorageIdentity(
    val kind: String,
    val backendId: String,
    val accountId: String,
    val unresolvedConnectionId: String,
)

private fun SourceBookKey.toStorageIdentity(): SnapshotStorageIdentity = when (
    val identity = accountIdentity
) {
    is SourceAccountIdentity.Portable -> SnapshotStorageIdentity(
        kind = "portable",
        backendId = identity.backendId,
        accountId = identity.accountId,
        unresolvedConnectionId = "",
    )
    is SourceAccountIdentity.Unresolved -> SnapshotStorageIdentity(
        kind = "unresolved",
        backendId = "",
        accountId = "",
        unresolvedConnectionId = identity.connectionId.value,
    )
}

private fun AppDatabase.recordUnknownSnapshot(
    snapshot: SourceBookSnapshot,
    sourceKey: String,
    exists: Boolean,
) {
    val metadata = snapshot.metadata
    val profileId = snapshot.source.key.profileId.value
    val observedAt = snapshot.status.observedAt.toEpochMilliseconds()
    val observedAtSubmillisecondNs = snapshot.status.observedAt.submillisecondNanoseconds()
    val connectionId = snapshot.source.connectionId?.value
    val legacyId = snapshot.source.legacyLibraryBookId?.value
    val revision = snapshot.status.revision
    if (exists) {
        librarySourceSnapshotQueries.updateUnknownSourceSnapshot(
            connectionId,
            legacyId,
            observedAt,
            observedAtSubmillisecondNs,
            revision,
            profileId,
            sourceKey,
        )
    } else {
        librarySourceSnapshotQueries.recordUnknownSourceSnapshot(
            profile_id = profileId,
            source_key = sourceKey,
            execution_connection_id = connectionId,
            legacy_library_book_id = legacyId,
            title = metadata.title,
            description = metadata.description,
            cover_reference = metadata.coverReference,
            publication_date = metadata.publicationDate,
            observed_at_epoch_ms = observedAt,
            observed_at_submillisecond_ns = observedAtSubmillisecondNs,
            revision = revision,
        )
    }
}

private fun AppDatabase.recordRemovedSnapshot(
    snapshot: SourceBookSnapshot,
    sourceKey: String,
    exists: Boolean,
) {
    val metadata = snapshot.metadata
    val profileId = snapshot.source.key.profileId.value
    val observedAt = snapshot.status.observedAt.toEpochMilliseconds()
    val observedAtSubmillisecondNs = snapshot.status.observedAt.submillisecondNanoseconds()
    val connectionId = snapshot.source.connectionId?.value
    val legacyId = snapshot.source.legacyLibraryBookId?.value
    val revision = snapshot.status.revision
    if (exists) {
        librarySourceSnapshotQueries.updateRemovedSourceSnapshot(
            connectionId,
            legacyId,
            observedAt,
            observedAtSubmillisecondNs,
            revision,
            profileId,
            sourceKey,
        )
    } else {
        librarySourceSnapshotQueries.recordRemovedSourceSnapshot(
            profile_id = profileId,
            source_key = sourceKey,
            execution_connection_id = connectionId,
            legacy_library_book_id = legacyId,
            title = metadata.title,
            description = metadata.description,
            cover_reference = metadata.coverReference,
            publication_date = metadata.publicationDate,
            observed_at_epoch_ms = observedAt,
            observed_at_submillisecond_ns = observedAtSubmillisecondNs,
            revision = revision,
        )
    }
}

private fun AppDatabase.recordPresentSnapshot(
    snapshot: SourceBookSnapshot,
    sourceKey: String,
    exists: Boolean,
) {
    val metadata = snapshot.metadata
    val profileId = snapshot.source.key.profileId.value
    val connectionId = snapshot.source.connectionId?.value
    val legacyId = snapshot.source.legacyLibraryBookId?.value
    val title = metadata.title
    val description = metadata.description
    val coverReference = metadata.coverReference
    val publicationDate = metadata.publicationDate
    val observedAt = snapshot.status.observedAt.toEpochMilliseconds()
    val observedAtSubmillisecondNs = snapshot.status.observedAt.submillisecondNanoseconds()
    val isAuthoritative = if (snapshot.status.isAuthoritative) 1L else 0L
    val revision = snapshot.status.revision
    if (exists) {
        librarySourceSnapshotQueries.updateLibrarySourceSnapshot(
            connectionId,
            legacyId,
            title,
            description,
            coverReference,
            publicationDate,
            observedAt,
            observedAtSubmillisecondNs,
            SourcePresence.Present.name,
            isAuthoritative,
            revision,
            profileId,
            sourceKey,
        )
    } else {
        librarySourceSnapshotQueries.insertLibrarySourceSnapshot(
            profile_id = profileId,
            source_key = sourceKey,
            execution_connection_id = connectionId,
            legacy_library_book_id = legacyId,
            title = title,
            description = description,
            cover_reference = coverReference,
            publication_date = publicationDate,
            observed_at_epoch_ms = observedAt,
            observed_at_submillisecond_ns = observedAtSubmillisecondNs,
            presence = SourcePresence.Present.name,
            is_authoritative = isAuthoritative,
            revision = revision,
        )
    }
}

private fun AppDatabase.replaceSnapshotPeople(snapshot: SourceBookSnapshot, sourceKey: String) {
    librarySourceSnapshotQueries.deleteLibrarySnapshotPeople(
        snapshot.source.key.profileId.value,
        sourceKey,
    )
    addSnapshotPeople(snapshot, sourceKey)
}

private fun AppDatabase.addSnapshotPeople(snapshot: SourceBookSnapshot, sourceKey: String) {
    val queries = librarySourceSnapshotQueries
    val profileId = snapshot.source.key.profileId.value
    snapshot.metadata.authors.forEachIndexed { index, name ->
        queries.insertLibrarySnapshotPerson(profileId, sourceKey, "author", index.toLong(), name)
    }
    snapshot.metadata.narrators.forEachIndexed { index, name ->
        queries.insertLibrarySnapshotPerson(profileId, sourceKey, "narrator", index.toLong(), name)
    }
}

private fun AppDatabase.replaceSnapshotSeries(snapshot: SourceBookSnapshot, sourceKey: String) {
    librarySourceSnapshotQueries.deleteLibrarySnapshotSeries(
        snapshot.source.key.profileId.value,
        sourceKey,
    )
    addSnapshotSeries(snapshot, sourceKey)
}

private fun AppDatabase.addSnapshotSeries(snapshot: SourceBookSnapshot, sourceKey: String) {
    val profileId = snapshot.source.key.profileId.value
    snapshot.metadata.series.forEachIndexed { index, series ->
        librarySourceSnapshotQueries.insertLibrarySnapshotSeries(
            profile_id = profileId,
            source_key = sourceKey,
            position = index.toLong(),
            native_id = series.nativeId,
            name = series.name,
            sequence_value = series.sequence?.toDouble(),
        )
    }
}

private fun AppDatabase.replaceSnapshotTags(snapshot: SourceBookSnapshot, sourceKey: String) {
    librarySourceSnapshotQueries.deleteLibrarySnapshotTags(
        snapshot.source.key.profileId.value,
        sourceKey,
    )
    addSnapshotTags(snapshot, sourceKey)
}

private fun AppDatabase.addSnapshotTags(snapshot: SourceBookSnapshot, sourceKey: String) {
    val profileId = snapshot.source.key.profileId.value
    snapshot.metadata.tags.forEachIndexed { index, tag ->
        librarySourceSnapshotQueries.insertLibrarySnapshotTag(
            profileId,
            sourceKey,
            index.toLong(),
            tag,
        )
    }
}

private fun AppDatabase.replaceSnapshotCollections(
    snapshot: SourceBookSnapshot,
    sourceKey: String,
) {
    librarySourceSnapshotQueries.deleteLibrarySnapshotCollections(
        snapshot.source.key.profileId.value,
        sourceKey,
    )
    addSnapshotCollections(snapshot, sourceKey)
}

private fun AppDatabase.addSnapshotCollections(
    snapshot: SourceBookSnapshot,
    sourceKey: String,
) {
    val profileId = snapshot.source.key.profileId.value
    snapshot.metadata.collections.forEachIndexed { index, collection ->
        librarySourceSnapshotQueries.insertLibrarySnapshotCollection(
            profile_id = profileId,
            source_key = sourceKey,
            position = index.toLong(),
            native_id = collection.nativeId,
            name = collection.name,
            created_at = collection.createdAt,
            updated_at = collection.updatedAt,
        )
    }
}

private fun AppDatabase.replaceSnapshotMediaTypes(
    snapshot: SourceBookSnapshot,
    sourceKey: String,
) {
    librarySourceSnapshotQueries.deleteLibrarySnapshotMediaTypes(
        snapshot.source.key.profileId.value,
        sourceKey,
    )
    addSnapshotMediaTypes(snapshot, sourceKey)
}

private fun AppDatabase.addSnapshotMediaTypes(snapshot: SourceBookSnapshot, sourceKey: String) {
    val profileId = snapshot.source.key.profileId.value
    snapshot.metadata.mediaTypes.forEachIndexed { index, mediaType ->
        librarySourceSnapshotQueries.insertLibrarySnapshotMediaType(
            profileId,
            sourceKey,
            index.toLong(),
            mediaType,
        )
    }
}

private fun AppDatabase.snapshotResourceRows(
    profileId: LibraryProfileId,
    sourceKey: String,
): List<Library_source_resources> = libraryEvidenceQueries
    .getLibrarySourceResourcesForBook(profileId.value, sourceKey)
    .executeAsList()

private fun AppDatabase.replaceSnapshotResources(snapshot: SourceBookSnapshot, sourceKey: String) {
    librarySourceSnapshotQueries.deleteLibrarySnapshotResources(
        snapshot.source.key.profileId.value,
        sourceKey,
    )
    addSnapshotResources(snapshot, sourceKey)
}

private fun AppDatabase.markSnapshotResourcesUnavailable(
    profileId: LibraryProfileId,
    sourceKey: String,
) {
    librarySourceSnapshotQueries.markLibrarySnapshotResourcesUnavailable(
        profileId.value,
        sourceKey,
    )
}

private fun AppDatabase.addSnapshotResources(snapshot: SourceBookSnapshot, sourceKey: String) {
    val profileId = snapshot.source.key.profileId.value
    snapshot.resources.forEach { resource ->
        val resourceId = ensureLibrarySourceResource(resource.reference)
        librarySourceSnapshotQueries.insertLibrarySnapshotResource(
            profile_id = profileId,
            source_key = sourceKey,
            resource_id = resourceId,
            media_type = resource.mediaType,
            format = resource.format,
            size_bytes = resource.sizeBytes,
            availability = resource.availability.name,
            local_storage_reference = resource.localStorageReference?.value,
            remote_resource_reference = resource.remoteResourceReference?.value,
        )
    }
}

private fun AppDatabase.retireEvidenceForResourcesNoLongerPresent(
    profileId: LibraryProfileId,
    oldResources: List<Library_source_resources>,
    currentResources: Set<SourceResourceRef>,
) {
    oldResources.forEach { row ->
        if (row.toRef() !in currentResources) {
            librarySourceSnapshotQueries.retireEvidenceForResource(
                profileId.value,
                row.resource_id,
                row.resource_id,
            )
        }
    }
}

private fun AppDatabase.recordSnapshotEvidence(
    snapshot: SourceBookSnapshot,
    evidence: SourceIdentityEvidence,
) {
    val profileId = snapshot.source.key.profileId
    val sourceResource = evidence.sourceResourceForSnapshotOrNull()
    val destination = (evidence as? SourceIdentityEvidence.CompletedTransfer)?.destination
    val sourceId = sourceResource?.let(::ensureLibrarySourceResource)
    val destinationId = destination?.let { resource -> ensureLibrarySourceResource(resource) }
    val recordId = evidence.snapshotEvidenceId(snapshot.status.observedAt.toString())
    val fingerprintAlgorithm = when (evidence) {
        is SourceIdentityEvidence.BookFingerprint -> evidence.algorithm
        is SourceIdentityEvidence.FileFingerprint -> evidence.algorithm
        else -> null
    }
    val fingerprintHash = when (evidence) {
        is SourceIdentityEvidence.BookFingerprint -> evidence.hash
        is SourceIdentityEvidence.FileFingerprint -> evidence.hash
        else -> null
    }
    val fingerprintScope = when (evidence) {
        is SourceIdentityEvidence.BookFingerprint -> evidence.scope
        is SourceIdentityEvidence.FileFingerprint -> evidence.scope
        else -> null
    }
    val fingerprintVerification = when (evidence) {
        is SourceIdentityEvidence.BookFingerprint -> evidence.verification
        is SourceIdentityEvidence.FileFingerprint -> evidence.verification
        else -> null
    }
    val transfer = evidence as? SourceIdentityEvidence.CompletedTransfer
    val certified = evidence as? SourceIdentityEvidence.AdapterCertified
    val expectedRecord = LibraryEvidenceRecord(
        evidenceId = recordId,
        evidence = evidence,
        provenance = LibraryEvidenceProvenance(
            LibraryEvidenceProvenanceKind.Adapter,
            "snapshot:${LibrarySourceKeyCodec.encode(snapshot.source.key)}",
        ),
        observedAt = snapshot.status.observedAt.toString(),
    )
    val existing = libraryEvidenceQueries.getLibraryIdentityEvidence(
        profileId.value,
        recordId,
    ).executeAsOneOrNull()
    if (existing != null) {
        check(toLibraryEvidenceRecord(existing) == expectedRecord) {
            "Snapshot evidence ID already belongs to a different association"
        }
        return
    }
    libraryEvidenceQueries.insertLibraryIdentityEvidence(
        profile_id = profileId.value,
        evidence_id = recordId,
        kind = when (evidence) {
            is SourceIdentityEvidence.BookFingerprint -> "book_fingerprint"
            is SourceIdentityEvidence.FileFingerprint -> "fingerprint"
            is SourceIdentityEvidence.CompletedTransfer -> "transfer"
            is SourceIdentityEvidence.AdapterCertified -> "certified"
        },
        source_resource_id = sourceId,
        source_book_key = (evidence as? SourceIdentityEvidence.BookFingerprint)
            ?.book
            ?.let(LibrarySourceKeyCodec::encode),
        destination_resource_id = destinationId,
        transfer_id = transfer?.transferId?.value,
        algorithm = fingerprintAlgorithm,
        content_hash = fingerprintHash,
        fingerprint_scope = fingerprintScope?.name,
        fingerprint_verification = fingerprintVerification?.name,
        certified_namespace = certified?.namespace,
        certified_identity = certified?.identity,
        certified_kind = certified?.kind?.name,
        provenance_kind = expectedRecord.provenance.kind.name,
        provenance_id = expectedRecord.provenance.referenceId,
        observed_at = snapshot.status.observedAt.toString(),
    )
}

private fun AppDatabase.retireObsoleteSnapshotEvidence(snapshot: SourceBookSnapshot) {
    val profileId = snapshot.source.key.profileId.value
    val provenanceId = "snapshot:${LibrarySourceKeyCodec.encode(snapshot.source.key)}"
    val activeIds = librarySourceSnapshotQueries.getActiveSnapshotEvidenceIds(
        profileId,
        provenanceId,
    ).executeAsList()
    val currentIds = snapshot.identityEvidence.map { evidence ->
        evidence.snapshotEvidenceId(snapshot.status.observedAt.toString())
    }.toSet()
    activeIds.filter { evidenceId -> evidenceId !in currentIds }
        .forEach { evidenceId ->
            librarySourceSnapshotQueries.retireSnapshotEvidenceId(profileId, evidenceId)
        }
}

private fun AppDatabase.toSnapshot(row: Library_source_snapshots): SourceBookSnapshot {
    val profileId = LibraryProfileId(row.profile_id)
    val key = LibrarySourceKeyCodec.decode(profileId, row.source_key)
    val source = row.toSourceBookRef(key)
    val people = librarySourceSnapshotQueries.getLibrarySnapshotPeople(
        profileId.value,
        row.source_key,
    ).executeAsList()
    val series = librarySourceSnapshotQueries.getLibrarySnapshotSeries(
        profileId.value,
        row.source_key,
    ).executeAsList()
    val tags = librarySourceSnapshotQueries.getLibrarySnapshotTags(
        profileId.value,
        row.source_key,
    ).executeAsList()
    val collections = librarySourceSnapshotQueries.getLibrarySnapshotCollections(
        profileId.value,
        row.source_key,
    ).executeAsList()
    val mediaTypes = librarySourceSnapshotQueries.getLibrarySnapshotMediaTypes(
        profileId.value,
        row.source_key,
    ).executeAsList()
    val resources = librarySourceSnapshotQueries.getLibrarySnapshotResources(
        profileId.value,
        row.source_key,
    ).executeAsList().map { resource ->
        SourceMediaResource(
            reference = SourceResourceRef(
                book = LibrarySourceKeyCodec.decode(profileId, resource.source_key),
                nativeResourceId = resource.native_resource_id,
                revision = if (resource.revision_present == 0L) null else resource.revision_value,
            ),
            mediaType = resource.media_type,
            format = resource.format,
            sizeBytes = resource.size_bytes,
            availability = SourceResourceAvailability.valueOf(resource.availability),
            localStorageReference = resource.local_storage_reference?.let { value ->
                DeviceStorageRef(value)
            },
            remoteResourceReference = resource.remote_resource_reference?.let { value ->
                RemoteResourceRef(value)
            },
        )
    }
    val evidence = libraryEvidenceQueries.getActiveLibraryIdentityEvidence(profileId.value)
        .executeAsList()
        .map { evidenceRow -> toLibraryEvidenceRecord(evidenceRow) }
        .filter { record -> record.evidence.referencesBook(key) }
        .map { record -> record.evidence }
    return SourceBookSnapshot(
        source = source,
        metadata = com.retro99.server.api.library.SourceBookMetadata(
            title = row.title,
            description = row.description,
            coverReference = row.cover_reference,
            authors = people.filter { person -> person.role == "author" }
                .map { person -> person.name },
            narrators = people.filter { person -> person.role == "narrator" }
                .map { person -> person.name },
            series = series.map { value ->
                com.retro99.server.api.library.SourceBookSeries(
                    nativeId = value.native_id,
                    name = value.name,
                    sequence = value.sequence_value?.toFloat(),
                )
            },
            tags = tags.map { value -> value.tag },
            collections = collections.map { value ->
                SourceBookCollection(
                    nativeId = value.native_id,
                    name = value.name,
                    createdAt = value.created_at,
                    updatedAt = value.updated_at,
                )
            },
            mediaTypes = mediaTypes.map { value -> value.media_type },
            publicationDate = row.publication_date,
        ),
        resources = resources,
        identityEvidence = evidence,
        status = SourceSnapshotStatus(
            observedAt = Instant.fromEpochMilliseconds(row.observed_at_epoch_ms) +
                row.observed_at_submillisecond_ns.nanoseconds,
            presence = SourcePresence.valueOf(row.presence),
            isAuthoritative = row.is_authoritative == 1L,
            revision = row.revision,
        ),
    )
}

private fun Instant.submillisecondNanoseconds(): Long =
    (nanosecondsOfSecond % NANOS_PER_MILLISECOND).toLong()

private const val NANOS_PER_MILLISECOND = 1_000_000

private fun Library_source_snapshots.toSourceBookRef(key: SourceBookKey): SourceBookRef =
    SourceBookRef(
        key = key,
        connectionId = execution_connection_id?.let { value -> SourceConnectionId(value) },
        legacyLibraryBookId = legacy_library_book_id?.let { value ->
            LegacyLibraryBookId(value)
        },
    )

private fun Library_source_resources.toRef(): SourceResourceRef = SourceResourceRef(
    book = LibrarySourceKeyCodec.decode(LibraryProfileId(profile_id), source_key),
    nativeResourceId = native_resource_id,
    revision = if (revision_present == 0L) null else revision_value,
)

private fun SourceIdentityEvidence.sourceResourceForSnapshotOrNull(): SourceResourceRef? =
    when (this) {
        is SourceIdentityEvidence.BookFingerprint -> null
        is SourceIdentityEvidence.FileFingerprint -> resource
        is SourceIdentityEvidence.CompletedTransfer -> source
        is SourceIdentityEvidence.AdapterCertified -> resource
    }

private fun SourceIdentityEvidence.referencesBook(key: SourceBookKey): Boolean = when (this) {
    is SourceIdentityEvidence.BookFingerprint -> book == key
    is SourceIdentityEvidence.FileFingerprint -> resource.book == key
    is SourceIdentityEvidence.AdapterCertified -> resource.book == key
    is SourceIdentityEvidence.CompletedTransfer -> source.book == key || destination.book == key
}

private fun SourceIdentityEvidence.snapshotEvidenceId(observedAt: String): String {
    val parts = when (this) {
        is SourceIdentityEvidence.BookFingerprint -> listOf(
            "book-fingerprint",
            LibrarySourceKeyCodec.encode(book),
            algorithm,
            hash,
            scope.name,
            verification.name,
        )
        is SourceIdentityEvidence.FileFingerprint -> listOf(
            "fingerprint",
            resource.identityValue(),
            algorithm,
            hash,
            scope.name,
            verification.name,
        )
        is SourceIdentityEvidence.CompletedTransfer -> listOf(
            "transfer",
            transferId.value,
            source.identityValue(),
            destination.identityValue(),
        )
        is SourceIdentityEvidence.AdapterCertified -> listOf(
            "certified",
            resource.identityValue(),
            namespace,
            identity,
            kind.name,
        )
    }
    return "snapshot-evidence:${encodeEvidenceParts(parts + observedAt)}"
}

private fun SourceResourceRef.identityValue(): String = encodeEvidenceParts(
    listOf(
        LibrarySourceKeyCodec.encode(book),
        nativeResourceId,
        revision,
    ),
)

private fun encodeEvidenceParts(parts: List<String?>): String = buildString {
    append("v1|")
    parts.forEach { part ->
        if (part == null) {
            append("-1:")
        } else {
            append(part.length)
            append(':')
            append(part)
        }
    }
}
