package com.retro99.server.audiobookshelf

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.usecase.DownloadMediaUseCase
import com.retro99.server.api.ServerNetworkClientProvider
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.OperationAvailability
import com.retro99.server.api.library.SourceMediaResource
import com.retro99.server.api.library.SourceResourceAvailability
import com.retro99.server.audiobookshelf.model.AudiobookshelfLibraryItemApiModel
import com.retro99.server.audiobookshelf.model.toDomain
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import retro99.network.api.get

@Single(binds = [LibraryOperationAdapter::class])
class AudiobookshelfLibraryOperationAdapter(
    @Provided private val networkClientProvider: ServerNetworkClientProvider,
    @Provided private val downloadMediaUseCase: DownloadMediaUseCase,
) : LibraryOperationAdapter {
    override val adapterId = LibraryAdapterId("audiobookshelf")

    override fun downloadOperationResources(
        resources: List<SourceMediaResource>,
    ): List<SourceMediaResource> {
        val audiobookRepresentative = resources.firstOrNull { resource ->
            resource.mediaType.equals(BookType.AUDIOBOOK.value, ignoreCase = true) &&
                resource.availability == SourceResourceAvailability.AvailableRemotely &&
                resource.remoteResourceReference != null
        }
        return resources.filter { resource ->
            !resource.mediaType.equals(BookType.AUDIOBOOK.value, ignoreCase = true) ||
                resource == audiobookRepresentative
        }
    }

    override fun supportsReaderCache(
        target: LibraryOperationTarget.RemoteReplica,
    ): Boolean = target.isAudiobookshelfFile()

    override fun readerCacheBookId(
        target: LibraryOperationTarget.RemoteReplica,
    ): String? = null

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> {
        val downloadable = target.isAudiobookshelfFile()
        return listOf(
            OperationAvailability(
                operation = LibraryOperation.Download,
                isAvailable = downloadable,
                reason = if (downloadable) null else "An Audiobookshelf file resource is required",
            ),
        )
    }

    override suspend fun execute(
        request: LibraryOperationRequest,
    ): LibraryOperationResult {
        if (request.operation != LibraryOperation.Download) {
            return LibraryOperationResult.Rejected(
                "Audiobookshelf only supports native media downloads",
            )
        }
        val target = request.target as? LibraryOperationTarget.RemoteReplica
            ?: return LibraryOperationResult.Rejected(
                "An Audiobookshelf remote file is required",
            )
        if (!target.isAudiobookshelfFile()) {
            return LibraryOperationResult.Rejected(
                "The selected Audiobookshelf file resource is no longer available",
            )
        }
        val connectionId = target.source.connectionId?.value
            ?: return LibraryOperationResult.Rejected(
                "The Audiobookshelf connection is no longer available",
            )
        val client = networkClientProvider.createForServerId(connectionId)
            ?: return LibraryOperationResult.Rejected(
                "The Audiobookshelf connection is no longer available",
            )
        val item = client.get<AudiobookshelfLibraryItemApiModel>(
            path = "/api/items/${target.source.key.nativeBookId.value}",
        ).getOrElse { error ->
            return LibraryOperationResult.Rejected(
                error.message ?: "The selected Audiobookshelf item could not be loaded",
            )
        }
        val book = item.toDomain(connectionId, client.baseUrl)
        val selectedResource = book.mediaResources.firstOrNull { resource ->
            resource.nativeResourceId == target.resource.nativeResourceId
        } ?: return LibraryOperationResult.Rejected(
            "The selected Audiobookshelf file is no longer available",
        )
        val bookType = BookType.entries.firstOrNull { candidate ->
            candidate.value == selectedResource.mediaType
        } ?: return LibraryOperationResult.Rejected(
            "The selected Audiobookshelf media type is not supported",
        )
        val filePath = when (bookType) {
            BookType.EBOOK -> book.ebookFilepath
            BookType.AUDIOBOOK -> book.audiobookFilepath
            BookType.READALOUD -> null
        } ?: return LibraryOperationResult.Rejected(
            "The selected Audiobookshelf file is no longer available",
        )
        if (filePath.split('|').none { path -> path == target.remoteRef.value }) {
            return LibraryOperationResult.Rejected(
                "The selected Audiobookshelf file changed before download",
            )
        }
        downloadMediaUseCase(
            bookUuid = request.downloadCacheId ?: book.uuid,
            bookType = bookType,
            filePath = filePath,
            bookTitle = request.displayTitle ?: book.title,
            serverId = connectionId,
        )
        return LibraryOperationResult.Accepted(request.operationId)
    }
}

private fun LibraryOperationTarget.isAudiobookshelfFile(): Boolean {
    val remoteReplica = this as? LibraryOperationTarget.RemoteReplica ?: return false
    if (remoteReplica.source.key.adapterId != LibraryAdapterId("audiobookshelf")) return false
    if (remoteReplica.resource.book != remoteReplica.source.key) return false
    val resourceId = remoteReplica.resource.nativeResourceId
    val bookId = remoteReplica.source.key.nativeBookId.value
    return remoteReplica.remoteRef.value == "/api/items/$bookId/file/$resourceId"
}
