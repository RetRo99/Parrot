package com.retro99.library.domain.operation

import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.LibraryUploadSource
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.StorageReplicaId
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

interface LibraryUploadTargetRepository {
    suspend fun resolve(
        profileId: LibraryProfileId,
        sources: List<LibraryUploadSource>,
        sourceAvailability: Map<
            LibraryOperationTarget.DeviceReplica,
            SourceResourceAvailability,
        >,
    ): LibraryUploadTargetResolution
}

@Factory
class ResolveLibraryUploadTargetsUseCase(
    @Provided private val repository: LibraryUploadTargetRepository,
) {
    suspend operator fun invoke(
        profileId: LibraryProfileId,
        sources: List<LibraryUploadSource>,
        sourceAvailability: Map<
            LibraryOperationTarget.DeviceReplica,
            SourceResourceAvailability,
        >,
    ): LibraryUploadTargetResolution = repository.resolve(
        profileId = profileId,
        sources = sources,
        sourceAvailability = sourceAvailability,
    )
}

data class LibraryUploadSourceInventory(
    val sources: List<LibraryUploadSource>,
    val availability: Map<
        LibraryOperationTarget.DeviceReplica,
        SourceResourceAvailability,
    >,
)

/** Builds exact asset and replica references from each member, without choosing a display row. */
fun LibraryBookGroup.uploadSourceInventory(): LibraryUploadSourceInventory {
    val sources = mutableListOf<LibraryUploadSource>()
    val availability = mutableMapOf<
        LibraryOperationTarget.DeviceReplica,
        SourceResourceAvailability,
    >()

    members.forEach { member ->
        member.snapshot.resources.forEach { resource ->
            val storage = resource.localStorageReference ?: return@forEach
            val format = resource.format?.takeIf(String::isNotBlank) ?: return@forEach
            val replica = LibraryOperationTarget.DeviceReplica(
                source = member.snapshot.source,
                resource = resource.reference,
                replicaId = StorageReplicaId(storage.value),
                storageRef = storage,
            )
            sources += LibraryUploadSource(
                assetId = MediaAssetId(resource.reference.stableAssetIdentity()),
                sourceReplica = replica,
                format = format,
            )
            availability[replica] = when (member.snapshot.status.presence) {
                com.retro99.server.api.library.SourcePresence.Present -> resource.availability
                com.retro99.server.api.library.SourcePresence.Removed ->
                    SourceResourceAvailability.Unavailable
                com.retro99.server.api.library.SourcePresence.Unknown ->
                    SourceResourceAvailability.Unknown
            }
        }
    }
    return LibraryUploadSourceInventory(sources, availability)
}

private fun com.retro99.server.api.library.SourceResourceRef.stableAssetIdentity(): String {
    val account = when (val identity = book.accountIdentity) {
        is SourceAccountIdentity.Portable -> listOf(
            "portable",
            identity.backendId,
            identity.accountId,
        )
        is SourceAccountIdentity.Unresolved -> listOf(
            "unresolved",
            identity.connectionId.value,
        )
    }
    return encodeLengthPrefixed(
        listOf(
            book.profileId.value,
            book.adapterId.value,
        ) + account + listOf(
            book.nativeBookId.value,
            nativeResourceId,
            revision.orEmpty(),
        ),
    )
}

private fun encodeLengthPrefixed(values: List<String>): String =
    values.joinToString(separator = "") { value -> "${value.length}:$value" }
