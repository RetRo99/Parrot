package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.importedbooks.ImportedBookEntity
import com.retro99.server.api.ServerBook
import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal data class ParrotCloudLibraryBookEntity(
    override val libraryBookId: String,
    override val contentHash: String?,
    override val contentHashAlgorithm: String?,
    override val title: String,
    override val author: String?,
    override val format: String,
    override val remoteRevision: Long? = null,
    override val deletedAt: String? = null,
    override val cloudBookId: String? = null,
    override val metadataJson: String? = null,
) : LibraryBookEntity

@Serializable
internal data class ParrotCloudBookPayload(
    @SerialName("library_book_id")
    val libraryBookId: String,
    @SerialName("cloud_book_id")
    val cloudBookId: String? = null,
    @SerialName("content_hash")
    val contentHash: String,
    @SerialName("content_hash_algorithm")
    val contentHashAlgorithm: String,
    val title: String,
    val author: String? = null,
    val format: String,
    @SerialName("metadata_json")
    val metadataJson: String? = null,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
)

internal fun LibraryBookEntity.toServerBook(
    serverId: String,
    localBook: ImportedBookEntity?,
    fileStates: List<CloudBookFileEntity>,
): ServerBook {
    val normalizedFormat = format.lowercase()
    val resources = if (fileStates.isNotEmpty()) {
        fileStates.map { file ->
            MediaResource(
                mediaType = file.mediaType,
                localPath = localBook?.filePath.takeIf { file.mediaType == localBook?.bookType },
                remoteAvailability = file.status.toRemoteAvailability(),
                size = file.sizeBytes,
                contentHash = file.contentHash,
                contentHashAlgorithm = file.contentHashAlgorithm,
                localOrigin = localBook?.origin.takeIf { file.mediaType == localBook?.bookType },
                cloudBookFileId = file.cloudBookFileId,
                nativeResourceId = file.cloudBookFileId,
                format = file.mediaType,
            )
        }
    } else {
        listOf(
            MediaResource(
                mediaType = normalizedFormat,
                localPath = localBook?.filePath,
                remoteAvailability = RemoteFileAvailability.None,
                size = localBook?.fileSize,
                contentHash = contentHash,
                contentHashAlgorithm = contentHashAlgorithm,
                localOrigin = localBook?.origin,
                cloudBookFileId = localBook?.cloudBookFileId,
                nativeResourceId = localBook?.cloudBookFileId,
                format = normalizedFormat,
            ),
        )
    }
    return ServerBook(
        uuid = cloudBookId ?: libraryBookId,
        serverId = serverId,
        title = title,
        description = null,
        coverUrl = null,
        authors = listOfNotNull(author),
        narrators = emptyList(),
        series = emptyList(),
        tags = emptyList(),
        hasEbook = normalizedFormat == "ebook" || resources.any { it.mediaType == "ebook" },
        hasAudiobook = normalizedFormat == "audiobook" || resources.any { it.mediaType == "audiobook" },
        hasReadaloud = normalizedFormat == "readaloud" || resources.any { it.mediaType == "readaloud" },
        ebookFilepath = resources.firstOrNull { it.mediaType == "ebook" }?.localPath,
        audiobookFilepath = resources.firstOrNull { it.mediaType == "audiobook" }?.localPath,
        readaloudFilepath = resources.firstOrNull { it.mediaType == "readaloud" }?.localPath,
        ebookFileSize = resources.firstOrNull { it.mediaType == "ebook" }?.size,
        audiobookFileSize = resources.firstOrNull { it.mediaType == "audiobook" }?.size,
        readaloudFileSize = resources.firstOrNull { it.mediaType == "readaloud" }?.size,
        createdAt = null,
        isLocal = false,
        serverType = ServerType.ParrotCloud,
        libraryBookId = libraryBookId,
        contentHash = contentHash,
        contentHashAlgorithm = contentHashAlgorithm,
        remoteRevision = remoteRevision,
        remoteFileAvailability = resources.aggregateRemoteAvailability(),
        mediaResources = resources,
        localSourceUuid = localBook?.uuid,
    )
}

private fun String.toRemoteAvailability(): RemoteFileAvailability = when (this) {
    "available" -> RemoteFileAvailability.Available
    "upload_pending" -> RemoteFileAvailability.UploadPending
    "uploading" -> RemoteFileAvailability.Uploading
    "upload_failed" -> RemoteFileAvailability.UploadFailed
    "deleting" -> RemoteFileAvailability.Deleting
    else -> RemoteFileAvailability.None
}

private fun List<MediaResource>.aggregateRemoteAvailability(): RemoteFileAvailability = when {
    any { it.remoteAvailability == RemoteFileAvailability.Deleting } -> RemoteFileAvailability.Deleting
    any { it.remoteAvailability == RemoteFileAvailability.Uploading } -> RemoteFileAvailability.Uploading
    any { it.remoteAvailability == RemoteFileAvailability.UploadPending } -> RemoteFileAvailability.UploadPending
    any { it.remoteAvailability == RemoteFileAvailability.UploadFailed } -> RemoteFileAvailability.UploadFailed
    isNotEmpty() && all { it.remoteAvailability == RemoteFileAvailability.Available } ->
        RemoteFileAvailability.Available
    else -> RemoteFileAvailability.None
}
