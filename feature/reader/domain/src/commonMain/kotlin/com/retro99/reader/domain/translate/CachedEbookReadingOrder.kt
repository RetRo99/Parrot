package com.retro99.reader.domain.translate

import com.github.michaelbull.result.get
import com.retro99.books.domain.model.BookType
import com.retro99.epub.api.EpubSpineReader
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.server.api.EbookReadingOrderSource
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * A server ebook's full spine, read from its file in the reader's download cache. It includes
 * `linear="no"` items (a cover, notes), because EPUB CFIs count them; the text reading order
 * ([CopyContentCache]) leaves them out.
 */
@Factory(binds = [EbookReadingOrderSource::class])
class CachedEbookReadingOrder(
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
    @Provided private val spineReader: EpubSpineReader,
) : EbookReadingOrderSource {
    override suspend fun readingOrderHrefs(bookUuid: String): List<String>? {
        val path = readerSettingsRepository.getCachedMediaPath(bookUuid, BookType.EBOOK)
            ?: return null
        return spineReader.readSpineHrefs(path).get()
    }
}
