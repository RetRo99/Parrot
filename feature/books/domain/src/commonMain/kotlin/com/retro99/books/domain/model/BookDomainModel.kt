package com.retro99.books.domain.model

/**
 * Sealed class representing a book in the domain layer.
 * Can be either a Storyteller book (from remote API) or a local book (imported EPUB).
 */
sealed class BookDomainModel {
    abstract val uuid: String
    abstract val title: String
    abstract val description: String?
    abstract val coverUrl: String?

    /**
     * Book from the Storyteller server with full metadata and media files.
     */
    data class StorytellerBook(
        override val uuid: String,
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
        val series: List<SeriesDomainModel>,
        val tags: List<TagDomainModel>,
        val collections: List<CollectionDomainModel>,
        val status: StatusDomainModel?,
        val ebook: MediaFileDomainModel?,
        val audiobook: MediaFileDomainModel?,
        val readaloud: ReadaloudDomainModel?,
    ) : BookDomainModel()

    /**
     * Locally imported EPUB book.
     */
    data class LocalBook(
        override val uuid: String,
        override val title: String,
        override val description: String?,
        override val coverUrl: String?,
        val author: String?,
        val filePath: String,
        val fileSize: Long,
        val importedAt: String,
        val lastOpenedAt: String?,
    ) : BookDomainModel()
}

