package com.retro99.database.implementation.dao.library

import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibrarySourceIdentityPromotionDatabase
import com.retro99.database.api.library.LibrarySourceIdentityPromotionResult
import com.retro99.database.api.library.LibrarySourceIdentityPromotionStatus
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.Library_group_memberships
import com.retro99.server.api.library.LegacyLibraryBookId
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryMembershipOrigin
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock

internal class LibrarySourceIdentityPromotionSqlDelightDao(
    private val profileSession: ProfileDatabaseSession,
    private val databaseProvider: () -> AppDatabase,
) : LibrarySourceIdentityPromotionDatabase {
    override suspend fun promoteUnresolvedSourceIdentity(
        unresolvedKey: SourceBookKey,
        portableSource: SourceBookRef,
    ): LibrarySourceIdentityPromotionStatus {
        validatePromotion(unresolvedKey, portableSource)
        return profileSession.withProfile(unresolvedKey.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.transactionWithResult {
                    database.promoteSourceIdentity(unresolvedKey, portableSource)
                }
            }
        }
    }

    override suspend fun resolveSourceIdentityAlias(key: SourceBookKey): SourceBookKey? =
        profileSession.withProfile(key.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                database.librarySourceIdentityPromotionQueries
                    .getSourceIdentityAlias(
                        key.profileId.value,
                        LibrarySourceKeyCodec.encode(key),
                    )
                    .executeAsOneOrNull()
                    ?.target_source_key
                    ?.let { target -> LibrarySourceKeyCodec.decode(key.profileId, target) }
            }
        }

    override suspend fun resolvePromotedParrotCloudSource(
        unresolvedKey: SourceBookKey,
        linkedConnectionId: SourceConnectionId,
        cloudUserId: String,
    ): SourceBookKey? {
        val account = unresolvedKey.accountIdentity as? SourceAccountIdentity.Unresolved
            ?: return null
        if (
            unresolvedKey.adapterId.value != PARROT_CLOUD_ADAPTER_ID ||
            account.connectionId != linkedConnectionId ||
            cloudUserId.isBlank()
        ) {
            return null
        }
        return profileSession.withProfile(unresolvedKey.profileId.value) {
            withContext(Dispatchers.IO) {
                val database = databaseProvider()
                val oldKey = LibrarySourceKeyCodec.encode(unresolvedKey)
                val alias = database.librarySourceIdentityPromotionQueries
                    .getSourceIdentityAlias(unresolvedKey.profileId.value, oldKey)
                    .executeAsOneOrNull()
                    ?: return@withContext null
                val promotedKey = LibrarySourceKeyCodec.decode(
                    unresolvedKey.profileId,
                    alias.target_source_key,
                )
                val portable = promotedKey.accountIdentity as? SourceAccountIdentity.Portable
                    ?: return@withContext null
                promotedKey.takeIf { sourceKey ->
                    sourceKey.adapterId.value == PARROT_CLOUD_ADAPTER_ID &&
                        portable.backendId == PARROT_CLOUD_BACKEND_ID &&
                        portable.accountId == cloudUserId
                }
            }
        }
    }

    override suspend fun promoteUnresolvedParrotCloudSources(
        profileId: LibraryProfileId,
        connectionId: SourceConnectionId,
        cloudUserId: String,
    ): List<LibrarySourceIdentityPromotionResult> {
        require(cloudUserId.isNotBlank())
        val memberships = profileSession.withProfile(profileId.value) {
            withContext(Dispatchers.IO) {
                databaseProvider().librarySourceIdentityPromotionQueries
                    .getUnresolvedParrotCloudMembershipsForConnection(
                        profile_id = profileId.value,
                        unresolved_connection_id = connectionId.value,
                        execution_connection_id = connectionId.value,
                    )
                    .executeAsList()
            }
        }
        return memberships.map { membership ->
            val unresolvedKey = membership.toUnresolvedKey()
            val portableSource = SourceBookRef(
                key = SourceBookKey(
                    profileId = profileId,
                    adapterId = LibraryAdapterId(PARROT_CLOUD_ADAPTER_ID),
                    accountIdentity = SourceAccountIdentity.Portable(
                        backendId = PARROT_CLOUD_BACKEND_ID,
                        accountId = cloudUserId,
                    ),
                    nativeBookId = NativeBookId(membership.native_book_id),
                ),
                connectionId = connectionId,
                legacyLibraryBookId = membership.legacy_library_book_id?.let(
                    ::LegacyLibraryBookId,
                ),
            )
            LibrarySourceIdentityPromotionResult(
                nativeBookId = membership.native_book_id,
                status = promoteUnresolvedSourceIdentity(unresolvedKey, portableSource),
            )
        }
    }

    private fun AppDatabase.promoteSourceIdentity(
        unresolvedKey: SourceBookKey,
        portableSource: SourceBookRef,
    ): LibrarySourceIdentityPromotionStatus {
        val profileId = unresolvedKey.profileId
        val oldStorageKey = LibrarySourceKeyCodec.encode(unresolvedKey)
        val targetStorageKey = LibrarySourceKeyCodec.encode(portableSource.key)
        val queries = librarySourceIdentityPromotionQueries

        val existingAlias = queries.getSourceIdentityAlias(profileId.value, oldStorageKey)
            .executeAsOneOrNull()
        if (existingAlias != null) {
            return if (existingAlias.target_source_key == targetStorageKey) {
                LibrarySourceIdentityPromotionStatus.AlreadyPromoted
            } else {
                LibrarySourceIdentityPromotionStatus.Conflict
            }
        }

        val membership = queries.getUnresolvedMembershipForPromotion(
            profileId.value,
            unresolvedKey.adapterId.value,
            unresolvedConnectionId(unresolvedKey).value,
            unresolvedKey.nativeBookId.value,
        ).executeAsOneOrNull() ?: return LibrarySourceIdentityPromotionStatus.NotFound

        val targetMembership = queries.getPortableMembershipForPromotion(
            profileId.value,
            unresolvedKey.adapterId.value,
            portableAccountIdentity(portableSource).backendId,
            portableAccountIdentity(portableSource).accountId,
            portableSource.key.nativeBookId.value,
        ).executeAsOneOrNull()
        val mergeDecisionId = targetMembership?.takeIf { target ->
            membership.group_id == target.group_id &&
                membership.membership_origin == LibraryMembershipOrigin.Manual.name &&
                target.membership_origin == LibraryMembershipOrigin.Manual.name &&
                membership.decision_id != null &&
                membership.decision_id == target.decision_id
        }?.decision_id
        val canCollapseMergedMemberships = mergeDecisionId != null
        val oldSnapshot = queries.getSourceSnapshotForPromotion(
            profileId.value,
            oldStorageKey,
        ).executeAsOneOrNull()
        val targetSnapshot = queries.getSourceSnapshotForPromotion(
            profileId.value,
            targetStorageKey,
        ).executeAsOneOrNull()
        val resourceCollisionCount = queries.countPromotionResourceKeyCollisions(
            profileId.value,
            oldStorageKey,
            targetStorageKey,
        ).executeAsOne()
        val selfSeparationCount = queries.countPromotionSelfSeparations(
            profileId.value,
            oldStorageKey,
            targetStorageKey,
            targetStorageKey,
            oldStorageKey,
        ).executeAsOne()
        val oldSeparations = queries.getManualSeparationsForIdentityPromotion(
            profileId.value,
            oldStorageKey,
            oldStorageKey,
        ).executeAsList()
        val mergedSelfSeparations = oldSeparations.filter { row ->
            (row.first_source_key == oldStorageKey &&
                row.second_source_key == targetStorageKey) ||
                (row.first_source_key == targetStorageKey &&
                    row.second_source_key == oldStorageKey)
        }
        val selfSeparationsAreOverriddenByMerge = canCollapseMergedMemberships &&
            mergedSelfSeparations.isNotEmpty() &&
            mergedSelfSeparations.all { row -> row.overridden_by_decision_id == mergeDecisionId }
        val hasUnresolvedSelfSeparation = selfSeparationCount > 0L &&
            !selfSeparationsAreOverriddenByMerge
        val separationsToRekey = if (selfSeparationsAreOverriddenByMerge) {
            oldSeparations - mergedSelfSeparations.toSet()
        } else {
            oldSeparations
        }
        val movingDecisionIds = separationsToRekey.mapTo(mutableSetOf()) { row -> row.decision_id }
        val separationRekeys = separationsToRekey.map { row ->
            val first = row.first_source_key.replaceExact(oldStorageKey, targetStorageKey)
            val second = row.second_source_key.replaceExact(oldStorageKey, targetStorageKey)
            if (first < second) {
                ManualSeparationRekey(
                    first = first,
                    second = second,
                    decisionId = row.decision_id,
                    createdAt = row.created_at,
                    overriddenByDecisionId = row.overridden_by_decision_id,
                )
            } else {
                ManualSeparationRekey(
                    first = second,
                    second = first,
                    decisionId = row.decision_id,
                    createdAt = row.created_at,
                    overriddenByDecisionId = row.overridden_by_decision_id,
                )
            }
        }
        val separationKeys = separationRekeys.map { row -> row.first to row.second }
        val hasSeparationCollision = separationKeys.distinct().size != separationKeys.size ||
            separationRekeys.any { rekey ->
                val existing = libraryGroupDecisionQueries.getLibraryManualSeparation(
                    profileId.value,
                    rekey.first,
                    rekey.second,
                ).executeAsOneOrNull()
                existing != null && existing.decision_id !in movingDecisionIds
            }
        if (
            (targetMembership != null && !canCollapseMergedMemberships) ||
            ((targetSnapshot != null || resourceCollisionCount > 0L) &&
                !canCollapseMergedMemberships) ||
            hasUnresolvedSelfSeparation || hasSeparationCollision
        ) {
            return LibrarySourceIdentityPromotionStatus.Conflict
        }

        queries.insertSourceIdentityAlias(
            profile_id = profileId.value,
            old_source_key = oldStorageKey,
            target_source_key = targetStorageKey,
            created_at = Clock.System.now().toString(),
        )
        if (oldSnapshot != null && targetSnapshot == null) {
            queries.insertPromotedSourceSnapshot(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
            queries.copyPromotedSourceSnapshotPeople(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
            queries.copyPromotedSourceSnapshotSeries(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
            queries.copyPromotedSourceSnapshotTags(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
            queries.copyPromotedSourceSnapshotMediaTypes(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
            queries.copyPromotedSourceSnapshotResources(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
        }
        if (!canCollapseMergedMemberships) {
            queries.updatePromotedSourceResourceKeys(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
        }
        if (!canCollapseMergedMemberships) {
            queries.updatePromotedBookFingerprintSourceKey(
                targetStorageKey,
                profileId.value,
                oldStorageKey,
            )
        }
        separationsToRekey.forEach { row ->
            queries.deleteManualSeparationForIdentityPromotion(
                row.profile_id,
                row.first_source_key,
                row.second_source_key,
            )
        }
        separationRekeys.forEach { row ->
            queries.insertManualSeparationForIdentityPromotion(
                profileId.value,
                row.first,
                row.second,
                row.decisionId,
                row.createdAt,
                row.overriddenByDecisionId,
            )
        }
        queries.updatePromotedSourceMetadataPreference(
            targetStorageKey,
            profileId.value,
            oldStorageKey,
        )
        if (oldSnapshot != null && targetSnapshot == null) {
            queries.deletePromotedSourceSnapshot(profileId.value, oldStorageKey)
        }
        if (canCollapseMergedMemberships) {
            queries.deleteUnresolvedMembershipForPromotion(
                profile_id = profileId.value,
                adapter_id = unresolvedKey.adapterId.value,
                unresolved_connection_id = unresolvedConnectionId(unresolvedKey).value,
                source_native_book_id = unresolvedKey.nativeBookId.value,
                execution_connection_id = unresolvedConnectionId(unresolvedKey).value,
            )
        } else {
            queries.updateMembershipIdentityForPromotion(
                backend_id = portableAccountIdentity(portableSource).backendId,
                account_id = portableAccountIdentity(portableSource).accountId,
                target_native_book_id = portableSource.key.nativeBookId.value,
                profile_id = profileId.value,
                adapter_id = unresolvedKey.adapterId.value,
                unresolved_connection_id = unresolvedConnectionId(unresolvedKey).value,
                source_native_book_id = unresolvedKey.nativeBookId.value,
                execution_connection_id = unresolvedConnectionId(unresolvedKey).value,
            )
        }
        check(membership.unresolved_connection_id == unresolvedConnectionId(unresolvedKey).value)
        return LibrarySourceIdentityPromotionStatus.Promoted
    }

    private fun Library_group_memberships.toUnresolvedKey(): SourceBookKey = SourceBookKey(
        profileId = LibraryProfileId(profile_id),
        adapterId = LibraryAdapterId(adapter_id),
        accountIdentity = SourceAccountIdentity.Unresolved(
            SourceConnectionId(unresolved_connection_id),
        ),
        nativeBookId = NativeBookId(native_book_id),
    )

    private fun unresolvedConnectionId(key: SourceBookKey): SourceConnectionId =
        (key.accountIdentity as? SourceAccountIdentity.Unresolved)?.connectionId
            ?: error("Source identity promotion requires an unresolved key")

    private fun portableAccountIdentity(source: SourceBookRef): SourceAccountIdentity.Portable =
        source.key.accountIdentity as? SourceAccountIdentity.Portable
            ?: error("Source identity promotion requires a portable source")

    private fun String.replaceExact(old: String, new: String): String =
        if (this == old) new else this

    private fun validatePromotion(
        unresolvedKey: SourceBookKey,
        portableSource: SourceBookRef,
    ) {
        val unresolved = unresolvedKey.accountIdentity as? SourceAccountIdentity.Unresolved
            ?: error("Source identity promotion requires an unresolved key")
        val portable = portableSource.key.accountIdentity as? SourceAccountIdentity.Portable
            ?: error("Source identity promotion requires a portable key")
        require(portableSource.key.adapterId == unresolvedKey.adapterId)
        require(portableSource.key.profileId == unresolvedKey.profileId)
        require(portableSource.connectionId == unresolved.connectionId)
        if (unresolvedKey.adapterId.value == LOCAL_ADAPTER_ID) {
            require(
                portable.backendId == LocalContentIdentity.BACKEND_ID &&
                    portable.accountId == LocalContentIdentity.ACCOUNT_ID &&
                    LocalContentIdentity.isPortableNativeBookId(
                        portableSource.key.nativeBookId.value,
                    ),
            ) {
                "Local identity promotion requires a verified whole-file SHA-256 identity"
            }
        } else {
            require(portableSource.key.nativeBookId == unresolvedKey.nativeBookId) {
                "Adapter account promotion cannot change its native book ID"
            }
            if (unresolvedKey.adapterId.value == PARROT_CLOUD_ADAPTER_ID) {
                require(
                    portable.backendId == PARROT_CLOUD_BACKEND_ID &&
                        portable.accountId.isNotBlank(),
                )
            }
        }
    }

    private companion object {
        const val PARROT_CLOUD_ADAPTER_ID = "parrot-cloud"
        const val PARROT_CLOUD_BACKEND_ID = "parrot-cloud"
        const val LOCAL_ADAPTER_ID = "local"
    }
}

private data class ManualSeparationRekey(
    val first: String,
    val second: String,
    val decisionId: String,
    val createdAt: String,
    val overriddenByDecisionId: String?,
)
