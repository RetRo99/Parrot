package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.SeriesDomainModel

fun BookDomainModel.StorytellerBook.toUiModel(): BookUiModel = BookUiModel(
    uuid = uuid,
    title = title,
    subtitle = subtitle,
    coverUrl = coverUrl,
    authors = authors.map { it.name },
    series = series.map { it.toUiModel() },
    tags = tags.map { it.name },
    statusName = status?.name,
    rating = rating,
    description = description,
    hasEbook = ebook?.filepath != null,
    hasAudiobook = audiobook?.filepath != null,
    hasReadaloud = readaloud?.filepath != null,
    ebookFilepath = ebook?.filepath,
    audiobookFilepath = audiobook?.filepath,
    readaloudFilepath = readaloud?.filepath,
)

fun SeriesDomainModel.toUiModel(): SeriesUiModel = SeriesUiModel(
    uuid = uuid,
    name = name,
    position = position,
)
