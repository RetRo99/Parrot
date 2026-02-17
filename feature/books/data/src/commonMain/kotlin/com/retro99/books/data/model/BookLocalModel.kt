package com.retro99.books.data.model

import com.retro99.base.url.CoverUrlBuilder
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.database.api.books.BookEntity

data class BookLocalModel(
    override val uuid: String,
    override val id: Long,
    override val title: String,
    override val subtitle: String?,
    override val language: String?,
    override val publicationDate: String?,
    override val description: String?,
    override val rating: Float?,
    override val suffix: String?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val authors: List<PersonLocalModel>,
    override val narrators: List<PersonLocalModel>,
    override val creators: List<PersonLocalModel>,
    override val series: List<SeriesWithPositionLocalModel>,
    override val tags: List<TagLocalModel>,
    override val collections: List<CollectionLocalModel>,
    override val status: StatusLocalModel?,
    override val ebook: MediaFileLocalModel?,
    override val audiobook: MediaFileLocalModel?,
    override val readaloud: ReadaloudLocalModel?,
) : BookEntity

fun BookLocalModel.toDomain(baseUrl: String?): BookDomainModel.StorytellerBook {
    return BookDomainModel.StorytellerBook(
        uuid = uuid,
        title = title,
        id = id,
        language = language,
        createdAt = createdAt,
        updatedAt = updatedAt,
        publicationDate = publicationDate,
        description = description,
        rating = rating,
        suffix = suffix,
        subtitle = subtitle,
        coverUrl = CoverUrlBuilder.buildCoverUrl(baseUrl, uuid),
        ebookCoverUrl = CoverUrlBuilder.buildEbookCoverUrl(baseUrl, uuid),
        audiobookCoverUrl = CoverUrlBuilder.buildAudiobookCoverUrl(baseUrl, uuid),
        authors = authors.map { it.toDomain() },
        narrators = narrators.map { it.toDomain() },
        creators = creators.map { it.toDomain() },
        series = series.map { it.toDomain() },
        tags = tags.map { it.toDomain() },
        collections = collections.map { it.toDomain() },
        status = status?.toDomain(),
        ebook = ebook?.toDomain(),
        audiobook = audiobook?.toDomain(),
        readaloud = readaloud?.toDomain(),
    )
}

fun BookDomainModel.StorytellerBook.toLocal(): BookLocalModel {
    return BookLocalModel(
        uuid = uuid,
        id = id,
        title = title,
        subtitle = subtitle,
        language = language,
        publicationDate = publicationDate,
        description = description,
        rating = rating,
        suffix = suffix,
        createdAt = createdAt,
        updatedAt = updatedAt,
        authors = authors.map { it.toLocal() },
        narrators = narrators.map { it.toLocal() },
        creators = creators.map { it.toLocal() },
        series = series.map { it.toLocal() },
        tags = tags.map { it.toLocal() },
        collections = collections.map { it.toLocal() },
        status = status?.toLocal(),
        ebook = ebook?.toLocal(uuid, "ebook"),
        audiobook = audiobook?.toLocal(uuid, "audiobook"),
        readaloud = readaloud?.toLocal(uuid),
    )
}

