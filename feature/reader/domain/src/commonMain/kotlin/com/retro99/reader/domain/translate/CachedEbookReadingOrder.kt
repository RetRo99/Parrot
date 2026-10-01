package com.retro99.reader.domain.translate

import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.server.api.EbookReadingOrderSource
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** A server ebook's reading order, read from its file in the reader's download cache. */
@Factory(binds = [EbookReadingOrderSource::class])
class CachedEbookReadingOrder(
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
    private val contentCache: CopyContentCache,
) : EbookReadingOrderSource {
    override suspend fun readingOrderHrefs(bookUuid: String): List<String>? {
        val path = readerSettingsRepository.getCachedMediaPath(bookUuid, BookType.EBOOK)
            ?: return null
        return contentCache.chapters(path)?.map { chapter -> chapter.href }
    }
}
