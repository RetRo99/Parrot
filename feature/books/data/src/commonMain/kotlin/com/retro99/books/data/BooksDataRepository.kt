package com.retro99.books.data

import com.retro99.base.repository.BaseRepository
import com.retro99.base.result.AppResult
import com.retro99.base.result.mapCatching
import com.retro99.books.data.model.toDomain
import com.retro99.books.data.model.toLocal
import com.retro99.books.data.source.BooksLocalSource
import com.retro99.books.data.source.BooksRemoteSource
import com.retro99.books.domain.BooksRepository
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import retro99.network.api.BaseUrlProvider

@Single(binds = [BooksRepository::class])
internal class BooksDataRepository(
    @Provided private val remoteSource: BooksRemoteSource,
    @Provided private val localSource: BooksLocalSource,
    @Provided private val baseUrlProvider: BaseUrlProvider,
) : BooksRepository, BaseRepository {

    override fun getBooks(): Flow<AppResult<List<BookDomainModel.StorytellerBook>>> {
        val baseUrl = baseUrlProvider.getBaseUrl()
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBooks().mapCatching { books ->
                    books?.map { it.toDomain(baseUrl) }
                }
            },
            remoteSource = {
                remoteSource.getBooks().mapCatching { books ->
                    books.map { it.toDomain(baseUrl) }
                        .sortedBy { it.title.lowercase() }
                }
            },
            saveToCache = { domainBooks ->
                localSource.saveBooks(domainBooks.map { it.toLocal() })
            },
        )
    }

    override fun getBook(uuid: String): Flow<AppResult<BookDomainModel.StorytellerBook>> {
        val baseUrl = baseUrlProvider.getBaseUrl()
        return cachedRemoteFlow(
            cacheSource = {
                localSource.getBook(uuid).mapCatching { book ->
                    book?.toDomain(baseUrl)
                }
            },
            remoteSource = {
                remoteSource.getBook(uuid).mapCatching { book ->
                    book.toDomain(baseUrl)
                }
            },
            saveToCache = { domainBook ->
                localSource.saveBook(domainBook.toLocal())
            },
        )
    }
}
