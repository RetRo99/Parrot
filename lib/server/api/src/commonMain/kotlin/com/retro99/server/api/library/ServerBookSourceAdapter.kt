package com.retro99.server.api.library

import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerBook

/**
 * Additive bridge for current repositories. The caller supplies scoped identity,
 * exact native resources, and evidence; this bridge never guesses them from UI data.
 */
data class ServerBookSourceRecord(
    val source: SourceBookRef,
    val book: ServerBook,
    val resources: List<SourceMediaResource>,
    val identityEvidence: List<SourceIdentityEvidence> = emptyList(),
    val status: SourceSnapshotStatus,
) : LibrarySourceRecord {
    init {
        require(resources.all { resource -> resource.reference.book == source.key }) {
            "Every resource must belong to the normalized source book"
        }
        require(identityEvidence.all { evidence -> evidence.referencesSource(source.key) }) {
            "Identity evidence must reference the normalized source book"
        }
    }
}

/** Shared translation only; source-specific identity policy stays in each adapter. */
abstract class ServerBookLibrarySourceAdapter : ServerBookSourceAdapter {
    protected open fun includeDeviceStorage(): Boolean = false

    protected open fun fingerprint(
        resource: SourceMediaResource,
        serverResource: MediaResource,
    ): SourceIdentityEvidence.FileFingerprint? = null

    final override fun normalize(record: LibrarySourceRecord): SourceBookSnapshot {
        val sourceRecord = record as? ServerBookSourceRecord
            ?: throw IllegalArgumentException("Expected a ServerBook source record")
        require(sourceRecord.source.key.adapterId == adapterId) {
            "Source record belongs to ${sourceRecord.source.key.adapterId.value}, not ${adapterId.value}"
        }
        return SourceBookSnapshot(
            source = sourceRecord.source,
            metadata = SourceBookMetadata(
                title = sourceRecord.book.title,
                description = sourceRecord.book.description,
                coverReference = sourceRecord.book.coverUrl,
                authors = sourceRecord.book.authors,
                narrators = sourceRecord.book.narrators,
                series = sourceRecord.book.series.map { series ->
                    SourceBookSeries(series.id, series.name, series.sequence)
                },
                tags = sourceRecord.book.tags,
                mediaTypes = sourceRecord.book.mediaTypes(),
                publicationDate = sourceRecord.book.publicationDate,
                collections = sourceRecord.book.collections.map { collection ->
                    SourceBookCollection(
                        nativeId = collection.id,
                        name = collection.name,
                        createdAt = collection.createdAt,
                        updatedAt = collection.updatedAt,
                    )
                },
            ),
            resources = sourceRecord.resources,
            identityEvidence = sourceRecord.identityEvidence,
            status = sourceRecord.status,
        )
    }

    open override fun snapshot(
        source: SourceBookRef,
        book: ServerBook,
        status: SourceSnapshotStatus,
    ): SourceBookSnapshot {
        require(source.key.adapterId == adapterId)
        val resources = book.mediaResources.mapNotNull { serverResource ->
            val nativeResourceId = serverResource.nativeResourceId ?: return@mapNotNull null
            val reference = SourceResourceRef(
                book = source.key,
                nativeResourceId = nativeResourceId,
                revision = serverResource.resourceRevision,
            )
            SourceMediaResource(
                reference = reference,
                mediaType = serverResource.mediaType,
                format = serverResource.format,
                sizeBytes = serverResource.size,
                availability = serverResource.toSourceAvailability(includeDeviceStorage()),
                localStorageReference = if (includeDeviceStorage()) {
                    serverResource.localPath?.let(::DeviceStorageRef)
                } else {
                    null
                },
                remoteResourceReference = if (includeDeviceStorage()) {
                    null
                } else {
                    nativeResourceId.let(::RemoteResourceRef)
                },
            )
        }
        val evidence = book.mediaResources.mapNotNull { serverResource ->
            val reference = resources.firstOrNull { resource ->
                resource.reference.nativeResourceId == serverResource.nativeResourceId &&
                    resource.reference.revision == serverResource.resourceRevision
            } ?: return@mapNotNull null
            fingerprint(reference, serverResource)
        }
        return SourceBookSnapshot(
            source = source,
            metadata = SourceBookMetadata(
                title = book.title,
                description = book.description,
                coverReference = book.coverUrl,
                authors = book.authors,
                narrators = book.narrators,
                series = book.series.map { series ->
                    SourceBookSeries(series.id, series.name, series.sequence)
                },
                tags = book.tags,
                mediaTypes = book.mediaTypes(),
                publicationDate = book.publicationDate,
                collections = book.collections.map { collection ->
                    SourceBookCollection(
                        nativeId = collection.id,
                        name = collection.name,
                        createdAt = collection.createdAt,
                        updatedAt = collection.updatedAt,
                    )
                },
            ),
            resources = resources,
            identityEvidence = evidence,
            status = status,
        )
    }
}

private fun ServerBook.mediaTypes(): List<String> = buildList {
    if (hasEbook) add("ebook")
    if (hasAudiobook) add("audiobook")
    if (hasReadaloud) add("readaloud")
}

private fun MediaResource.toSourceAvailability(
    deviceResource: Boolean,
): SourceResourceAvailability = when {
    deviceResource && localPath != null -> SourceResourceAvailability.DevicePresent
    remoteAvailability == RemoteFileAvailability.Available ->
        SourceResourceAvailability.AvailableRemotely
    remoteAvailability == RemoteFileAvailability.UploadPending ||
        remoteAvailability == RemoteFileAvailability.Uploading ||
        remoteAvailability == RemoteFileAvailability.Deleting ->
        SourceResourceAvailability.TransferPending
    remoteAvailability == RemoteFileAvailability.None ->
        SourceResourceAvailability.Unavailable
    else -> SourceResourceAvailability.Unknown
}

private fun SourceIdentityEvidence.referencesSource(
    source: SourceBookKey,
): Boolean = when (this) {
    is SourceIdentityEvidence.BookFingerprint -> book == source
    is SourceIdentityEvidence.FileFingerprint -> resource.book == source
    is SourceIdentityEvidence.CompletedTransfer ->
        this.source.book == source || destination.book == source
    is SourceIdentityEvidence.AdapterCertified -> resource.book == source
}
