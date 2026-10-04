package com.retro99.reader.data.recap

import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.database.api.books.BooksDatabase
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.links.BookLinksDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow

interface RecapIdentity {
    suspend fun cloudId(bookId: String): String?
    suspend fun cloudBooks(): List<String>
    fun observeProgression(bookId: String): Flow<Double?>
}

/** Reuses library IDs, position mapping and portable book-link copy keys. */
class RecapBookIdentity(
    private val library: LibraryBooksDatabase,
    private val links: BookLinksDatabase,
    private val books: BooksDatabase,
    private val positions: PositionDatabase,
) : RecapIdentity {
    override fun observeProgression(bookId: String) = positions.observePositionByBookUuid(bookId).map { it?.totalProgression }
    override suspend fun cloudId(bookId: String): String? {
        val libraryId = positions.getPositionByBookUuid(bookId)?.libraryBookId ?: bookId
        library.getLibraryBookById(libraryId)?.takeIf { it.remoteRevision != null && it.deletedAt == null }
            ?.let { return it.libraryBookId }
        val book = books.getBookByUuid(bookId) ?: return null
        val source = when (book.serverType) {
            "storyteller" -> CopySource.Storyteller
            "audiobookshelf" -> CopySource.Audiobookshelf
            else -> return null
        }
        val key = CopyKey(source, bookId).value
        val linkedId = links.getLinks().firstOrNull { key in it.members }?.members
            ?.mapNotNull(CopyKey::parse)?.firstOrNull { it.source == CopySource.Library }?.id ?: return null
        return library.getLibraryBookById(linkedId)?.takeIf { it.deletedAt == null && it.remoteRevision != null }?.libraryBookId
    }

    override suspend fun cloudBooks(): List<String> = library.observeLibraryBooks().first()
        .filter { it.deletedAt == null && it.remoteRevision != null }.map { it.libraryBookId }
}
