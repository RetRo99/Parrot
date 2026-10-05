package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.links.copyKey
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.reader.domain.ReaderSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** How to open a saved item's book on this device. */
sealed interface SavedBookOpenTarget {
    data class Open(val serverId: String, val bookUuid: String, val bookType: BookType) : SavedBookOpenTarget

    /** The book is known here but its file isn't downloaded. */
    data class Download(val serverId: String, val bookUuid: String, val title: String) : SavedBookOpenTarget

    /** No copy of this book on this device's servers. */
    data object Unavailable : SavedBookOpenTarget
}

/** What a saved list shows for a book: its title and cover, when this device knows it. */
data class SavedBookInfo(
    val key: String,
    val title: String,
    val coverUrl: String?,
)

@Factory
class ResolveSavedBookOpenUseCase(
    @Provided private val getBooksUseCase: GetBooksUseCase,
    @Provided private val readerRepository: ReaderSettingsRepository,
) {
    suspend operator fun invoke(bookKey: String): SavedBookOpenTarget {
        val book = books().firstOrNull { candidate -> candidate.copyKey().value == bookKey }
            ?: return SavedBookOpenTarget.Unavailable
        // Library books open directly from their device_files path; they aren't in the
        // reader's download cache. Server books, in contrast, use that reader cache.
        val openableType = when (book) {
            is BookDomainModel.LibraryBook -> listOf(BookType.READALOUD, BookType.EBOOK)
                .firstOrNull { type -> book.deviceFilePath(type) != null }
            is BookDomainModel.StorytellerBook -> listOf(BookType.READALOUD, BookType.EBOOK)
                .firstOrNull { type ->
                    runCatching { readerRepository.isEbookCached(book.uuid, type) }.getOrDefault(false)
                }
        }
        return if (openableType != null) {
            SavedBookOpenTarget.Open(book.serverId, book.uuid, openableType)
        } else {
            SavedBookOpenTarget.Download(book.serverId, book.uuid, book.title)
        }
    }

    /** Title and cover of every book this device knows, by copy key. */
    fun observeBookInfo(): Flow<Map<String, SavedBookInfo>> =
        getBooksUseCase(groupLinked = false).map { result ->
            result.getOrElse { emptyList() }.associate { book ->
                val key = book.copyKey().value
                key to SavedBookInfo(key = key, title = book.title, coverUrl = book.coverUrl)
            }
        }

    private suspend fun books(): List<BookDomainModel> =
        getBooksUseCase(groupLinked = false).first().getOrElse { emptyList() }
}
