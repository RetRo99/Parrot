package com.retro99.books.domain.model

import com.retro99.server.api.ServerType
import com.retro99.server.api.RemoteFileAvailability

/**
 * Sealed class representing a book in the domain layer.
 * Can be either a Storyteller book (from remote API) or a local book (imported EPUB).
 */
sealed class BookDomainModel {
    abstract val uuid: String
    abstract val serverId: String
    abstract val serverType: ServerType?
    abstract val title: String
    abstract val description: String?
    abstract val coverUrl: String?

    open val libraryBookId: String?
        get() = null

    open val contentHash: String?
        get() = null

    open val contentHashAlgorithm: String?
        get() = null

    open val remoteFileAvailability: RemoteFileAvailability
        get() = RemoteFileAvailability.None

    open val remoteRevision: Long?
        get() = null

    abstract val series: List<SeriesDomainModel>

    /**
     * Book from the Storyteller server with full metadata and media files.
     */
    data class StorytellerBook(
        override val uuid: String,
        override val serverId: String,
        override val serverType: ServerType?,
        override val title: String,
        override val description: String?,
        override val coverUrl: String?,
        val id: Long,
        val language: String?,
        val createdAt: String?,
        val updatedAt: String?,
        val publicationDate: String?,
        val rating: Float?,
        val suffix: String?,
        val subtitle: String?,
        val ebookCoverUrl: String?,
        val audiobookCoverUrl: String?,
        val authors: List<PersonDomainModel>,
        val narrators: List<PersonDomainModel>,
        val creators: List<PersonDomainModel>,
        override val series: List<SeriesDomainModel>,
        val tags: List<TagDomainModel>,
        val collections: List<CollectionDomainModel>,
        val status: StatusDomainModel?,
        val ebook: MediaFileDomainModel?,
        val audiobook: MediaFileDomainModel?,
        val readaloud: ReadaloudDomainModel?,
        override val libraryBookId: String? = null,
        override val contentHash: String? = null,
        override val contentHashAlgorithm: String? = null,
        override val remoteFileAvailability: RemoteFileAvailability = RemoteFileAvailability.None,
        override val remoteRevision: Long? = null,
    ) : BookDomainModel()

    /**
     * Locally imported EPUB book.
     */
    data class LocalBook(
        override val uuid: String,
        override val serverId: String,
        override val serverType: ServerType?,
        override val title: String,
        override val description: String?,
        override val coverUrl: String?,
        val author: String?,
        val filePath: String,
        val fileSize: Long,
        override val contentHash: String? = null,
        override val contentHashAlgorithm: String? = null,
        val importedAt: String,
        val lastOpenedAt: String?,
        val bookType: BookType,
        val publicationDate: String?,
    ) : BookDomainModel() {
        override val series: List<SeriesDomainModel> = emptyList()

        override val libraryBookId: String?
            get() = contentHash?.let { hash -> "${contentHashAlgorithm ?: "sha-256-v1"}:$hash" }
    }
}
