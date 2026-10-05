package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.MediaResource
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ResolveSavedBookOpenUseCaseTest {
    @Test
    fun `opens a downloaded library ebook without prompting to download`() = runTest {
        val book = ServerBook(
            uuid = "book-1",
            serverId = "local",
            title = "Downloaded book",
            description = null,
            coverUrl = null,
            authors = emptyList(),
            narrators = emptyList(),
            series = emptyList(),
            tags = emptyList(),
            hasEbook = true,
            hasAudiobook = false,
            hasReadaloud = false,
            isLocal = true,
            serverType = ServerType.Local,
            libraryBookId = "book-1",
            mediaResources = listOf(MediaResource("ebook", localPath = "/books/book-1.epub")),
        )
        val provider = object : AuthenticatedRepositoryProvider {
            private val repository = object : ServerBooksRepository {
                override val serverId = "local"
                override fun getBooks(): Flow<AppResult<List<ServerBook>>> = flowOf(Ok(listOf(book)))
                override fun getBook(uuid: String): Flow<AppResult<ServerBook>> = flowOf(Ok(book))
                override suspend fun saveBook(book: ServerBook): CompletableResult = error("unused")
                override suspend fun searchBooks(query: String): AppResult<List<ServerBook>> = error("unused")
            }

            override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> = flowOf(listOf(repository))
            override suspend fun getBooksRepositories(): List<ServerBooksRepository> = listOf(repository)
            override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? = repository
            override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? = null
            override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> = flowOf(emptyList())
            override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
        }
        val links = object : BookLinksRepository {
            override fun observeLinks(): Flow<List<BookLink>> = flowOf(emptyList())
            override fun observeDecisions(): Flow<Map<String, LinkDecision>> = flowOf(emptyMap())
            override suspend fun link(first: CopyKey, second: CopyKey): AppResult<BookLink> = error("unused")
            override suspend fun unlink(copy: CopyKey): CompletableResult = error("unused")
            override suspend fun decide(
                first: CopyKey,
                second: CopyKey,
                decision: LinkDecisionType,
            ): CompletableResult = error("unused")
        }
        val readerSettings = object : ReaderSettingsRepository {
            override suspend fun prepareEbook(
                bookUuid: String,
                ebookFilePath: String,
                bookType: BookType,
            ): AppResult<String> = error("unused")
            override fun getReaderSettings(): Flow<ReaderSettingsDomainModel> = error("unused")
            override suspend fun saveReaderSettings(settings: ReaderSettingsDomainModel): CompletableResult = error("unused")
            override fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>> = error("unused")
            override suspend fun saveCustomFonts(fonts: List<CustomReaderFontDomainModel>): CompletableResult = error("unused")
            override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean = false
            override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean = false
            override fun getCurrentlyReading(): CurrentlyReadingDomainModel? = null
            override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> = flowOf(null)
            override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) = Unit
            override fun clearCurrentlyReading() = Unit
            override suspend fun getAllPositions(): AppResult<List<PositionDomainModel>> = error("unused")
        }
        val useCase = ResolveSavedBookOpenUseCase(GetBooksUseCase(provider, links), readerSettings)

        assertEquals(
            SavedBookOpenTarget.Open("local", "book-1", BookType.EBOOK),
            useCase("library:book-1"),
        )
    }
}
