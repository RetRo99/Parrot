package com.retro99.books.domain.model

import com.retro99.server.api.ServerBook

/**
 * Maps a ServerBook to BookDomainModel.
 * Returns LocalBook if isLocal flag is true, otherwise StorytellerBook.
 */
fun ServerBook.toBookDomainModel(): BookDomainModel {
    return if (isLocal) {
        // Determine book type from which filepath is set
        val bookType = when {
            ebookFilepath != null -> BookType.EBOOK
            readaloudFilepath != null -> BookType.READALOUD
            audiobookFilepath != null -> BookType.AUDIOBOOK
            else -> BookType.EBOOK
        }
        // Get the file path and size based on book type
        val filePath = ebookFilepath ?: readaloudFilepath ?: audiobookFilepath ?: ""
        val fileSize = ebookFileSize ?: readaloudFileSize ?: audiobookFileSize ?: 0L

        BookDomainModel.LocalBook(
            uuid = uuid,
            serverId = serverId,
            serverType = serverType,
            title = title,
            description = description,
            coverUrl = coverUrl,
            author = authors.firstOrNull(),
            filePath = filePath,
            fileSize = fileSize,
            importedAt = createdAt ?: "",
            lastOpenedAt = lastOpenedAt,
            bookType = bookType,
            publicationDate = publicationDate,
            contentHash = contentHash,
            contentHashAlgorithm = contentHashAlgorithm,
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
                filepath = ebookFilepath,
                missing = null,
                createdAt = null,
                updatedAt = null,
            ) else null,
            audiobook = if (hasAudiobook) MediaFileDomainModel(
                uuid = "$uuid-audiobook",
                filepath = audiobookFilepath,
                missing = null,
                createdAt = null,
                updatedAt = null,
            ) else null,
            readaloud = if (hasReadaloud) ReadaloudDomainModel(
                uuid = "$uuid-readaloud",
                filepath = readaloudFilepath,
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
        )
    }
}
