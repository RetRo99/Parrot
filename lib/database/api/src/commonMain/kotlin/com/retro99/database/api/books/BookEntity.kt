package com.retro99.database.api.books

/**
 * Book entity with all its relations pre-loaded.
 * This provides a complete view of a book without requiring
 * the caller to fetch relations separately.
 */
interface BookEntity {
    val uuid: String
    val serverId: String // Track which server this book belongs to
    val serverType: String? // Server type identifier (e.g., "storyteller", "audiobookshelf", "local"); null for legacy cached rows
    val id: Long
    val title: String
    val subtitle: String?
    val language: String?
    val publicationDate: String?
    val description: String?
    val rating: Float?
    val suffix: String?
    val createdAt: String?
    val updatedAt: String?
    val authors: List<PersonEntity>
    val narrators: List<PersonEntity>
    val creators: List<PersonEntity>
    val series: List<SeriesWithPositionEntity>
    val tags: List<TagEntity>
    val collections: List<CollectionEntity>
    val status: StatusEntity?
    val coverUrl: String?
    val ebook: MediaFileEntity?
    val audiobook: MediaFileEntity?
    val readaloud: ReadaloudEntity?
    val isbn: String? get() = null
    val asin: String? get() = null
}

/**
 * Series entity with position information from the book-series relationship.
 */
interface SeriesWithPositionEntity {
    val uuid: String
    val name: String
    val featured: Int?
    val position: Double?
    val createdAt: String?
    val updatedAt: String?
}

