package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibrarySourceIdentityPromotionDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibrarySourceIdentitySyncAuthorization
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.sync.domain.LibraryGroupDecisionCodec
import com.retro99.sync.domain.LibraryGroupDecisionPayload
import com.retro99.sync.domain.LibraryGroupMemberRef
import com.retro99.sync.domain.LibraryGroupSyncReadiness
import kotlinx.serialization.SerializationException

/** Resolves exact promoted source aliases without rewriting the local decision. */
internal suspend fun resolvePromotedSourceMembersForWire(
    localPayload: String,
    expectedLocalProfileId: String,
    linkedConnectionId: SourceConnectionId,
    cloudUserId: String,
    promotionDatabase: LibrarySourceIdentityPromotionDatabase,
): String {
    val payload = try {
        LibraryGroupDecisionCodec.decodeLocal(localPayload)
    } catch (_: SerializationException) {
        return localPayload
    }
    if (payload.localProfileId != expectedLocalProfileId) return localPayload

    suspend fun resolve(member: LibraryGroupMemberRef): LibraryGroupMemberRef {
        if (member.isPortable || member.profileId != expectedLocalProfileId) {
            return member
        }
        val unresolvedKey = SourceBookKey(
            profileId = LibraryProfileId(member.profileId),
            adapterId = LibraryAdapterId(member.adapterId),
            accountIdentity = SourceAccountIdentity.Unresolved(
                SourceConnectionId(member.connectionId),
            ),
            nativeBookId = NativeBookId(member.nativeBookId),
        )
        val promotedKey = if (member.adapterId == PARROT_CLOUD_ADAPTER_ID) {
            if (member.connectionId != linkedConnectionId.value) return member
            promotionDatabase.resolvePromotedParrotCloudSource(
                unresolvedKey = unresolvedKey,
                linkedConnectionId = linkedConnectionId,
                cloudUserId = cloudUserId,
            )
        } else {
            promotionDatabase.resolveSourceIdentityAlias(unresolvedKey)
        } ?: return member
        if (
            promotedKey.profileId.value != expectedLocalProfileId ||
            promotedKey.adapterId.value != member.adapterId ||
            promotedKey.accountIdentity !is SourceAccountIdentity.Portable
        ) {
            return member
        }
        return LibraryGroupMemberRef.from(promotedKey)
    }

    val resolved = try {
        val members = payload.members.map { member -> resolve(member) }
        val retainedMembers = payload.retainedMembers.map { member -> resolve(member) }
        val distinctReferences = mutableSetOf<LibraryGroupMemberRef>()
        val uniqueMembers = if (payload.decisionType == LibraryGroupDecisionPayload.MERGE) {
            members.filter { member -> distinctReferences.add(member) }
        } else {
            members
        }
        val uniqueRetainedMembers = if (payload.decisionType == LibraryGroupDecisionPayload.MERGE) {
            retainedMembers.filter { member -> distinctReferences.add(member) }
        } else {
            retainedMembers
        }
        payload.copy(
            members = uniqueMembers,
            retainedMembers = uniqueRetainedMembers,
            preferredMetadataMember = payload.preferredMetadataMember?.let { member ->
                resolve(member)
            },
            preferredMediaMembers = payload.preferredMediaMembers.mapValues { (_, member) ->
                resolve(member)
            },
        )
    } catch (_: IllegalArgumentException) {
        return localPayload
    }
    return LibraryGroupDecisionCodec.encodeLocal(resolved)
}

private const val PARROT_CLOUD_ADAPTER_ID = "parrot-cloud"

/** Returns only sendable mutations, translating eligible decisions in memory for this attempt. */
internal suspend fun prepareParrotCloudLibraryMutationsForWire(
    entries: List<SyncOutboxEntry>,
    expectedLocalProfileId: String,
    linkedConnectionId: SourceConnectionId,
    cloudUserId: String,
    promotionDatabase: LibrarySourceIdentityPromotionDatabase,
    identitySyncAuthorization: LibrarySourceIdentitySyncAuthorization,
): List<SyncOutboxEntry> = entries.mapNotNull { entry ->
    if (entry.entityType != SyncOutboxEntry.ENTITY_TYPE_LIBRARY_GROUP_DECISION) {
        return@mapNotNull entry
    }

    val payload = try {
        LibraryGroupDecisionCodec.decodeLocal(entry.payload)
    } catch (_: SerializationException) {
        return@mapNotNull null
    } catch (_: IllegalArgumentException) {
        return@mapNotNull null
    }
    val members = payload.members + payload.retainedMembers +
        listOfNotNull(payload.preferredMetadataMember) + payload.preferredMediaMembers.values
    if (
        payload.localProfileId != expectedLocalProfileId ||
        members.any { member -> member.profileId != expectedLocalProfileId }
    ) {
        return@mapNotNull null
    }

    val resolvedPayload = resolvePromotedSourceMembersForWire(
        localPayload = entry.payload,
        expectedLocalProfileId = expectedLocalProfileId,
        linkedConnectionId = linkedConnectionId,
        cloudUserId = cloudUserId,
        promotionDatabase = promotionDatabase,
    )
    val resolvedDecision = try {
        LibraryGroupDecisionCodec.decodeLocal(resolvedPayload)
    } catch (_: SerializationException) {
        return@mapNotNull null
    } catch (_: IllegalArgumentException) {
        return@mapNotNull null
    }
    val audiobookshelfMembers = (
        resolvedDecision.members + resolvedDecision.retainedMembers +
            listOfNotNull(resolvedDecision.preferredMetadataMember) +
            resolvedDecision.preferredMediaMembers.values
        ).distinct().filter { member ->
            member.isPortable && member.adapterId == AUDIOBOOKSHELF_ADAPTER_ID
        }
    for (member in audiobookshelfMembers) {
        if (!identitySyncAuthorization.isAuthorized(
                profileId = LibraryProfileId(member.profileId),
                cloudAccountId = cloudUserId,
                adapterId = LibraryAdapterId(member.adapterId),
                identity = SourceAccountIdentity.Portable(
                    backendId = member.backendId,
                    accountId = member.accountId,
                ),
            )
        ) {
            return@mapNotNull null
        }
    }
    when (LibraryGroupDecisionCodec.encodeWireOrDefer(resolvedPayload)) {
        is LibraryGroupSyncReadiness.Ready -> entry.copy(payload = resolvedPayload)
        else -> null
    }
}

private const val AUDIOBOOKSHELF_ADAPTER_ID = "audiobookshelf"
