package com.retro99.books.ui.model

import com.retro99.base.server.ServerType
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.links.linkOrder
import kotlinx.serialization.Serializable

/**
 * Sealed class representing a book in the UI layer.
 * Either a book from a Storyteller or Audiobookshelf server, or a book in your library.
 */
@Serializable
sealed class BookUiModel {
    abstract val uuid: String
    abstract val serverId: String
    abstract val serverType: ServerType?
    abstract val title: String
    abstract val description: String?
    abstract val coverUrl: String?

    abstract val hasEbook: Boolean
    abstract val hasAudiobook: Boolean
    abstract val hasReadaloud: Boolean

    abstract val series: List<SeriesUiModel>

    abstract val statusName: String?

    abstract val authors: List<String>

    abstract val tags: List<String>

    abstract val subtitle: String?

    abstract val rating: Float?

    abstract val publicationDate: String?

    /**
     * The date when the book was added to the library.
     * For local books this is importedAt, for Storyteller books this is createdAt.
     */
    abstract val dateAdded: String?
    open val narrators: List<String> get() = emptyList()
    open val language: String? get() = null
    open val audioDurationMs: Long? get() = null
    open val lastOpened: String? get() = null
    open val mediaSizes: Map<BookType, Long> get() = emptyMap()
    open val narrationStatus: String? get() = null
    open val narrationStage: String? get() = null
    open val narrationProgress: Double? get() = null

    /** Where the book lives, shown as its badge. */
    abstract val home: BookHome

    /** The other copies of a linked book. Empty for a book that isn't linked. */
    abstract val linkedCopies: List<LinkedCopyUiModel>

    /** Every home of the book: this copy's first, then its linked copies' in priority order. */
    val homes: List<BookHome>
        get() = (
            listOf(home) +
                linkedCopies.map { copy -> copy.home }.sortedBy { other -> other.linkOrder }
            ).distinct()

    /**
     * Returns the file path for the given book type, or null if not available.
     */
    abstract fun filePath(bookType: BookType): String?

    /**
     * Book from the Storyteller server with full metadata and media files.
     */
    @Serializable
    data class StorytellerBook(
        override val uuid: String,
        override val serverId: String,
        override val serverType: ServerType?,
        override val title: String,
        override val description: String?,
        override val coverUrl: String?,
        override val subtitle: String?,
        override val authors: List<String>,
        override val series: List<SeriesUiModel>,
        override val tags: List<String>,
        override val statusName: String?,
        override val rating: Float?,
        override val publicationDate: String?,
        override val dateAdded: String?,
        override val hasEbook: Boolean,
        override val hasAudiobook: Boolean,
        override val hasReadaloud: Boolean,
        val ebookFilepath: String?,
        val audiobookFilepath: String?,
        val readaloudFilepath: String?,
        val libraryBookId: String? = null,
        val remoteFileAvailability: String = "None",
        val mediaResources: List<MediaResourceUiModel> = emptyList(),
        override val home: BookHome = BookHome.Storyteller,
        override val linkedCopies: List<LinkedCopyUiModel> = emptyList(),
        override val narrators: List<String> = emptyList(),
        override val language: String? = null,
        override val audioDurationMs: Long? = null,
        override val lastOpened: String? = null,
        override val mediaSizes: Map<BookType, Long> = emptyMap(),
        override val narrationStatus: String? = null,
        override val narrationStage: String? = null,
        override val narrationProgress: Double? = null,
    ) : BookUiModel() {
        override fun filePath(bookType: BookType): String? = when (bookType) {
            BookType.EBOOK -> ebookFilepath
            BookType.AUDIOBOOK -> audiobookFilepath
            BookType.READALOUD -> readaloudFilepath
        }
    }

    /** A book in your library. [uuid] is the book id. */
    @Serializable
    data class LibraryBook(
        override val uuid: String,
        override val serverId: String,
        override val serverType: ServerType?,
        override val title: String,
        override val description: String?,
        override val coverUrl: String?,
        val author: String?,
        override val publicationDate: String?,
        val addedAt: String,
        val lastOpenedAt: String?,
        override val home: BookHome,
        val mediaResources: List<MediaResourceUiModel>,
        override val linkedCopies: List<LinkedCopyUiModel> = emptyList(),
    ) : BookUiModel() {
        val libraryBookId: String get() = uuid
        val hasDeviceCopy: Boolean
            get() = mediaResources.any { resource -> resource.localPath != null }
        override val hasEbook: Boolean get() = hasMediaType(BookType.EBOOK)
        override val hasAudiobook: Boolean get() = hasMediaType(BookType.AUDIOBOOK)
        override val hasReadaloud: Boolean get() = hasMediaType(BookType.READALOUD)
        override val series: List<SeriesUiModel> get() = emptyList()
        override val statusName: String? get() = null
        override val authors: List<String> get() = listOfNotNull(author)
        override val tags: List<String> get() = emptyList()
        override val subtitle: String? get() = null
        override val rating: Float? get() = null
        override val dateAdded: String? get() = addedAt
        override val lastOpened: String? get() = lastOpenedAt
        override val mediaSizes: Map<BookType, Long>
            get() = mediaResources.mapNotNull { resource ->
                val type = BookType.entries.find { type -> type.value == resource.mediaType }
                val size = resource.size
                if (type != null && size != null) type to size else null
            }.toMap()

        /** The device copy of [bookType], or null when it isn't on this device. */
        override fun filePath(bookType: BookType): String? = mediaResource(bookType)?.localPath

        fun mediaResource(bookType: BookType): MediaResourceUiModel? =
            mediaResources.firstOrNull { resource -> resource.mediaType == bookType.value }

        private fun hasMediaType(bookType: BookType): Boolean = mediaResource(bookType) != null
    }
}

@Serializable
data class MediaResourceUiModel(
    val mediaType: String,
    val localPath: String?,
    val remoteAvailability: String,
    val size: Long?,
    val contentHash: String?,
    val contentHashAlgorithm: String?,
    val localOrigin: String? = null,
    val cloudBookFileId: String? = null,
)

@Serializable
data class SeriesUiModel(
    val uuid: String,
    val name: String,
    val position: Double?,
)
