package com.retro99.sync.domain

import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Merge, split, and preference decisions persisted locally and sent through Parrot sync. */
@Serializable
data class LibraryGroupDecisionPayload(
    val version: Int = CURRENT_VERSION,
    @SerialName("decision_id")
    val decisionId: String,
    @SerialName("decision_type")
    val decisionType: String,
    @SerialName("target_group_id")
    val targetGroupId: String,
    @SerialName("retained_group_id")
    val retainedGroupId: String? = null,
    val members: List<LibraryGroupMemberRef>,
    @SerialName("retained_members")
    val retainedMembers: List<LibraryGroupMemberRef> = emptyList(),
    @SerialName("preferred_metadata_member")
    val preferredMetadataMember: LibraryGroupMemberRef? = null,
    @SerialName("preferred_media_members")
    val preferredMediaMembers: Map<String, LibraryGroupMemberRef> = emptyMap(),
    @SerialName("created_at")
    val createdAt: String,
    /** Installation-local profile scope. The authenticated RPC account is the wire scope. */
    @SerialName("local_profile_id")
    val localProfileId: String,
) {
    init {
        require(version > 0)
        require(decisionId.isNotBlank())
        require(targetGroupId.isNotBlank())
        require(createdAt.isNotBlank())
        require(localProfileId.isNotBlank())
        require((members + retainedMembers).distinct().size == members.size + retainedMembers.size)
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MERGE = "merge"
        const val SPLIT = "split"
        const val PREFERENCES = "preferences"
    }
}

/** Local durable reference. Unresolved connection identity is intentionally never sent. */
@Serializable
data class LibraryGroupMemberRef(
    @SerialName("profile_id")
    val profileId: String,
    @SerialName("adapter_id")
    val adapterId: String,
    @SerialName("identity_kind")
    val identityKind: String,
    @SerialName("backend_id")
    val backendId: String = "",
    @SerialName("account_id")
    val accountId: String = "",
    @SerialName("connection_id")
    val connectionId: String = "",
    @SerialName("native_book_id")
    val nativeBookId: String,
) {
    init {
        require(profileId.isNotBlank())
        require(adapterId.isNotBlank())
        require(nativeBookId.isNotBlank())
        when (identityKind) {
            PORTABLE -> require(
                backendId.isNotBlank() && accountId.isNotBlank() && connectionId.isEmpty(),
            )

            UNRESOLVED -> require(
                backendId.isEmpty() && accountId.isEmpty() && connectionId.isNotBlank(),
            )

            else -> error("Unsupported library identity kind: $identityKind")
        }
    }

    val isPortable: Boolean
        get() = identityKind == PORTABLE

    companion object {
        const val PORTABLE = "portable"
        const val UNRESOLVED = "unresolved"

        fun from(key: SourceBookKey): LibraryGroupMemberRef =
            when (val identity = key.accountIdentity) {
            is SourceAccountIdentity.Portable -> LibraryGroupMemberRef(
                profileId = key.profileId.value,
                adapterId = key.adapterId.value,
                identityKind = PORTABLE,
                backendId = identity.backendId,
                accountId = identity.accountId,
                nativeBookId = key.nativeBookId.value,
            )

            is SourceAccountIdentity.Unresolved -> LibraryGroupMemberRef(
                profileId = key.profileId.value,
                adapterId = key.adapterId.value,
                identityKind = UNRESOLVED,
                connectionId = identity.connectionId.value,
                nativeBookId = key.nativeBookId.value,
            )
        }

        fun toSourceBookKey(
            profileId: LibraryProfileId,
            member: LibraryGroupMemberRef,
        ): SourceBookKey {
            require(member.isPortable) {
                "Unresolved members cannot be applied from a remote decision"
            }
            require(member.profileId == profileId.value) {
                "A synchronized decision cannot cross local profiles"
            }
            return SourceBookKey(
                profileId = profileId,
                adapterId = com.retro99.server.api.library.LibraryAdapterId(member.adapterId),
                accountIdentity = SourceAccountIdentity.Portable(
                    backendId = member.backendId,
                    accountId = member.accountId,
                ),
                nativeBookId = com.retro99.server.api.library.NativeBookId(member.nativeBookId),
            )
        }
    }
}

/** The wire shape has no installation profile, URL, connection ID, or local resource path. */
@Serializable
data class PortableLibraryGroupDecision(
    val version: Int,
    @SerialName("decision_id")
    val decisionId: String,
    @SerialName("decision_type")
    val decisionType: String,
    @SerialName("target_group_id")
    val targetGroupId: String,
    @SerialName("retained_group_id")
    val retainedGroupId: String? = null,
    val members: List<PortableLibraryGroupMember>,
    @SerialName("retained_members")
    val retainedMembers: List<PortableLibraryGroupMember> = emptyList(),
    @SerialName("preferred_metadata_member")
    val preferredMetadataMember: PortableLibraryGroupMember? = null,
    @SerialName("preferred_media_members")
    val preferredMediaMembers: Map<String, PortableLibraryGroupMember> = emptyMap(),
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class PortableLibraryGroupMember(
    @SerialName("adapter_id")
    val adapterId: String,
    @SerialName("backend_id")
    val backendId: String,
    @SerialName("account_id")
    val accountId: String,
    @SerialName("native_book_id")
    val nativeBookId: String,
) {
    init {
        require(adapterId.isNotBlank())
        require(backendId.isNotBlank())
        require(accountId.isNotBlank())
        require(nativeBookId.isNotBlank())
    }

    companion object {
        fun from(member: LibraryGroupMemberRef): PortableLibraryGroupMember {
            require(member.isPortable) { "Unresolved members cannot be serialized for sync" }
            return PortableLibraryGroupMember(
                adapterId = member.adapterId,
                backendId = member.backendId,
                accountId = member.accountId,
                nativeBookId = member.nativeBookId,
            )
        }

        fun toLocal(
            profileId: LibraryProfileId,
            member: PortableLibraryGroupMember,
        ): LibraryGroupMemberRef = LibraryGroupMemberRef(
            profileId = profileId.value,
            adapterId = member.adapterId,
            identityKind = LibraryGroupMemberRef.PORTABLE,
            backendId = member.backendId,
            accountId = member.accountId,
            nativeBookId = member.nativeBookId,
        )
    }
}

sealed interface LibraryGroupSyncReadiness {
    data class Ready(val payload: String) : LibraryGroupSyncReadiness

    data class Deferred(val unresolvedMemberCount: Int) : LibraryGroupSyncReadiness

    data class UnsupportedVersion(val version: Int) : LibraryGroupSyncReadiness
}

/** Versioned local/wire codec shared by manual grouping and the existing mutation transport. */
object LibraryGroupDecisionCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeLocal(payload: LibraryGroupDecisionPayload): String = json.encodeToString(payload)

    fun decodeLocal(payload: String): LibraryGroupDecisionPayload =
        json.decodeFromString(payload)

    fun encodeWireOrDefer(localPayload: String): LibraryGroupSyncReadiness {
        val local = try {
            json.decodeFromString<LibraryGroupDecisionPayload>(localPayload)
        } catch (_: SerializationException) {
            return LibraryGroupSyncReadiness.UnsupportedVersion(-1)
        }
        if (local.version != LibraryGroupDecisionPayload.CURRENT_VERSION) {
            return LibraryGroupSyncReadiness.UnsupportedVersion(local.version)
        }
        val members = local.members + local.retainedMembers +
            listOfNotNull(local.preferredMetadataMember) + local.preferredMediaMembers.values
        val unresolvedCount = members.count { member -> !member.isPortable }
        if (unresolvedCount > 0) return LibraryGroupSyncReadiness.Deferred(unresolvedCount)
        if (!local.isStructurallyValid()) return LibraryGroupSyncReadiness.Deferred(members.size)

        val wire = local.toPortable()
        return LibraryGroupSyncReadiness.Ready(json.encodeToString(wire))
    }

    fun decodeWire(payload: String): PortableLibraryGroupDecision =
        json.decodeFromString(payload)

    fun toLocal(
        profileId: LibraryProfileId,
        payload: PortableLibraryGroupDecision,
    ): LibraryGroupDecisionPayload {
        require(payload.version == LibraryGroupDecisionPayload.CURRENT_VERSION) {
            "Unsupported library group payload version ${payload.version}"
        }
        return LibraryGroupDecisionPayload(
            version = payload.version,
            decisionId = payload.decisionId,
            decisionType = payload.decisionType,
            targetGroupId = payload.targetGroupId,
            retainedGroupId = payload.retainedGroupId,
            members = payload.members.map { member ->
                PortableLibraryGroupMember.toLocal(profileId, member)
            },
            retainedMembers = payload.retainedMembers.map { member ->
                PortableLibraryGroupMember.toLocal(profileId, member)
            },
            preferredMetadataMember = payload.preferredMetadataMember?.let { member ->
                PortableLibraryGroupMember.toLocal(profileId, member)
            },
            preferredMediaMembers = payload.preferredMediaMembers.mapValues { (_, member) ->
                PortableLibraryGroupMember.toLocal(profileId, member)
            },
            createdAt = payload.createdAt,
            localProfileId = profileId.value,
        )
    }

    private fun LibraryGroupDecisionPayload.toPortable() = PortableLibraryGroupDecision(
        version = version,
        decisionId = decisionId,
        decisionType = decisionType,
        targetGroupId = targetGroupId,
        retainedGroupId = retainedGroupId,
        members = members.map { member -> PortableLibraryGroupMember.from(member) },
        retainedMembers = retainedMembers.map { member -> PortableLibraryGroupMember.from(member) },
        preferredMetadataMember = preferredMetadataMember?.let { member ->
            PortableLibraryGroupMember.from(member)
        },
        preferredMediaMembers = preferredMediaMembers.mapValues { (_, member) ->
            PortableLibraryGroupMember.from(member)
        },
        createdAt = createdAt,
    )

    private fun LibraryGroupDecisionPayload.isStructurallyValid(): Boolean = when (decisionType) {
        LibraryGroupDecisionPayload.MERGE -> members.size >= 2 && retainedMembers.isEmpty()
        LibraryGroupDecisionPayload.SPLIT -> members.isNotEmpty() && retainedMembers.isNotEmpty() &&
            retainedGroupId != null
        LibraryGroupDecisionPayload.PREFERENCES -> members.isNotEmpty() &&
            (preferredMetadataMember != null || preferredMediaMembers.isNotEmpty())
        else -> false
    }
}
