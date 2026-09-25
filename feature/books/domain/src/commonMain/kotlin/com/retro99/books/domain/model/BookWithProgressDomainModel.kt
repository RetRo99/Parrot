package com.retro99.books.domain.model

import com.retro99.server.api.library.SourceBookKey

/**
 * Wrapper class that combines a book with its progress information.
 * Used by use cases that need to return both book data and progress together.
 */
data class BookWithProgressDomainModel(
    val book: BookDomainModel,
    /**
     * Progress and cache information for the book.
     * Null if no progress or cache exists for this book.
     */
    val progressInfo: BookProgressInfoDomainModel?,
    /** Progress remains inspectable per exact source member and media choice. */
    val memberProgress: List<BookMemberProgressDomainModel> = emptyList(),
)

data class BookMemberProgressDomainModel(
    val sourceKey: SourceBookKey?,
    val serverId: String?,
    val bookUuid: String,
    val mediaTypes: Set<String>,
    val progressInfoByMediaType: Map<String, BookProgressInfoDomainModel?>,
    val savedPositionUpdatedAtByMediaType: Map<String, String?>,
)
