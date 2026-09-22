package com.retro99.server.parrotcloud

import com.retro99.database.api.library.LibraryBookEntity
import com.retro99.server.api.ServerBook
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
)

internal fun LibraryBookEntity.toServerBook(serverId: String): ServerBook {
    val normalizedFormat = format.lowercase()
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
        hasEbook = normalizedFormat == "ebook",
        hasAudiobook = normalizedFormat == "audiobook",
        hasReadaloud = normalizedFormat == "readaloud",
        createdAt = null,
        isLocal = false,
        serverType = ServerType.ParrotCloud,
        libraryBookId = libraryBookId,
        contentHash = contentHash,
        contentHashAlgorithm = contentHashAlgorithm,
        remoteRevision = remoteRevision,
    )
}
