package com.retro99.library.domain.projection

import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LocalContentIdentity
import com.retro99.server.api.library.ProgressOwnerRef
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.SourcePresence

data class LibraryReaderTarget(
    val connectionId: String,
    val nativeBookId: String,
    val resource: SourceResourceRef,
    val mediaType: String,
    val title: String,
    val storage: DeviceStorageRef,
    val progressOwner: ProgressOwnerRef,
)

/** Resolve a reader request only from the exact resource that is present on this device. */
fun LibraryGroupMember.readerTargetFor(
    resource: SourceMediaResource,
): LibraryReaderTarget? {
    if (snapshot.status.presence != SourcePresence.Present) return null
    if (resource !in snapshot.resources) return null
    if (resource.availability != SourceResourceAvailability.DevicePresent) return null
    val storage = resource.localStorageReference ?: return null
    val connectionId = snapshot.source.connectionId?.value ?: return null
    val supportedMediaType = when (resource.mediaType.lowercase()) {
        "ebook", "audiobook", "readaloud" -> resource.mediaType.lowercase()
        else -> return null
    }
    val nativeBookId = if (sourceKey.adapterId.value == LocalContentIdentity.ADAPTER_ID) {
        resource.reference.nativeResourceId
    } else {
        sourceKey.nativeBookId.value
    }
    return LibraryReaderTarget(
        connectionId = connectionId,
        nativeBookId = nativeBookId,
        resource = resource.reference,
        mediaType = supportedMediaType,
        title = snapshot.metadata.title,
        storage = storage,
        progressOwner = ProgressOwnerRef(
            adapterId = sourceKey.adapterId,
            source = snapshot.source,
            nativeProgressId = nativeBookId,
        ),
    )
}
