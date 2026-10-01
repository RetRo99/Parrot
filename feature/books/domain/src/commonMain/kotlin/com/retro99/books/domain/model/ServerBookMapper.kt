package com.retro99.books.domain.model

import com.retro99.server.api.ServerBook
import com.retro99.server.api.MediaResource

/**
 * Maps a ServerBook to BookDomainModel.
 * Returns LibraryBook if isLocal flag is true, otherwise StorytellerBook.
 */
fun ServerBook.toBookDomainModel(): BookDomainModel {
    return if (isLocal) {
        BookDomainModel.LibraryBook(
            uuid = uuid,
            serverId = serverId,
            serverType = serverType,
            title = title,
            description = description,
            coverUrl = coverUrl,
            author = authors.firstOrNull(),
            publicationDate = publicationDate,
            addedAt = createdAt.orEmpty(),
            lastOpenedAt = lastOpenedAt,
            mediaResources = mediaResources,
        )
    } else {
        BookDomainModel.StorytellerBook(
            uuid = uuid,
            serverId = serverId,
            serverType = serverType,
            title = title,
            description = description,
            coverUrl = coverUrl,
            id = 0L,
            language = null,
            createdAt = createdAt,
            updatedAt = null,
            publicationDate = publicationDate,
            rating = null,
            suffix = null,
            subtitle = null,
            ebookCoverUrl = null,
            audiobookCoverUrl = null,
            authors = authors.map {
                PersonDomainModel(
                    uuid = it,
                    id = null,
                    name = it,
                    fileAs = null,
                    createdAt = null,
                    updatedAt = null,
                )
            },
            narrators = narrators.map {
                PersonDomainModel(
                    uuid = it,
                    id = null,
                    name = it,
                    fileAs = null,
                    createdAt = null,
                    updatedAt = null,
                )
            },
            creators = emptyList(),
            series = series.map { s ->
                SeriesDomainModel(
                    uuid = s.id ?: s.name,
                    name = s.name,
                    featured = null,
                    position = s.sequence?.toDouble(),
                    createdAt = null,
                    updatedAt = null,
                )
            },
            tags = tags.map { tagName ->
                TagDomainModel(
                    uuid = tagName,
                    name = tagName,
                    createdAt = null,
                    updatedAt = null,
                )
            },
            collections = emptyList(),
            status = null,
            ebook = if (hasEbook) MediaFileDomainModel(
                uuid = "$uuid-ebook",
                filepath = mediaResources.firstOrNull { it.mediaType == "ebook" }?.localPath ?: ebookFilepath,
                missing = null,
                createdAt = null,
                updatedAt = null,
            ) else null,
            audiobook = if (hasAudiobook) MediaFileDomainModel(
                uuid = "$uuid-audiobook",
                filepath = mediaResources.firstOrNull { it.mediaType == "audiobook" }?.localPath ?: audiobookFilepath,
                missing = null,
                createdAt = null,
                updatedAt = null,
            ) else null,
            readaloud = if (hasReadaloud) ReadaloudDomainModel(
                uuid = "$uuid-readaloud",
                filepath = mediaResources.firstOrNull { it.mediaType == "readaloud" }?.localPath ?: readaloudFilepath,
                missing = null,
                status = null,
                currentStage = null,
                stageProgress = null,
                queuePosition = null,
                restartPending = null,
                createdAt = null,
                updatedAt = null,
            ) else null,
            libraryBookId = libraryBookId,
            contentHash = contentHash,
            contentHashAlgorithm = contentHashAlgorithm,
            remoteFileAvailability = remoteFileAvailability,
            remoteRevision = remoteRevision,
            mediaResources = mediaResources,
        )
    }
}
