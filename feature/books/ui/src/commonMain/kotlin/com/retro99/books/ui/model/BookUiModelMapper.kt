package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.SeriesDomainModel
import com.retro99.books.domain.model.home
import com.retro99.server.api.MediaResource

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
        remoteFileAvailability = remoteFileAvailability.name,
        mediaResources = mediaResources.map { resource -> resource.toUiModel() },
        home = home,
        linkedCopies = linkedCopies.map { copy -> copy.toUiModel() },
        narrators = narrators.map { narrator -> narrator.name },
        language = language,
        audioDurationMs = audioDurationMs,
        lastOpened = lastOpenedAt,
        mediaSizes = mediaSizes,
        narrationStatus = readaloud?.status,
        narrationStage = readaloud?.currentStage,
        narrationProgress = readaloud?.stageProgress,
    )
    is BookDomainModel.LibraryBook -> BookUiModel.LibraryBook(
        uuid = uuid,
        serverId = serverId,
        serverType = serverType,
        title = title,
        description = description,
        coverUrl = coverUrl,
        author = author,
        publicationDate = publicationDate,
        addedAt = addedAt,
        lastOpenedAt = lastOpenedAt,
        home = home,
        mediaResources = mediaResources.map { resource -> resource.toUiModel() },
        linkedCopies = linkedCopies.map { copy -> copy.toUiModel() },
    )
}

private fun MediaResource.toUiModel() = MediaResourceUiModel(
    mediaType = mediaType,
    localPath = localPath,
    remoteAvailability = remoteAvailability.name,
    size = size,
    contentHash = contentHash,
    contentHashAlgorithm = contentHashAlgorithm,
    localOrigin = localOrigin,
    cloudBookFileId = cloudBookFileId,
)

fun SeriesDomainModel.toUiModel(): SeriesUiModel = SeriesUiModel(
    uuid = uuid,
    name = name,
    position = position,
)
