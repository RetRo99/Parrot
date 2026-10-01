package com.retro99.books.domain.model

import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.server.api.ServerType
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.MediaResource

/**
 * Sealed class representing a book in the domain layer.
 * Either a book from a Storyteller or Audiobookshelf server, or a book in your library.
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

    open val mediaResources: List<MediaResource>
        get() = emptyList()

    /** Identifiers used to suggest that books on different sources are the same book. */
    open val isbn: String?
        get() = null

    open val asin: String?
        get() = null

    /**
     * The other copies of this book, when it stands for a linked book in a list. Empty for
     * a book that isn't linked, and for a book loaded on its own.
     */
    open val linkedCopies: List<LinkedCopy>
        get() = emptyList()

    fun withLinkedCopies(copies: List<LinkedCopy>): BookDomainModel = when (this) {
        is StorytellerBook -> copy(linkedCopies = copies)
        is LibraryBook -> copy(linkedCopies = copies)
    }

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
        override val mediaResources: List<MediaResource> = emptyList(),
        override val isbn: String? = null,
        override val asin: String? = null,
        override val linkedCopies: List<LinkedCopy> = emptyList(),
        val audioDurationMs: Long? = null,
        val lastOpenedAt: String? = null,
        val mediaSizes: Map<BookType, Long> = emptyMap(),
    ) : BookDomainModel()

    /** Your library: files on this device and/or in Parrot Cloud. [uuid] is the book ID. */
    data class LibraryBook(
        override val uuid: String,
        override val serverId: String,
        override val serverType: ServerType?,
        override val title: String,
        override val description: String?,
        override val coverUrl: String?,
        val author: String?,
        val publicationDate: String?,
        val addedAt: String,
        val lastOpenedAt: String?,
        override val mediaResources: List<MediaResource>,
        override val isbn: String? = null,
        override val linkedCopies: List<LinkedCopy> = emptyList(),
    ) : BookDomainModel() {
        override val series: List<SeriesDomainModel> = emptyList()

        override val libraryBookId: String
            get() = uuid

        fun deviceFilePath(bookType: BookType): String? = mediaResources
            .firstOrNull { resource -> resource.mediaType == bookType.value }
            ?.localPath
    }
}
