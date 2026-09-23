package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.SeriesDomainModel

fun BookDomainModel.toUiModel(): BookUiModel = when (this) {
    is BookDomainModel.StorytellerBook -> BookUiModel.StorytellerBook(
        uuid = uuid,
        serverId = serverId,
        serverType = serverType,
        title = title,
        subtitle = subtitle,
        coverUrl = coverUrl,
        authors = authors.map { it.name },
        series = series.map { it.toUiModel() },
        tags = tags.map { it.name },
        statusName = status?.name,
        rating = rating,
        publicationDate = publicationDate,
        dateAdded = createdAt,
        description = description,
        hasEbook = ebook != null,
        hasAudiobook = audiobook != null,
        hasReadaloud = readaloud != null,
        ebookFilepath = ebook?.filepath,
        audiobookFilepath = audiobook?.filepath,
        readaloudFilepath = readaloud?.filepath,
        libraryBookId = libraryBookId,
        localSourceUuid = localSourceUuid,
        remoteFileAvailability = remoteFileAvailability.name,
        mediaResources = mediaResources.map { resource ->
            MediaResourceUiModel(
                mediaType = resource.mediaType,
                localPath = resource.localPath,
                remoteAvailability = resource.remoteAvailability.name,
                size = resource.size,
                contentHash = resource.contentHash,
                contentHashAlgorithm = resource.contentHashAlgorithm,
                localOrigin = resource.localOrigin,
                cloudBookFileId = resource.cloudBookFileId,
            )
        },
    )
    is BookDomainModel.LocalBook -> BookUiModel.LocalBook(
        uuid = uuid,
        serverId = serverId,
        serverType = serverType,
        title = title,
        description = description,
        coverUrl = coverUrl,
        author = author,
        filePath = filePath,
        fileSize = fileSize,
        importedAt = importedAt,
        lastOpenedAt = lastOpenedAt,
        bookType = bookType,
        publicationDate = publicationDate,
        libraryBookId = libraryBookId,
        origin = origin,
        cloudBookFileId = cloudBookFileId,
    )
}

fun SeriesDomainModel.toUiModel(): SeriesUiModel = SeriesUiModel(
    uuid = uuid,
    name = name,
    position = position,
)
