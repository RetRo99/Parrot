package com.retro99.server.storyteller

import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.usecase.DownloadMediaUseCase
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryOperation
import com.retro99.server.api.library.LibraryOperationAdapter
import com.retro99.server.api.library.LibraryOperationRequest
import com.retro99.server.api.library.LibraryOperationResult
import com.retro99.server.api.library.LibraryOperationTarget
import com.retro99.server.api.library.OperationAvailability
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryOperationAdapter::class])
class StorytellerLibraryOperationAdapter(
    @Provided private val downloadMediaUseCase: DownloadMediaUseCase,
) : LibraryOperationAdapter {
    override val adapterId = LibraryAdapterId("storyteller")

    override fun supportsReaderCache(
        target: LibraryOperationTarget.RemoteReplica,
    ): Boolean = target.toStorytellerDownload() != null

    override fun readerCacheBookId(
        target: LibraryOperationTarget.RemoteReplica,
    ): String? = null

    override suspend fun availability(
        target: LibraryOperationTarget,
    ): List<OperationAvailability> {
        val downloadable = target.toStorytellerDownload() != null
        return listOf(
            OperationAvailability(
                operation = LibraryOperation.Download,
                isAvailable = downloadable,
                reason = if (downloadable) null else "A Storyteller file resource is required",
            ),
        )
    }

    override suspend fun execute(
        request: LibraryOperationRequest,
    ): LibraryOperationResult {
        if (request.operation != LibraryOperation.Download) {
            return LibraryOperationResult.Rejected(
                "Storyteller only supports native media downloads",
            )
        }
        val download = request.target.toStorytellerDownload()
            ?: return LibraryOperationResult.Rejected(
                "The selected Storyteller file resource is no longer available",
            )
        val connectionId = request.target.source.connectionId?.value
            ?: return LibraryOperationResult.Rejected(
                "The Storyteller connection is no longer available",
            )
        downloadMediaUseCase(
            bookUuid = request.downloadCacheId
                ?: request.target.source.key.nativeBookId.value,
            bookType = download.bookType,
            filePath = download.filePath,
            bookTitle = request.displayTitle ?: request.target.source.key.nativeBookId.value,
            serverId = connectionId,
        )
        return LibraryOperationResult.Accepted(request.operationId)
    }
}

private data class StorytellerDownload(
    val bookType: BookType,
    val filePath: String,
)

private fun LibraryOperationTarget.toStorytellerDownload(): StorytellerDownload? {
    val remoteReplica = this as? LibraryOperationTarget.RemoteReplica ?: return null
    if (remoteReplica.source.key.adapterId != LibraryAdapterId("storyteller")) return null
    if (remoteReplica.resource.book != remoteReplica.source.key) return null

    val filePath = remoteReplica.remoteRef.value
    if (remoteReplica.resource.nativeResourceId != filePath) return null
    val bookId = remoteReplica.source.key.nativeBookId.value
    val bookType = BookType.entries.firstOrNull { candidate ->
        filePath == "/api/v2/books/$bookId/files?format=${candidate.value}"
    } ?: return null
    return StorytellerDownload(bookType = bookType, filePath = filePath)
}
