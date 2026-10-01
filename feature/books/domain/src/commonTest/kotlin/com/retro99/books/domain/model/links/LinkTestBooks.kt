package com.retro99.books.domain.model.links

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.MediaFileDomainModel
import com.retro99.books.domain.model.PersonDomainModel
import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
import com.retro99.server.api.ServerType

internal fun testLibraryBook(
    uuid: String,
    title: String = "Book $uuid",
    inParrot: Boolean = false,
    onDevice: Boolean = true,
    lastOpenedAt: String? = null,
    author: String? = null,
    isbn: String? = null,
) = BookDomainModel.LibraryBook(
    uuid = uuid,
    serverId = LOCAL_SERVER_ID,
    serverType = ServerType.Local,
    title = title,
    description = null,
    coverUrl = null,
    author = author,
    publicationDate = null,
    addedAt = "2026-10-01T00:00:00Z",
    lastOpenedAt = lastOpenedAt,
    mediaResources = listOf(
        MediaResource(
            mediaType = "ebook",
            localPath = if (onDevice) "/library/${uuid}_ebook.epub" else null,
            remoteAvailability = if (inParrot) {
                RemoteFileAvailability.Available
            } else {
                RemoteFileAvailability.None
            },
        ),
    ),
    isbn = isbn,
)

internal fun testServerBook(
    uuid: String,
    serverType: ServerType = ServerType.Storyteller,
    serverId: String = "${serverType.identifier}-1",
    title: String = "Book $uuid",
    hasAudiobook: Boolean = false,
    authors: List<String> = emptyList(),
    language: String? = null,
    isbn: String? = null,
    asin: String? = null,
    subtitle: String? = null,
) = BookDomainModel.StorytellerBook(
    uuid = uuid,
    serverId = serverId,
    serverType = serverType,
    title = title,
    description = null,
    coverUrl = null,
    id = 0L,
    language = language,
    createdAt = null,
    updatedAt = null,
    publicationDate = null,
    rating = null,
    suffix = null,
    subtitle = subtitle,
    ebookCoverUrl = null,
    audiobookCoverUrl = null,
    authors = authors.map { name ->
        PersonDomainModel(
            uuid = name,
            id = null,
            name = name,
            fileAs = null,
            createdAt = null,
            updatedAt = null,
        )
    },
    narrators = emptyList(),
    creators = emptyList(),
    series = emptyList(),
    tags = emptyList(),
    collections = emptyList(),
    status = null,
    ebook = MediaFileDomainModel(
        uuid = "$uuid-ebook",
        filepath = null,
        missing = null,
        createdAt = null,
        updatedAt = null,
    ),
    audiobook = if (hasAudiobook) {
        MediaFileDomainModel(
            uuid = "$uuid-audiobook",
            filepath = null,
            missing = null,
            createdAt = null,
            updatedAt = null,
        )
    } else {
        null
    },
    readaloud = null,
    isbn = isbn,
    asin = asin,
)

internal fun testLink(linkId: String, vararg members: String) = BookLink(
    linkId = linkId,
    members = members.mapNotNull { member -> CopyKey.parse(member) }.toSet(),
)
