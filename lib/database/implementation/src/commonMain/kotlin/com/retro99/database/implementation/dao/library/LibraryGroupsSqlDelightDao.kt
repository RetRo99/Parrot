package com.retro99.database.implementation.dao.library

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibraryAutomaticGroupMerge
import com.retro99.database.api.library.LibraryGroupMembershipRecord
import com.retro99.database.api.library.LibraryGroupRecord
import com.retro99.database.api.library.LibraryGroupsDatabase
import com.retro99.database.api.library.LibraryManualGroupMerge
import com.retro99.database.api.library.LibraryManualGroupSplit
import com.retro99.database.api.library.LibraryManualSeparationRecord
import com.retro99.database.api.library.LibraryMediaPreferenceChange
import com.retro99.database.api.library.LibraryMediaPreferenceChangeResult
import com.retro99.database.api.library.LibrarySynchronizedGroupDecision
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Library_group_memberships
import com.retro99.database.implementation.dao.sync.enqueue
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class LibraryGroupsSqlDelightDao(
    private val profileSession: ProfileDatabaseSession,
    private val databaseProvider: () -> AppDatabase,
) : LibraryGroupsDatabase {
    override fun observeProjectionChanges(profileId: LibraryProfileId): Flow<Unit> = flow {
        val query = profileSession.withProfile(profileId.value) {
            databaseProvider().libraryGroupQueries.observeLibraryGroupProjectionChanges(
                profile_id = profileId.value,
            )
        }
        emitAll(
            query.asFlow()
                .mapToList(Dispatchers.IO)
                .map { Unit },
        )
    }

    override suspend fun ensureGroupForSource(
        source: SourceBookRef,
        proposedGroupId: LibraryGroupId,
        createdAt: String,
    ): LibraryGroupId {
        require(createdAt.isNotBlank())
        val key = source.key
        val identity = key.toStorageIdentity()
        return profileSession.withProfile(key.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val existing = database.libraryGroupQueries.getLibraryGroupMembership(
                        profile_id = key.profileId.value,
                        adapter_id = key.adapterId.value,
                        identity_kind = identity.kind,
                        backend_id = identity.backendId,
                        account_id = identity.accountId,
                        unresolved_connection_id = identity.unresolvedConnectionId,
                        native_book_id = key.nativeBookId.value,
                    ).executeAsOneOrNull()
                    if (existing != null) {
                        database.libraryGroupQueries.updateLibraryGroupMembershipSource(
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
                        LibraryGroupId(existing.group_id)
                    } else {
                        check(database.libraryGroupQueries.getLibraryGroup(
                            key.profileId.value,
                            proposedGroupId.value,
                        ).executeAsOneOrNull() == null) {
                            "Proposed group ID already belongs to another group"
                        }
                        database.libraryGroupQueries.insertLibraryGroup(
                            profile_id = key.profileId.value,
                            group_id = proposedGroupId.value,
                            created_at = createdAt,
                        )
                        database.libraryGroupQueries.insertLibraryGroupMembership(
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
                        proposedGroupId
                    }
                }
            }
        }
    }

    override suspend fun getGroup(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupRecord? = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().libraryGroupQueries.getLibraryGroup(
                profileId.value,
                groupId.value,
            ).executeAsOneOrNull()?.let { row ->
                LibraryGroupRecord(
                    profileId = LibraryProfileId(row.profile_id),
                    groupId = LibraryGroupId(row.group_id),
                    createdAt = row.created_at,
                    preferredMetadataSourceKey = databaseProvider().libraryGroupDecisionQueries
                        .getLibraryGroupMetadataPreference(profileId.value, groupId.value)
                        .executeAsOneOrNull()
                        ?.source_key
                        ?.let { sourceKey ->
                            LibrarySourceKeyCodec.decode(profileId, sourceKey)
                        },
                    preferredMediaSourceKeys = databaseProvider().libraryGroupDecisionQueries
                        .getLibraryGroupMediaPreferences(profileId.value, groupId.value)
                        .executeAsList()
                        .associate { preference ->
                            preference.media_type to LibrarySourceKeyCodec.decode(
                                profileId,
                                preference.source_key,
                            )
                        },
                )
            }
        }
    }

    override suspend fun getMembership(key: SourceBookKey): LibraryGroupMembershipRecord? {
        val identity = key.toStorageIdentity()
        return profileSession.withProfile(key.profileId.value) {
            withContext(Dispatchers.IO) {
                databaseProvider().libraryGroupQueries.getLibraryGroupMembership(
                    profile_id = key.profileId.value,
                    adapter_id = key.adapterId.value,
                    identity_kind = identity.kind,
                    backend_id = identity.backendId,
                    account_id = identity.accountId,
                    unresolved_connection_id = identity.unresolvedConnectionId,
                    native_book_id = key.nativeBookId.value,
                ).executeAsOneOrNull()?.toRecord()
            }
        }
    }

    override suspend fun getMemberships(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): List<LibraryGroupMembershipRecord> = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().libraryGroupQueries.getLibraryGroupMemberships(
                profileId.value,
                groupId.value,
            ).executeAsList().map { row -> row.toRecord() }
        }
    }

    override suspend fun getAllMemberships(
        profileId: LibraryProfileId,
    ): List<LibraryGroupMembershipRecord> {
        return profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val rows = databaseProvider().libraryGroupQueries
                    .getAllLibraryGroupMemberships(profileId.value)
                    .executeAsList()
                rows.map { row -> row.toRecord() }
            }
        }
    }

    override suspend fun applyAutomaticMerge(merge: LibraryAutomaticGroupMerge): Boolean {
        require(merge.sourceKeys.size >= 2)
        require(merge.sourceKeys.all { key -> key.profileId == merge.profileId })
        require(merge.evidenceIds.isNotEmpty())
        require(merge.appliedAt.isNotBlank())
        val targetGroups = merge.retiredGroupIds + merge.survivorGroupId
        require(targetGroups.size >= 2)
        require(merge.survivorGroupId !in merge.retiredGroupIds)
        return profileSession.withProfile(merge.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val members = merge.sourceKeys.map { key ->
                        val identity = key.toStorageIdentity()
                        database.libraryGroupQueries.getLibraryGroupMembership(
                            profile_id = key.profileId.value,
                            adapter_id = key.adapterId.value,
                            identity_kind = identity.kind,
                            backend_id = identity.backendId,
                            account_id = identity.accountId,
                            unresolved_connection_id = identity.unresolvedConnectionId,
                            native_book_id = key.nativeBookId.value,
                        ).executeAsOneOrNull()
                    }
                    check(members.all { member -> member != null }) {
                        "Automatic merge references a missing source membership"
                    }
                    val memberRows = members.filterNotNull()
                    val currentGroups = memberRows
                        .map { row -> LibraryGroupId(row.group_id) }
                        .toSet()
                    if (currentGroups == setOf(merge.survivorGroupId)) {
                        val redirectsResolve = merge.retiredGroupIds.all { aliasId ->
                            database.libraryGroupDecisionQueries.getLibraryGroupAlias(
                                merge.profileId.value,
                                aliasId.value,
                            ).executeAsOneOrNull()?.target_group_id == merge.survivorGroupId.value
                        }
                        check(redirectsResolve) {
                            "Automatic merge replay has incomplete redirects"
                        }
                        return@transactionWithResult false
                    }
                    check(currentGroups == targetGroups) {
                        "Automatic merge membership changed; recompute the grouping plan"
                    }
                    check(
                        currentGroups.minBy { groupId -> groupId.value } == merge.survivorGroupId,
                    ) { "Automatic merge survivor must be the smallest existing group ID" }
                    val currentMemberKeys = currentGroups.flatMap { groupId ->
                        database.libraryGroupQueries.getLibraryGroupMemberships(
                            merge.profileId.value,
                            groupId.value,
                        ).executeAsList().map { row -> row.toRecord().source.key }
                    }.toSet()
                    check(currentMemberKeys == merge.sourceKeys) {
                        "Automatic merge must name every member in its existing groups"
                    }
                    val currentGroupBySource = memberRows.associate { row ->
                        row.toRecord().source.key to row.group_id
                    }
                    database.libraryGroupDecisionQueries.getLibraryManualSeparations(
                        merge.profileId.value,
                    ).executeAsList().forEach { separation ->
                        val first = LibrarySourceKeyCodec.decode(
                            merge.profileId,
                            separation.first_source_key,
                        )
                        val second = LibrarySourceKeyCodec.decode(
                            merge.profileId,
                            separation.second_source_key,
                        )
                        val firstGroup = currentGroupBySource[first]
                        val secondGroup = currentGroupBySource[second]
                        check(
                            firstGroup == null || secondGroup == null || firstGroup == secondGroup,
                        ) { "Automatic merge conflicts with a manual separation" }
                    }
                    check(database.libraryGroupQueries.getLibraryGroup(
                        merge.profileId.value,
                        merge.survivorGroupId.value,
                    ).executeAsOneOrNull() != null) { "Automatic merge survivor is missing" }
                    check(database.resolveGroupId(
                        merge.profileId,
                        merge.survivorGroupId,
                    ) == merge.survivorGroupId) { "Automatic merge survivor is already an alias" }

                    val decisionId = merge.evidenceIds.sorted().joinToString(
                        prefix = "automatic:",
                        separator = "",
                    ) { evidenceId -> "${evidenceId.length}:$evidenceId" }
                    memberRows.forEach { row ->
                        database.libraryGroupQueries.moveLibraryGroupMembership(
                            group_id = merge.survivorGroupId.value,
                            membership_origin = LibraryMembershipOrigin.Automatic.name,
                            revision = 0L,
                            decision_id = decisionId,
                            profile_id = row.profile_id,
                            adapter_id = row.adapter_id,
                            identity_kind = row.identity_kind,
                            backend_id = row.backend_id,
                            account_id = row.account_id,
                            unresolved_connection_id = row.unresolved_connection_id,
                            native_book_id = row.native_book_id,
                        )
                    }
                    merge.retiredGroupIds.forEach { aliasId ->
                        val existingAlias = database.libraryGroupDecisionQueries
                            .getLibraryGroupAlias(merge.profileId.value, aliasId.value)
                            .executeAsOneOrNull()
                        if (existingAlias == null) {
                            database.libraryGroupDecisionQueries.insertLibraryGroupAlias(
                                profile_id = merge.profileId.value,
                                alias_group_id = aliasId.value,
                                target_group_id = merge.survivorGroupId.value,
                                created_at = merge.appliedAt,
                            )
                        } else {
                            check(existingAlias.target_group_id == merge.survivorGroupId.value) {
                                "A retired group already redirects elsewhere"
                            }
                        }
                    }
                    true
                }
            }
        }
    }

    override suspend fun applyManualMerge(merge: LibraryManualGroupMerge) {
        require(merge.members.size >= 2) { "A manual merge needs at least two members" }
        require(
            merge.members.map { member -> member.sourceKey }.distinct().size == merge.members.size,
        ) {
            "A manual merge cannot name a source more than once"
        }
        require(merge.members.all { member -> member.sourceKey.profileId == merge.profileId }) {
            "A manual merge cannot cross profiles"
        }
        require(
            merge.preferredMetadataSourceKey == null ||
                merge.members.any { member ->
                    member.sourceKey == merge.preferredMetadataSourceKey
                },
        ) { "Preferred metadata must come from a selected merge member" }
        require(merge.decisionId.isNotBlank() && merge.appliedAt.isNotBlank())
        validateOutboxEntry(merge.outboxEntry, merge.decisionId)

        profileSession.withProfile(merge.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transaction {
                    check(database.libraryGroupQueries.getLibraryGroup(
                        merge.profileId.value,
                        merge.newGroupId.value,
                    ).executeAsOneOrNull() == null) {
                        "Manual merge target group already exists"
                    }

                    val memberRows = merge.members.map { member ->
                        val key = member.sourceKey
                        val identity = key.toStorageIdentity()
                        val row = database.libraryGroupQueries.getLibraryGroupMembership(
                            profile_id = key.profileId.value,
                            adapter_id = key.adapterId.value,
                            identity_kind = identity.kind,
                            backend_id = identity.backendId,
                            account_id = identity.accountId,
                            unresolved_connection_id = identity.unresolvedConnectionId,
                            native_book_id = key.nativeBookId.value,
                        ).executeAsOneOrNull()
                        check(row != null) { "Manual merge references a missing source" }
                        check(row.group_id == member.expectedGroupId.value) {
                            "Manual merge selection is stale; reload the group"
                        }
                        check(database.resolveGroupId(
                            merge.profileId,
                            member.expectedGroupId,
                        ) == member.expectedGroupId) {
                            "Manual merge selection references a retired group"
                        }
                        row
                    }

                    val selectedKeys = memberRows.map { row ->
                        LibrarySourceKeyCodec.encode(row.toRecord().source.key)
                    }.toSet()
                    val currentGroupIds = memberRows.map { row -> row.group_id }.toSet()
                    val originalRowsByGroup = currentGroupIds.associateWith { groupId ->
                        database.libraryGroupQueries.getLibraryGroupMemberships(
                            merge.profileId.value,
                            groupId,
                        ).executeAsList()
                    }
                    val remainingRowsByGroup = originalRowsByGroup.mapValues { (_, rows) ->
                        rows.filter { row ->
                            LibrarySourceKeyCodec.encode(row.toRecord().source.key) !in selectedKeys
                        }
                    }

                    database.libraryGroupQueries.insertLibraryGroup(
                        profile_id = merge.profileId.value,
                        group_id = merge.newGroupId.value,
                        created_at = merge.appliedAt,
                    )
                    memberRows.forEach { row ->
                        database.libraryGroupQueries.moveLibraryGroupMembership(
                            group_id = merge.newGroupId.value,
                            membership_origin = LibraryMembershipOrigin.Manual.name,
                            revision = row.revision,
                            decision_id = merge.decisionId,
                            profile_id = row.profile_id,
                            adapter_id = row.adapter_id,
                            identity_kind = row.identity_kind,
                            backend_id = row.backend_id,
                            account_id = row.account_id,
                            unresolved_connection_id = row.unresolved_connection_id,
                            native_book_id = row.native_book_id,
                        )
                    }

                    val newlySeparatedPairs = currentGroupIds.flatMap { groupId ->
                        val movedFromGroup = memberRows.filter { row -> row.group_id == groupId }
                        val remainingFromGroup = remainingRowsByGroup.getValue(groupId)
                        movedFromGroup.flatMap { movedRow ->
                            remainingFromGroup.map { remainingRow ->
                                movedRow.toRecord().source.key to remainingRow.toRecord().source.key
                            }
                        }
                    }
                    insertManualSeparations(
                        database = database,
                        profileId = merge.profileId,
                        pairs = newlySeparatedPairs,
                        decisionId = merge.decisionId,
                        createdAt = merge.appliedAt,
                    )

                    val activeSeparations = database.libraryGroupDecisionQueries
                        .getLibraryManualSeparations(merge.profileId.value)
                        .executeAsList()
                    activeSeparations.filter { separation ->
                        separation.first_source_key in selectedKeys &&
                            separation.second_source_key in selectedKeys
                    }.forEach { separation ->
                        database.libraryGroupDecisionQueries.overrideLibraryManualSeparation(
                            overridden_by_decision_id = merge.decisionId,
                            profile_id = merge.profileId.value,
                            first_source_key = separation.first_source_key,
                            second_source_key = separation.second_source_key,
                        )
                    }

                    remainingRowsByGroup.filterValues { rows -> rows.isEmpty() }.keys.forEach {
                        retiredGroupId ->
                        check(database.libraryGroupDecisionQueries.getLibraryGroupAlias(
                            merge.profileId.value,
                            retiredGroupId,
                        ).executeAsOneOrNull() == null) {
                            "Manual merge cannot redirect an already retired group"
                        }
                        database.libraryGroupDecisionQueries.insertLibraryGroupAlias(
                            profile_id = merge.profileId.value,
                            alias_group_id = retiredGroupId,
                            target_group_id = merge.newGroupId.value,
                            created_at = merge.appliedAt,
                        )
                    }

                    merge.preferredMetadataSourceKey?.let { sourceKey ->
                        database.libraryGroupDecisionQueries.upsertLibraryGroupMetadataPreference(
                            profile_id = merge.profileId.value,
                            group_id = merge.newGroupId.value,
                            source_key = LibrarySourceKeyCodec.encode(sourceKey),
                            decision_id = merge.decisionId,
                            server_revision = 0L,
                        )
                    }
                    merge.outboxEntry?.let { entry ->
                        database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
                            profile_id = merge.profileId.value,
                            decision_id = merge.decisionId,
                            server_revision = 0L,
                            payload = entry.payload,
                            created_at = merge.appliedAt,
                        )
                        database.syncOutboxQueries.enqueue(entry)
                    }
                }
            }
        }
    }

    override suspend fun applyManualSplit(split: LibraryManualGroupSplit) {
        require(split.movedSourceKeys.isNotEmpty()) { "A split needs at least one member" }
        require(split.movedSourceKeys.distinct().size == split.movedSourceKeys.size) {
            "A split cannot name a source more than once"
        }
        require(split.movedSourceKeys.all { key -> key.profileId == split.profileId }) {
            "A split cannot cross profiles"
        }
        require(split.retainedSourceKeys.all { key -> key.profileId == split.profileId }) {
            "A split cannot cross profiles"
        }
        require(split.movedSourceKeys.none { key -> key in split.retainedSourceKeys }) {
            "A split member cannot be on both sides"
        }
        require(split.sourceGroupId != split.newGroupId)
        require(split.decisionId.isNotBlank() && split.appliedAt.isNotBlank())
        validateOutboxEntry(split.outboxEntry, split.decisionId)

        profileSession.withProfile(split.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transaction {
                    check(database.resolveGroupId(
                        split.profileId,
                        split.sourceGroupId,
                    ) == split.sourceGroupId) {
                        "Split selection references a missing or retired group"
                    }
                    check(database.libraryGroupQueries.getLibraryGroup(
                        split.profileId.value,
                        split.newGroupId.value,
                    ).executeAsOneOrNull() == null) {
                        "Manual split target group already exists"
                    }

                    val sourceRows = database.libraryGroupQueries.getLibraryGroupMemberships(
                        split.profileId.value,
                        split.sourceGroupId.value,
                    ).executeAsList()
                    val movedKeys = split.movedSourceKeys.toSet()
                    val movedRows = sourceRows.filter { row ->
                        row.toRecord().source.key in movedKeys
                    }
                    check(movedRows.size == movedKeys.size) {
                        "Split selection is stale or contains a nonmember"
                    }
                    val remainingRows = sourceRows.filterNot { row ->
                        row.toRecord().source.key in movedKeys
                    }
                    if (split.retainedSourceKeys.isNotEmpty()) {
                        check(
                            remainingRows.map { row -> row.toRecord().source.key }.toSet() ==
                                split.retainedSourceKeys.toSet(),
                        ) { "Split selection changed; reload the group" }
                    }
                    check(remainingRows.isNotEmpty()) {
                        "A split must leave at least one member in the original group"
                    }

                    database.libraryGroupQueries.insertLibraryGroup(
                        profile_id = split.profileId.value,
                        group_id = split.newGroupId.value,
                        created_at = split.appliedAt,
                    )
                    movedRows.forEach { row ->
                        database.libraryGroupQueries.moveLibraryGroupMembership(
                            group_id = split.newGroupId.value,
                            membership_origin = LibraryMembershipOrigin.Manual.name,
                            revision = row.revision,
                            decision_id = split.decisionId,
                            profile_id = row.profile_id,
                            adapter_id = row.adapter_id,
                            identity_kind = row.identity_kind,
                            backend_id = row.backend_id,
                            account_id = row.account_id,
                            unresolved_connection_id = row.unresolved_connection_id,
                            native_book_id = row.native_book_id,
                        )
                    }
                    val separationPairs = movedRows.flatMap { movedRow ->
                        remainingRows.map { remainingRow ->
                            movedRow.toRecord().source.key to remainingRow.toRecord().source.key
                        }
                    }
                    insertManualSeparations(
                        database = database,
                        profileId = split.profileId,
                        pairs = separationPairs,
                        decisionId = split.decisionId,
                        createdAt = split.appliedAt,
                    )
                    split.outboxEntry?.let { entry ->
                        database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
                            profile_id = split.profileId.value,
                            decision_id = split.decisionId,
                            server_revision = 0L,
                            payload = entry.payload,
                            created_at = split.appliedAt,
                        )
                        database.syncOutboxQueries.enqueue(entry)
                    }
                }
            }
        }
    }

    override suspend fun applyMediaPreference(
        change: LibraryMediaPreferenceChange,
    ): LibraryMediaPreferenceChangeResult {
        require(change.profileId == change.memberSourceKey.profileId) {
            "A media preference cannot cross profiles"
        }
        require(change.mediaType.isNotBlank())
        require(change.decisionId.isNotBlank() && change.appliedAt.isNotBlank())
        validateOutboxEntry(change.outboxEntry, change.decisionId)

        return profileSession.withProfile(change.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val identity = change.memberSourceKey.toStorageIdentity()
                    val membership = database.libraryGroupQueries.getLibraryGroupMembership(
                        profile_id = change.profileId.value,
                        adapter_id = change.memberSourceKey.adapterId.value,
                        identity_kind = identity.kind,
                        backend_id = identity.backendId,
                        account_id = identity.accountId,
                        unresolved_connection_id = identity.unresolvedConnectionId,
                        native_book_id = change.memberSourceKey.nativeBookId.value,
                    ).executeAsOneOrNull()
                    if (membership?.group_id != change.activeGroupId.value) {
                        val staleMembership =
                            LibraryMediaPreferenceChangeResult.StaleMembership
                        return@transactionWithResult staleMembership
                    }

                    val encodedSourceKey = LibrarySourceKeyCodec.encode(change.memberSourceKey)
                    val existingPreference = database.libraryGroupDecisionQueries
                        .getLibraryGroupMediaPreferences(
                            change.profileId.value,
                            change.activeGroupId.value,
                        ).executeAsList()
                        .firstOrNull { preference -> preference.media_type == change.mediaType }
                    if (existingPreference?.source_key == encodedSourceKey) {
                        val alreadyPreferred =
                            LibraryMediaPreferenceChangeResult.AlreadyPreferred
                        return@transactionWithResult alreadyPreferred
                    }

                    database.libraryGroupDecisionQueries.upsertLibraryGroupMediaPreference(
                        profile_id = change.profileId.value,
                        group_id = change.activeGroupId.value,
                        media_type = change.mediaType,
                        source_key = encodedSourceKey,
                        decision_id = change.decisionId,
                        server_revision = 0L,
                    )
                    database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
                        profile_id = change.profileId.value,
                        decision_id = change.decisionId,
                        server_revision = 0L,
                        payload = change.outboxEntry.payload,
                        created_at = change.appliedAt,
                    )
                    database.syncOutboxQueries.enqueue(change.outboxEntry)
                    LibraryMediaPreferenceChangeResult.Applied
                }
            }
        }
    }

    override suspend fun applySynchronizedDecision(
        decision: LibrarySynchronizedGroupDecision,
    ): Boolean {
        require(decision.decisionId.isNotBlank() && decision.payload.isNotBlank())
        require(decision.revision > 0L)
        require(decision.appliedAt.isNotBlank())
        require(decision.operation in setOf("merge", "split", "preferences"))
        val allMembers = decision.members + decision.retainedMembers
        require(allMembers.distinct().size == allMembers.size)
        require(allMembers.all { key ->
            key.profileId == decision.profileId &&
                key.accountIdentity is SourceAccountIdentity.Portable
        }) { "Synchronized decisions accept only portable members in the active profile" }
        require(
            decision.preferredMetadataSourceKey == null ||
                decision.preferredMetadataSourceKey in allMembers,
        ) { "Preferred metadata must name a decision member" }
        require(decision.preferredMediaSourceKeys.keys.all { mediaType ->
            mediaType.isNotBlank()
        }) { "Preferred media types cannot be blank" }
        require(decision.preferredMediaSourceKeys.values.all { key ->
            key in decision.members
        }) { "Preferred media sources must name selected decision members" }

        return profileSession.withProfile(decision.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val existing = database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                        decision.profileId.value,
                        decision.decisionId,
                    ).executeAsOneOrNull()
                    if (existing?.feed_applied == 1L) {
                        return@transactionWithResult false
                    }
                    check(existing == null || existing.server_revision == 0L ||
                        existing.server_revision == decision.revision
                    ) { "A decision cannot be applied at two different revisions" }

                    val highestAppliedRevision = database.libraryGroupDecisionQueries
                        .getHighestAppliedLibraryGroupDecisionRevision(decision.profileId.value)
                        .executeAsOne()
                        .MAX
                    if (
                        highestAppliedRevision != null &&
                        decision.revision <= highestAppliedRevision
                    ) {
                        if (existing == null) {
                            database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
                                profile_id = decision.profileId.value,
                                decision_id = decision.decisionId,
                                server_revision = decision.revision,
                                payload = decision.payload,
                                created_at = decision.appliedAt,
                            )
                        } else if (existing.server_revision == 0L) {
                            updateAcceptedDecisionRevision(database, decision)
                        }
                        database.libraryGroupDecisionQueries.markLibraryGroupDecisionFeedApplied(
                            decision.profileId.value,
                            decision.decisionId,
                        )
                        return@transactionWithResult false
                    }

                    if (existing?.server_revision == 0L) {
                        updateAcceptedDecisionRevision(database, decision)
                    }

                    when (decision.operation) {
                        "merge" -> applyRemoteMerge(database, decision)
                        "split" -> applyRemoteSplit(database, decision)
                        "preferences" -> applyRemotePreferences(database, decision)
                    }

                    if (existing == null) {
                        database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
                            profile_id = decision.profileId.value,
                            decision_id = decision.decisionId,
                            server_revision = decision.revision,
                            payload = decision.payload,
                            created_at = decision.appliedAt,
                        )
                    }
                    database.libraryGroupDecisionQueries.markLibraryGroupDecisionFeedApplied(
                        decision.profileId.value,
                        decision.decisionId,
                    )
                    true
                }
            }
        }
    }

    override suspend fun recordAcceptedDecision(
        profileId: LibraryProfileId,
        decisionId: String,
        revision: Long,
        payload: String,
    ) {
        require(decisionId.isNotBlank() && revision > 0L && payload.isNotBlank())
        profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transaction {
                    val existing = database.libraryGroupDecisionQueries.getLibraryGroupDecision(
                        profileId.value,
                        decisionId,
                    ).executeAsOneOrNull()
                    if (existing == null) {
                        database.libraryGroupDecisionQueries.insertLibraryGroupDecision(
                            profile_id = profileId.value,
                            decision_id = decisionId,
                            server_revision = revision,
                            payload = payload,
                            created_at = Clock.System.now().toString(),
                        )
                    } else if (existing.server_revision == 0L) {
                        database.libraryGroupDecisionQueries.updateLibraryGroupDecisionRevision(
                            server_revision = revision,
                            payload = payload,
                            profile_id = profileId.value,
                            decision_id = decisionId,
                        )
                        database.libraryGroupDecisionQueries.updateMembershipDecisionRevision(
                            revision = revision,
                            profile_id = profileId.value,
                            decision_id = decisionId,
                        )
                        database.libraryGroupDecisionQueries.updateMetadataPreferenceRevision(
                            server_revision = revision,
                            profile_id = profileId.value,
                            decision_id = decisionId,
                        )
                        database.libraryGroupDecisionQueries.updateMediaPreferenceRevision(
                            server_revision = revision,
                            profile_id = profileId.value,
                            decision_id = decisionId,
                        )
                    } else {
                        check(existing.server_revision == revision) {
                            "A decision cannot be acknowledged at two different revisions"
                        }
                    }
                }
            }
        }
    }

    override suspend fun addGroupAlias(
        profileId: LibraryProfileId,
        aliasId: LibraryGroupId,
        targetId: LibraryGroupId,
        createdAt: String,
    ) {
        require(aliasId != targetId)
        require(createdAt.isNotBlank())
        profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transaction {
                    val existing = database.libraryGroupDecisionQueries.getLibraryGroupAlias(
                        profileId.value,
                        aliasId.value,
                    ).executeAsOneOrNull()
                    if (existing != null) {
                        check(existing.target_group_id == targetId.value) {
                            "A group alias cannot be redirected twice"
                        }
                    } else {
                        check(database.libraryGroupQueries.getLibraryGroup(
                            profileId.value,
                            aliasId.value,
                        ).executeAsOneOrNull() != null) {
                            "Alias group does not exist"
                        }
                        val target = database.resolveGroupId(profileId, targetId)
                        check(target != null && target != aliasId) {
                            "Alias target is missing or would create a cycle"
                        }
                        database.libraryGroupDecisionQueries.insertLibraryGroupAlias(
                            profile_id = profileId.value,
                            alias_group_id = aliasId.value,
                            target_group_id = targetId.value,
                            created_at = createdAt,
                        )
                    }
                }
            }
        }
    }

    override suspend fun resolveGroupId(
        profileId: LibraryProfileId,
        groupId: LibraryGroupId,
    ): LibraryGroupId? = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().resolveGroupId(profileId, groupId)
        }
    }

    override suspend fun addManualSeparation(
        first: SourceBookKey,
        second: SourceBookKey,
        decisionId: String,
        createdAt: String,
    ): Boolean {
        require(first.profileId == second.profileId) { "Separation cannot cross profiles" }
        require(first != second) { "A source cannot be separated from itself" }
        require(decisionId.isNotBlank() && createdAt.isNotBlank())
        val firstKey = LibrarySourceKeyCodec.encode(first)
        val secondKey = LibrarySourceKeyCodec.encode(second)
        val ordered = if (firstKey < secondKey) firstKey to secondKey else secondKey to firstKey
        return profileSession.withProfile(first.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    val existing = database.libraryGroupDecisionQueries.getLibraryManualSeparation(
                        first.profileId.value,
                        ordered.first,
                        ordered.second,
                    ).executeAsOneOrNull()
                    when {
                        existing == null -> {
                            database.libraryGroupDecisionQueries.insertLibraryManualSeparation(
                                profile_id = first.profileId.value,
                                first_source_key = ordered.first,
                                second_source_key = ordered.second,
                                decision_id = decisionId,
                                created_at = createdAt,
                            )
                            true
                        }

                        existing.overridden_by_decision_id != null -> {
                            database.libraryGroupDecisionQueries.reactivateLibraryManualSeparation(
                                decision_id = decisionId,
                                created_at = createdAt,
                                profile_id = first.profileId.value,
                                first_source_key = ordered.first,
                                second_source_key = ordered.second,
                            )
                            true
                        }

                        else -> false
                    }
                }
            }
        }
    }

    override suspend fun getManualSeparations(
        profileId: LibraryProfileId,
    ): List<LibraryManualSeparationRecord> = profileSession.withProfile(profileId.value) {
        withContext(Dispatchers.IO) {
            databaseProvider().libraryGroupDecisionQueries.getLibraryManualSeparations(
                profileId.value,
            ).executeAsList().map { row ->
                LibraryManualSeparationRecord(
                    first = LibrarySourceKeyCodec.decode(profileId, row.first_source_key),
                    second = LibrarySourceKeyCodec.decode(profileId, row.second_source_key),
                    decisionId = row.decision_id,
                    createdAt = row.created_at,
                )
            }
        }
    }
}

private fun updateAcceptedDecisionRevision(
    database: AppDatabase,
    decision: LibrarySynchronizedGroupDecision,
) {
    database.libraryGroupDecisionQueries.updateLibraryGroupDecisionRevision(
        server_revision = decision.revision,
        payload = decision.payload,
        profile_id = decision.profileId.value,
        decision_id = decision.decisionId,
    )
    database.libraryGroupDecisionQueries.updateMembershipDecisionRevision(
        revision = decision.revision,
        profile_id = decision.profileId.value,
        decision_id = decision.decisionId,
    )
    database.libraryGroupDecisionQueries.updateMetadataPreferenceRevision(
        server_revision = decision.revision,
        profile_id = decision.profileId.value,
        decision_id = decision.decisionId,
    )
    database.libraryGroupDecisionQueries.updateMediaPreferenceRevision(
        server_revision = decision.revision,
        profile_id = decision.profileId.value,
        decision_id = decision.decisionId,
    )
}

private fun applyRemoteMerge(
    database: AppDatabase,
    decision: LibrarySynchronizedGroupDecision,
) {
    require(decision.members.size >= 2 && decision.retainedMembers.isEmpty())
    val profileId = decision.profileId
    val targetGroupId = ensureActiveRemoteGroup(
        database = database,
        profileId = profileId,
        groupId = decision.targetGroupId,
        createdAt = decision.appliedAt,
        reactivateRetiredGroup = true,
    )

    val selectedKeys = decision.members.toSet()
    val selectedKeysEncoded = selectedKeys.map(LibrarySourceKeyCodec::encode).toSet()
    val existingRows = decision.members.mapNotNull { key ->
        findMembership(database, key)
    }
    val originalGroupIds = existingRows.map { row -> row.group_id }.toSet()
    val originalRowsByGroup = originalGroupIds.associateWith { groupId ->
        database.libraryGroupQueries.getLibraryGroupMemberships(
            profileId.value,
            groupId,
        ).executeAsList()
    }
    val remainingRowsByGroup = originalRowsByGroup.mapValues { (_, rows) ->
        rows.filter { row ->
            LibrarySourceKeyCodec.encode(row.toRecord().source.key) !in selectedKeysEncoded
        }
    }

    decision.members.forEach { key ->
        moveOrInsertRemoteMember(
            database = database,
            profileId = profileId,
            key = key,
            targetGroupId = targetGroupId,
            decisionId = decision.decisionId,
            revision = decision.revision,
        )
    }

    val separations = decision.members.flatMap { selectedKey ->
        originalRowsByGroup.values.flatten()
            .filter { row ->
                LibrarySourceKeyCodec.encode(row.toRecord().source.key) !in selectedKeysEncoded
            }
            .map { remainingRow -> selectedKey to remainingRow.toRecord().source.key }
    }
    insertManualSeparations(
        database = database,
        profileId = profileId,
        pairs = separations,
        decisionId = decision.decisionId,
        createdAt = decision.appliedAt,
    )

    val activeSeparations = database.libraryGroupDecisionQueries
        .getLibraryManualSeparations(profileId.value)
        .executeAsList()
    activeSeparations.filter { separation ->
        separation.first_source_key in selectedKeysEncoded &&
            separation.second_source_key in selectedKeysEncoded
    }.forEach { separation ->
        database.libraryGroupDecisionQueries.overrideLibraryManualSeparation(
            overridden_by_decision_id = decision.decisionId,
            profile_id = profileId.value,
            first_source_key = separation.first_source_key,
            second_source_key = separation.second_source_key,
        )
    }

    remainingRowsByGroup.filterValues { rows -> rows.isEmpty() }.keys.forEach { retiredGroupId ->
        addRemoteGroupAlias(
            database = database,
            profileId = profileId,
            aliasId = LibraryGroupId(retiredGroupId),
            targetId = targetGroupId,
            createdAt = decision.appliedAt,
        )
    }
    applyRemotePreference(database, decision.copy(targetGroupId = targetGroupId))
}

private fun applyRemoteSplit(
    database: AppDatabase,
    decision: LibrarySynchronizedGroupDecision,
) {
    require(decision.members.isNotEmpty() && decision.retainedMembers.isNotEmpty())
    val retainedGroupId = requireNotNull(decision.retainedGroupId) {
        "A synchronized split requires its retained group ID"
    }
    check(retainedGroupId != decision.targetGroupId)
    val profileId = decision.profileId
    val targetGroupId = ensureActiveRemoteGroup(
        database = database,
        profileId = profileId,
        groupId = decision.targetGroupId,
        createdAt = decision.appliedAt,
        reactivateRetiredGroup = true,
    )
    val activeRetainedGroupId = ensureActiveRemoteGroup(
        database = database,
        profileId = profileId,
        groupId = retainedGroupId,
        createdAt = decision.appliedAt,
        reactivateRetiredGroup = true,
    )
    check(targetGroupId != activeRetainedGroupId) {
        "A synchronized split cannot resolve both groups to the same target"
    }

    val allKeys = decision.members + decision.retainedMembers
    val originalRows = allKeys.mapNotNull { key -> findMembership(database, key) }
    val originalGroupIds = originalRows.map { row -> row.group_id }.toSet()
    decision.members.forEach { key ->
        moveOrInsertRemoteMember(
            database = database,
            profileId = profileId,
            key = key,
            targetGroupId = targetGroupId,
            decisionId = decision.decisionId,
            revision = decision.revision,
        )
    }
    decision.retainedMembers.forEach { key ->
        moveOrInsertRemoteMember(
            database = database,
            profileId = profileId,
            key = key,
            targetGroupId = activeRetainedGroupId,
            decisionId = decision.decisionId,
            revision = decision.revision,
        )
    }
    insertManualSeparations(
        database = database,
        profileId = profileId,
        pairs = decision.members.flatMap { movedKey ->
            decision.retainedMembers.map { retainedKey -> movedKey to retainedKey }
        },
        decisionId = decision.decisionId,
        createdAt = decision.appliedAt,
    )

    originalGroupIds.filter { groupId ->
        groupId != targetGroupId.value && groupId != activeRetainedGroupId.value &&
            database.libraryGroupQueries.getLibraryGroupMemberships(
                profileId.value,
                groupId,
            ).executeAsList().isEmpty()
    }.forEach { retiredGroupId ->
        addRemoteGroupAlias(
            database = database,
            profileId = profileId,
            aliasId = LibraryGroupId(retiredGroupId),
            targetId = activeRetainedGroupId,
            createdAt = decision.appliedAt,
        )
    }
    applyRemotePreference(database, decision.copy(targetGroupId = targetGroupId))
}

private fun applyRemotePreferences(
    database: AppDatabase,
    decision: LibrarySynchronizedGroupDecision,
) {
    require(decision.members.isNotEmpty())
    require(
        decision.preferredMetadataSourceKey != null ||
            decision.preferredMediaSourceKeys.isNotEmpty(),
    ) { "A synchronized preference decision requires at least one preferred source" }
    val targetGroupId = ensureActiveRemoteGroup(
        database = database,
        profileId = decision.profileId,
        groupId = decision.targetGroupId,
        createdAt = decision.appliedAt,
    )
    applyRemotePreference(database, decision.copy(targetGroupId = targetGroupId))
}

private fun applyRemotePreference(
    database: AppDatabase,
    decision: LibrarySynchronizedGroupDecision,
) {
    decision.preferredMetadataSourceKey?.let { key ->
        database.libraryGroupDecisionQueries.upsertLibraryGroupMetadataPreference(
            profile_id = decision.profileId.value,
            group_id = decision.targetGroupId.value,
            source_key = LibrarySourceKeyCodec.encode(key),
            decision_id = decision.decisionId,
            server_revision = decision.revision,
        )
    }
    decision.preferredMediaSourceKeys.forEach { (mediaType, key) ->
        database.libraryGroupDecisionQueries.upsertLibraryGroupMediaPreference(
            profile_id = decision.profileId.value,
            group_id = decision.targetGroupId.value,
            media_type = mediaType,
            source_key = LibrarySourceKeyCodec.encode(key),
            decision_id = decision.decisionId,
            server_revision = decision.revision,
        )
    }
}

private fun ensureActiveRemoteGroup(
    database: AppDatabase,
    profileId: LibraryProfileId,
    groupId: LibraryGroupId,
    createdAt: String,
    reactivateRetiredGroup: Boolean = false,
) : LibraryGroupId {
    val existing = database.libraryGroupQueries.getLibraryGroup(
        profileId.value,
        groupId.value,
    ).executeAsOneOrNull()
    if (existing == null) {
        database.libraryGroupQueries.insertLibraryGroup(
            profile_id = profileId.value,
            group_id = groupId.value,
            created_at = createdAt,
        )
        return groupId
    }
    if (reactivateRetiredGroup) {
        database.libraryGroupDecisionQueries.deleteLibraryGroupAlias(
            profileId.value,
            groupId.value,
        )
        return groupId
    }
    return requireNotNull(database.resolveGroupId(profileId, groupId)) {
        "A synchronized group reference resolves to a missing group"
    }
}

private fun moveOrInsertRemoteMember(
    database: AppDatabase,
    profileId: LibraryProfileId,
    key: SourceBookKey,
    targetGroupId: LibraryGroupId,
    decisionId: String,
    revision: Long,
) {
    var row = findMembership(database, key)
    if (row == null) {
        val identity = key.toStorageIdentity()
        database.libraryGroupQueries.insertLibraryGroupMembership(
            profile_id = key.profileId.value,
            adapter_id = key.adapterId.value,
            identity_kind = identity.kind,
            backend_id = identity.backendId,
            account_id = identity.accountId,
            unresolved_connection_id = identity.unresolvedConnectionId,
            native_book_id = key.nativeBookId.value,
            group_id = targetGroupId.value,
            execution_connection_id = null,
            legacy_library_book_id = null,
        )
        row = findMembership(database, key)
    }
    val membership = requireNotNull(row)
    database.libraryGroupQueries.moveLibraryGroupMembership(
        group_id = targetGroupId.value,
        membership_origin = LibraryMembershipOrigin.Manual.name,
        revision = revision,
        decision_id = decisionId,
        profile_id = membership.profile_id,
        adapter_id = membership.adapter_id,
        identity_kind = membership.identity_kind,
        backend_id = membership.backend_id,
        account_id = membership.account_id,
        unresolved_connection_id = membership.unresolved_connection_id,
        native_book_id = membership.native_book_id,
    )
}

private fun findMembership(
    database: AppDatabase,
    key: SourceBookKey,
): Library_group_memberships? {
    val identity = key.toStorageIdentity()
    return database.libraryGroupQueries.getLibraryGroupMembership(
        profile_id = key.profileId.value,
        adapter_id = key.adapterId.value,
        identity_kind = identity.kind,
        backend_id = identity.backendId,
        account_id = identity.accountId,
        unresolved_connection_id = identity.unresolvedConnectionId,
        native_book_id = key.nativeBookId.value,
    ).executeAsOneOrNull()
}

private fun addRemoteGroupAlias(
    database: AppDatabase,
    profileId: LibraryProfileId,
    aliasId: LibraryGroupId,
    targetId: LibraryGroupId,
    createdAt: String,
) {
    if (aliasId == targetId) return
    val existing = database.libraryGroupDecisionQueries.getLibraryGroupAlias(
        profileId.value,
        aliasId.value,
    ).executeAsOneOrNull()
    if (existing != null) {
        check(existing.target_group_id == targetId.value) {
            "A synchronized group alias already targets another group"
        }
        return
    }
    database.libraryGroupDecisionQueries.insertLibraryGroupAlias(
        profile_id = profileId.value,
        alias_group_id = aliasId.value,
        target_group_id = targetId.value,
        created_at = createdAt,
    )
}

private fun AppDatabase.resolveGroupId(
    profileId: LibraryProfileId,
    groupId: LibraryGroupId,
): LibraryGroupId? {
    val visited = mutableSetOf<LibraryGroupId>()
    var current = groupId
    while (true) {
        check(visited.add(current)) { "Group alias cycle detected" }
        val alias = libraryGroupDecisionQueries.getLibraryGroupAlias(
            profileId.value,
            current.value,
        ).executeAsOneOrNull()
        if (alias == null) {
            return if (libraryGroupQueries.getLibraryGroup(
                profileId.value,
                current.value,
            ).executeAsOneOrNull() != null) current else null
        }
        current = LibraryGroupId(alias.target_group_id)
    }
}

private fun insertManualSeparations(
    database: AppDatabase,
    profileId: LibraryProfileId,
    pairs: List<Pair<SourceBookKey, SourceBookKey>>,
    decisionId: String,
    createdAt: String,
) {
    val orderedPairs = pairs.map { (first, second) ->
        val firstKey = LibrarySourceKeyCodec.encode(first)
        val secondKey = LibrarySourceKeyCodec.encode(second)
        if (firstKey < secondKey) {
            firstKey to secondKey
        } else {
            secondKey to firstKey
        }
    }.distinct().sortedWith(
        compareBy<Pair<String, String>> { pair -> pair.first }
            .thenBy { pair -> pair.second },
    )

    orderedPairs.forEachIndexed { index, (firstKey, secondKey) ->
        val existing = database.libraryGroupDecisionQueries.getLibraryManualSeparation(
            profileId.value,
            firstKey,
            secondKey,
        ).executeAsOneOrNull()
        when {
            existing == null -> database.libraryGroupDecisionQueries
                .insertLibraryManualSeparation(
                    profile_id = profileId.value,
                    first_source_key = firstKey,
                    second_source_key = secondKey,
                    decision_id = "$decisionId:separation:$index",
                    created_at = createdAt,
                )

            existing.overridden_by_decision_id != null -> database.libraryGroupDecisionQueries
                .reactivateLibraryManualSeparation(
                    decision_id = "$decisionId:separation:$index",
                    created_at = createdAt,
                    profile_id = profileId.value,
                    first_source_key = firstKey,
                    second_source_key = secondKey,
                )

            else -> Unit
        }
    }
}

private fun validateOutboxEntry(
    entry: SyncOutboxEntry?,
    decisionId: String,
) {
    if (entry == null) return
    require(entry.mutationId == decisionId) {
        "A grouping decision must use its decision ID as the outbox mutation ID"
    }
    require(entry.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION)
    require(entry.entityId == decisionId)
    require(entry.operation == SyncOutboxEntry.OPERATION_UPSERT)
}

private data class StorageIdentity(
    val kind: String,
    val backendId: String,
    val accountId: String,
    val unresolvedConnectionId: String,
)

private fun SourceBookKey.toStorageIdentity(): StorageIdentity = when (val identity =
    accountIdentity) {
    is SourceAccountIdentity.Portable -> StorageIdentity(
        kind = "portable",
        backendId = identity.backendId,
        accountId = identity.accountId,
        unresolvedConnectionId = "",
    )
    is SourceAccountIdentity.Unresolved -> StorageIdentity(
        kind = "unresolved",
        backendId = "",
        accountId = "",
        unresolvedConnectionId = identity.connectionId.value,
    )
}

private fun Library_group_memberships.toRecord(): LibraryGroupMembershipRecord {
    val accountIdentity = when (identity_kind) {
        "portable" -> SourceAccountIdentity.Portable(backend_id, account_id)
        "unresolved" -> SourceAccountIdentity.Unresolved(
            SourceConnectionId(unresolved_connection_id),
        )
        else -> error("Unknown source identity kind: $identity_kind")
    }
    return LibraryGroupMembershipRecord(
        groupId = LibraryGroupId(group_id),
        source = SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId(profile_id),
                adapterId = LibraryAdapterId(adapter_id),
                accountIdentity = accountIdentity,
                nativeBookId = NativeBookId(native_book_id),
            ),
            connectionId = execution_connection_id?.let { id -> SourceConnectionId(id) },
            legacyLibraryBookId = legacy_library_book_id?.let { id -> LegacyLibraryBookId(id) },
        ),
        origin = LibraryMembershipOrigin.valueOf(membership_origin),
        revision = revision,
        decisionId = decision_id,
    )
}
