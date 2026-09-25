package com.retro99.server.storyteller.model

import com.retro99.base.url.CoverUrlBuilder
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookCollection
import com.retro99.server.api.ServerBookSeries
import com.retro99.server.api.ServerType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StorytellerBookApiModel(
    @SerialName("uuid")
    val uuid: String,

    @SerialName("title")
    val title: String,

    @SerialName("language")
    val language: String? = null,

    @SerialName("createdAt")
    val createdAt: String? = null,

    @SerialName("updatedAt")
    val updatedAt: String? = null,

    @SerialName("publicationDate")
    val publicationDate: String? = null,

    @SerialName("description")
    val description: String? = null,

    @SerialName("rating")
    val rating: Float? = null,

    @SerialName("suffix")
    val suffix: String? = null,

    @SerialName("subtitle")
    val subtitle: String? = null,

    @SerialName("authors")
    val authors: List<StorytellerPersonApiModel> = emptyList(),

    @SerialName("narrators")
    val narrators: List<StorytellerPersonApiModel> = emptyList(),

    @SerialName("creators")
    val creators: List<StorytellerPersonApiModel> = emptyList(),

    @SerialName("series")
    val series: List<StorytellerSeriesApiModel> = emptyList(),

    @SerialName("tags")
    val tags: List<StorytellerTagApiModel> = emptyList(),

    @SerialName("ebook")
    val ebook: StorytellerMediaFileApiModel? = null,

    @SerialName("audiobook")
    val audiobook: StorytellerMediaFileApiModel? = null,

    @SerialName("readaloud")
    val readaloud: StorytellerReadaloudApiModel? = null,

    @SerialName("collections")
    val collections: List<StorytellerCollectionApiModel> = emptyList(),
)

@Serializable
data class StorytellerReadaloudApiModel(
    @SerialName("filepath")
    val filepath: String? = null,
)

@Serializable
data class StorytellerPersonApiModel(
    @SerialName("id")
    val id: Long? = null,

    @SerialName("name")
    val name: String,
)

@Serializable
data class StorytellerSeriesApiModel(
    @SerialName("id")
    val id: Long? = null,

    @SerialName("uuid")
    val uuid: String? = null,

    @SerialName("name")
    val name: String,

    @SerialName("position")
    val position: Float? = null,

    @SerialName("featured")
    @Serializable(with = BooleanOrIntSerializer::class)
    val featured: Int? = null,
)

@Serializable
data class StorytellerTagApiModel(
    @SerialName("id")
    val id: Long? = null,

    @SerialName("name")
    val name: String,
)

@Serializable
data class StorytellerCollectionApiModel(
    @SerialName("uuid")
    val uuid: String,

    @SerialName("name")
    val name: String,

    @SerialName("createdAt")
    val createdAt: String? = null,

    @SerialName("updatedAt")
    val updatedAt: String? = null,
)

@Serializable
data class StorytellerMediaFileApiModel(
    @SerialName("filepath")
    val filepath: String? = null,

    @SerialName("size")
    val size: Long? = null,
)

fun StorytellerBookApiModel.toDomain(
    serverId: String,
    baseUrl: String?,
): ServerBook {
    val hasAudiobook = audiobook?.filepath != null && audiobook?.size != null
    return ServerBook(
        uuid = uuid,
        serverId = serverId,
        title = title,
        description = description,
        coverUrl = CoverUrlBuilder.buildCoverUrl(baseUrl, uuid),
        authors = authors.map { it.name },
        narrators = narrators.map { it.name },
        series = series.map {
            ServerBookSeries(
                id = it.uuid ?: it.id?.toString(),
                name = it.name,
                sequence = it.position,
            )
        },
        tags = tags.map { it.name },
        hasEbook = ebook?.filepath != null,
        hasAudiobook = hasAudiobook,
        hasReadaloud = readaloud?.filepath != null,
        ebookFilepath = ebook?.filepath?.let { "/api/v2/books/$uuid/files?format=ebook" },
        audiobookFilepath = audiobook?.filepath?.let { "/api/v2/books/$uuid/files?format=audiobook" },
        readaloudFilepath = readaloud?.filepath?.let { "/api/v2/books/$uuid/files?format=readaloud" },
        ebookFileSize = ebook?.size,
        audiobookFileSize = audiobook?.size,
        readaloudFileSize = null,
        // Timestamps
        createdAt = createdAt,
        lastOpenedAt = null, // Not available from API
        publicationDate = publicationDate,
        // Storyteller books are not local
        isLocal = false,
        serverType = ServerType.Storyteller,
        collections = collections.map { collection ->
            ServerBookCollection(
                id = collection.uuid,
                name = collection.name,
                createdAt = collection.createdAt,
                updatedAt = collection.updatedAt,
            )
        },
    )
}
