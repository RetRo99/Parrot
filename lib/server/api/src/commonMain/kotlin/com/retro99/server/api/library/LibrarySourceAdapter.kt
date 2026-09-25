package com.retro99.server.api.library

import kotlin.time.Instant

/** Source-owned metadata remains intact so grouping and later splitting are lossless. */
data class SourceBookMetadata(
    val title: String,
    val description: String? = null,
    val coverReference: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<SourceBookSeries> = emptyList(),
    val tags: List<String> = emptyList(),
    val mediaTypes: List<String> = emptyList(),
    val publicationDate: String? = null,
    val collections: List<SourceBookCollection> = emptyList(),
) {
    init { require(title.isNotBlank()) }
}

data class SourceBookCollection(
    val nativeId: String,
    val name: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    init {
        require(nativeId.isNotBlank())
        require(name.isNotBlank())
    }
}

data class SourceBookSeries(
    val nativeId: String?,
    val name: String,
    val sequence: Float?,
) {
    init { require(name.isNotBlank()) }
}

enum class SourcePresence { Present, Removed, Unknown }

enum class SourceResourceAvailability {
    DevicePresent,
    AvailableRemotely,
    TransferPending,
    Unavailable,
    Unknown,
}

/** Installation-local opaque reference. It is never synchronized as availability. */
data class DeviceStorageRef(val value: String) {
    init { require(value.isNotBlank()) }
}

/** Adapter-owned remote reference; it must not be a device path in disguise. */
data class RemoteResourceRef(val value: String) {
    init { require(value.isNotBlank()) }
}

data class SourceSnapshotStatus(
    val observedAt: Instant,
    val presence: SourcePresence,
    val isAuthoritative: Boolean,
    val revision: String? = null,
) {
    init {
        require(presence != SourcePresence.Removed || isAuthoritative) {
            "Only an authoritative source event can report removal"
        }
    }
}

data class SourceMediaResource(
    val reference: SourceResourceRef,
    val mediaType: String,
    val format: String? = null,
    val sizeBytes: Long? = null,
    val availability: SourceResourceAvailability = SourceResourceAvailability.Unknown,
    val localStorageReference: DeviceStorageRef? = null,
    val remoteResourceReference: RemoteResourceRef? = null,
) {
    init {
        require(mediaType.isNotBlank())
        require(localStorageReference == null || remoteResourceReference == null) {
            "A resource location is either device storage or remote storage"
        }
    }
}

/** Normalized source data. It contains no displayed group identity or grouping decision. */
data class SourceBookSnapshot(
    val source: SourceBookRef,
    val metadata: SourceBookMetadata,
    val resources: List<SourceMediaResource>,
    val identityEvidence: List<SourceIdentityEvidence> = emptyList(),
    val status: SourceSnapshotStatus,
)

/**
 * Adapter input is deliberately opaque to the library core. Implementations may
 * wrap an existing ServerBook or an integration-native cached source record.
 */
interface LibrarySourceRecord

interface LibrarySourceAdapter {
    val adapterId: LibraryAdapterId

    fun normalize(record: LibrarySourceRecord): SourceBookSnapshot
}

/**
 * Optional bridge for current ServerBook repositories. Source adapters own the rules for
 * mapping native resource IDs and deciding which identity evidence their backend supports.
 */
interface ServerBookSourceAdapter : LibrarySourceAdapter {
    fun snapshot(
        source: SourceBookRef,
        book: com.retro99.server.api.ServerBook,
        status: SourceSnapshotStatus,
    ): SourceBookSnapshot
}

interface LibrarySourceAdapterRegistry {
    fun adapter(adapterId: LibraryAdapterId): LibrarySourceAdapter?

    fun normalize(
        adapterId: LibraryAdapterId,
        record: LibrarySourceRecord,
    ): SourceBookSnapshot = requireNotNull(adapter(adapterId)) {
        "No library source adapter registered for ${adapterId.value}"
    }.normalize(record)
}
