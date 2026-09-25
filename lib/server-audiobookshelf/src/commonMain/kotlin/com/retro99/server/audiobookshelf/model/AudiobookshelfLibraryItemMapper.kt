package com.retro99.server.audiobookshelf.model

import com.retro99.server.api.MediaResource
import com.retro99.server.api.RemoteFileAvailability
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
    val orderedAudioFiles = media?.audioFiles.orEmpty().orderedByTrackIndex()

    val ebookDownloadPath = ebookFile?.ino?.let { ino ->
        "/api/items/$id/file/$ino"
    }

    val audiobookDownloadPaths = orderedAudioFiles
        .filter { audioFile -> audioFile.ino != null }
        .joinToString("|") { audioFile -> "/api/items/$id/file/${audioFile.ino}" }
        .takeIf { filePaths -> filePaths.isNotEmpty() }
    val resources = buildList {
        ebookFile?.ino?.let { resourceId ->
            add(
                MediaResource(
                    mediaType = "ebook",
                    remoteAvailability = RemoteFileAvailability.Available,
                    size = ebookFile.metadata?.size ?: ebookFile.size,
                    nativeResourceId = resourceId,
                    format = ebookFile.ebookFormat ?: media?.ebookFileFormat,
                ),
            )
        }
        orderedAudioFiles.forEach { audioFile ->
            val resourceId = audioFile.ino ?: return@forEach
            add(
                MediaResource(
                    mediaType = "audiobook",
                    remoteAvailability = RemoteFileAvailability.Available,
                    size = audioFile.metadata?.size,
                    nativeResourceId = resourceId,
                    format = audioFile.mimeType ?: audioFile.codec,
                ),
            )
        }
    }

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
        mediaResources = resources,
    )
}

private fun List<AudiobookshelfAudioFileApiModel>.orderedByTrackIndex():
    List<AudiobookshelfAudioFileApiModel> =
    if (all { audioFile -> audioFile.index != null }) {
        sortedBy { audioFile -> audioFile.index }
    } else {
        this
    }
