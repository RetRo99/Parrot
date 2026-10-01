package com.retro99.server.audiobookshelf.model

import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookSeries
import com.retro99.server.api.ServerType

fun AudiobookshelfLibraryItemApiModel.toDomain(
    serverId: String,
    baseUrl: String?,
): ServerBook {
    val metadata = media?.metadata
    val ebookFile = media?.ebookFile
    val hasAudiobook = media?.numAudioFiles?.let { it > 0 }
        ?: media?.audioFiles?.let { it.isNotEmpty() }
        ?: false
    val hasEbook = ebookFile?.ino != null || media?.ebookFileFormat != null

    val ebookDownloadPath = ebookFile?.ino?.let { ino ->
        "/api/items/$id/file/$ino"
    }

    val audiobookDownloadPaths = media?.audioFiles
        ?.filter { it.ino != null }
        ?.joinToString("|") { audioFile -> "/api/items/$id/file/${audioFile.ino}" }
        ?.takeIf { it.isNotEmpty() }

    return ServerBook(
        uuid = id,
        serverId = serverId,
        title = metadata?.title ?: "",
        description = metadata?.description,
        coverUrl = baseUrl?.let { "${it.trimEnd('/')}/api/items/$id/cover" },
        authors = listOfNotNull(metadata?.authorName),
        narrators = listOfNotNull(metadata?.narratorName),
        series = metadata?.series.orEmpty().map { seriesItem ->
            ServerBookSeries(
                id = seriesItem.id,
                name = seriesItem.name,
                sequence = seriesItem.sequence?.toFloatOrNull(),
            )
        },
        tags = media?.tags.orEmpty(),
        hasEbook = hasEbook,
        hasAudiobook = hasAudiobook,
        hasReadaloud = false,
        ebookFilepath = ebookDownloadPath,
        audiobookFilepath = audiobookDownloadPaths,
        ebookFileSize = ebookFile?.metadata?.size ?: ebookFile?.size,
        audiobookFileSize = media?.size,
        createdAt = addedAt?.toString(),
        lastOpenedAt = null,
        publicationDate = metadata?.publishedYear,
        isLocal = false,
        serverType = ServerType.Audiobookshelf,
        language = metadata?.language?.takeIf { value -> value.isNotBlank() },
        isbn = metadata?.isbn?.takeIf { value -> value.isNotBlank() },
        asin = metadata?.asin?.takeIf { value -> value.isNotBlank() },
        audioDurationMs = audioDurationMs(),
    )
}

/** The item's total audio length: the media duration, or the sum of its audio files. */
internal fun AudiobookshelfLibraryItemApiModel.audioDurationMs(): Long? {
    val seconds = media?.duration?.takeIf { value -> value > 0 }
        ?: media?.audioFiles.orEmpty()
            .mapNotNull { file -> file.duration }
            .sum()
            .takeIf { sum -> sum > 0 }
        ?: return null
    return (seconds * 1000).toLong()
}
