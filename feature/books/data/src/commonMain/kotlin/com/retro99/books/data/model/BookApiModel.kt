package com.retro99.books.data.model

import com.retro99.base.url.CoverUrlBuilder
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BookApiModel(
    @SerialName("uuid")
    val uuid: String,

    @SerialName("title")
    val title: String,

    @SerialName("id")
    val id: Long,

    @SerialName("language")
    val language: String? = null,

    @SerialName("createdAt")
    val createdAt: String? = null,

    @SerialName("updatedAt")
    val updatedAt: String? = null,

    @SerialName("publicationDate")
    val publicationDate: String? = null,

    @SerialName("alignedByStorytellerVersion")
    val alignedByStorytellerVersion: String? = null,

    @SerialName("alignedAt")
    val alignedAt: String? = null,

    @SerialName("alignedWith")
    val alignedWith: String? = null,

    @SerialName("description")
    val description: String? = null,

    @SerialName("rating")
    val rating: Float? = null,

    @SerialName("suffix")
    val suffix: String? = null,

    @SerialName("subtitle")
    val subtitle: String? = null,

    @SerialName("authors")
    val authors: List<PersonApiModel> = emptyList(),

    @SerialName("narrators")
    val narrators: List<PersonApiModel> = emptyList(),

    @SerialName("creators")
    val creators: List<PersonApiModel> = emptyList(),

    @SerialName("series")
    val series: List<SeriesApiModel> = emptyList(),

    @SerialName("tags")
    val tags: List<TagApiModel> = emptyList(),

    @SerialName("collections")
    val collections: List<CollectionApiModel> = emptyList(),

    @SerialName("status")
    val status: StatusApiModel? = null,

    @SerialName("ebook")
    val ebook: MediaFileApiModel? = null,

    @SerialName("audiobook")
    val audiobook: MediaFileApiModel? = null,

    @SerialName("readaloud")
    val readaloud: ReadaloudApiModel? = null,
)

fun BookApiModel.toDomain(baseUrl: String?): BookDomainModel.StorytellerBook {
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
