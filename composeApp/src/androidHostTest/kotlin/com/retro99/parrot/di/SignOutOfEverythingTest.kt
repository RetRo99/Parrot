package com.retro99.parrot.di

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.auth.domain.usecase.LogoutUseCase
import com.retro99.books.domain.BookFileOrigin
import com.retro99.books.domain.StagedBookFile
import com.retro99.books.domain.StagedBookImportManager
import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.database.api.DataClearable
import com.retro99.database.api.DatabaseCleaner
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.database.api.favorites.FavoritesDatabase
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryImportJournalDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.server.api.CatalogueAccountEditor
import com.retro99.server.api.CatalogueAcquisitionRepository
import com.retro99.server.api.CatalogueFeedDocument
import com.retro99.server.api.CatalogueRepositoryProvider
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.OpdsCredentialStore
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.user.api.UserRegistry
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Sign out of everything" in the app's own graph. A book downloaded from a catalogue must
 * come out of it in the same condition as a book picked from this device's files.
 */
class SignOutOfEverythingTest {

    /**
     * Pins today's behavior before sign-out is changed. The generated graph gives the cleaner
     * `getAll<DataClearable>()`, and no table is bound under that type, so it clears nothing.
     * Whether it should is a product decision; this run does not make it.
     */
    @Test
    fun `the app's cleaner is handed no tables, so clearing all data changes nothing for a picked book`() = inGraph { graph ->
        // Given a picked book that was read, with its details not sent yet
        val koin = graph.koin
        val bookId = pickBook(graph, "picked bytes")
        readAndFavourite(koin, bookId)
        val before = condition(koin, bookId)
        assertEquals(READ_AND_UNSENT.copy(isFavorite = true), before)

        // When
        val cleaner = koin.get<DatabaseCleaner>()
        koin.get<ProfileDatabaseSession>().withProfile(PROFILE) { cleaner.clearAllData() }

        // Then
        assertEquals(emptyList<Any?>(), cleaner.privateField("dataClearables"))
        assertEquals(before, condition(koin, bookId))
    }

    /** What the eight tables written as clearable would remove, if the cleaner were handed them. */
    @Test
    fun `the clearable tables, cleared as written, keep a picked book and its file and drop its position, favourite and unsent changes`() = inGraph { graph ->
        // Given
        val koin = graph.koin
        val bookId = pickBook(graph, "picked bytes")
        readAndFavourite(koin, bookId)

        // When
        val tables = koin.get<List<DataClearable>>()
        koin.get<ProfileDatabaseSession>().withProfile(PROFILE) { tables.forEach { table -> table.clearAllData() } }

        // Then
        assertEquals(8, tables.size)
        assertEquals(
            BookCondition(inLibrary = true, fileRows = 1, fileOnDisk = true, coverOnDisk = true, unsentChanges = 0, hasPosition = false, isFavorite = false),
            condition(koin, bookId),
        )
    }

    @Test
    fun `signing out of everything leaves a catalogue book in the same condition as a picked book`() {
        // Given the same library server data in a profile without a catalogue and in one with
        val withoutCatalogue = signOutOfEverything(withCatalogue = false)
        val withCatalogue = signOutOfEverything(withCatalogue = true)

        // Then the library server is treated as it is in a profile that has no catalogue
        assertEquals(withoutCatalogue.serverData, withCatalogue.serverData)
        assertEquals(withoutCatalogue.picked, withCatalogue.picked)

        // And the catalogue book came out exactly like the picked one
        assertEquals(withCatalogue.picked, assertNotNull(withCatalogue.downloaded))

        // And today that means untouched, because the cleaner clears nothing (see the first test)
        assertEquals(READ_AND_UNSENT, withCatalogue.picked)
        assertEquals(ServerData(positionKept = true, favouriteKept = true), withCatalogue.serverData)
    }

    private data class ServerData(val positionKept: Boolean, val favouriteKept: Boolean)
    private class SignedOut(val serverData: ServerData, val picked: BookCondition, val downloaded: BookCondition?)

    private fun signOutOfEverything(withCatalogue: Boolean): SignedOut {
        lateinit var result: SignedOut
        inGraph(respond = { path -> if (path.endsWith("/one")) Answer.Book else Answer.Feed }) { graph ->
            // Given a library server with cached data, a picked book and (with a catalogue) a downloaded book, all read
            val koin = graph.koin
            val registry = koin.get<ServerRegistry>()
            registry.addServerWithId("storyteller", "Storyteller", ServerType.Storyteller, "https://storyteller.example")
            val pickedId = pickBook(graph, "picked bytes")
            val done = if (withCatalogue) download(koin, registry.addServerWithId("public", "Books", ServerType.Opds, ROOT)) else null
            if (done != null) assertEquals(AcquisitionState.Done, done.state)
            val catalogueId = done?.libraryBookId
            val session = koin.get<ProfileDatabaseSession>()
            session.withProfile(PROFILE) {
                val positions = koin.get<PositionDatabase>()
                listOfNotNull(pickedId, catalogueId).forEach { id -> positions.upsertPosition(TestPosition(id, libraryBookId = id)) }
                positions.upsertPosition(TestPosition(SERVER_BOOK, libraryBookId = null))
                koin.get<FavoritesDatabase>().addFavorite(SERVER_BOOK)
            }
            assertEquals(READ_AND_UNSENT, condition(koin, pickedId))
            catalogueId?.let { id -> assertEquals(READ_AND_UNSENT, condition(koin, id)) }

            // When
            assertTrue(koin.get<LogoutUseCase>().logoutAll().isOk)

            // Then the catalogue, the record of where the book came from and the finished download stay
            val expectedSources = listOfNotNull("storyteller", "public".takeIf { withCatalogue }).toSet()
            assertEquals(expectedSources, registry.getAllServers().filter { it.type != ServerType.Local }.map(ServerConfig::id).toSet())
            val serverData = session.withProfile(PROFILE) {
                if (done != null && catalogueId != null) {
                    assertEquals(listOf(catalogueId), koin.get<CatalogueBookSourcesDatabase>().getForBook(catalogueId).map { it.libraryBookId })
                    assertEquals(listOf(done.requestId), koin.get<CatalogueAcquisitionsDatabase>().getAll().map { it.requestId })
                }
                assertTrue(koin.get<LibraryImportJournalDatabase>().getAll().isEmpty())
                ServerData(
                    positionKept = koin.get<PositionDatabase>().getPositionByBookUuid(SERVER_BOOK) != null,
                    favouriteKept = koin.get<FavoritesDatabase>().isFavorite(SERVER_BOOK),
                )
            }
            result = SignedOut(serverData, condition(koin, pickedId), catalogueId?.let { id -> condition(koin, id) })
        }
        return result
    }

    private suspend fun readAndFavourite(koin: Koin, bookId: String) {
        koin.get<ProfileDatabaseSession>().withProfile(PROFILE) {
            koin.get<PositionDatabase>().upsertPosition(TestPosition(bookId, libraryBookId = bookId))
            koin.get<FavoritesDatabase>().addFavorite(bookId)
        }
    }

    @Test
    fun `signing out removes the unfinished downloads of a catalogue that had account details and keeps the others`() = inGraph(
        respond = { path ->
            when {
                path.startsWith("/private/") && path.endsWith("/one") -> Answer.SignIn
                path.endsWith("/one") -> Answer.Missing
                else -> Answer.Feed
            }
        },
    ) { graph ->
        // Given one catalogue with account details and one without, each with a download that did not finish
        val koin = graph.koin
        val registry = koin.get<ServerRegistry>()
        val private = registry.addServerWithId("private", "Private", ServerType.Opds, "https://books.example/private/")
        val public = registry.addServerWithId("public", "Public", ServerType.Opds, ROOT)
        koin.get<CatalogueAccountEditor>().saveAccount(private.id, OpdsAccountDetails("patron", "secret"))
        assertEquals(AcquisitionState.Failed(AcquisitionFailureReason.SignIn), download(koin, private).state)
        val publicRequest = download(koin, public)
        assertEquals(AcquisitionState.Failed(AcquisitionFailureReason.Refused), publicRequest.state)

        // When
        assertTrue(koin.get<LogoutUseCase>().logoutAll().isOk)

        // Then
        assertNull(koin.get<OpdsCredentialStore>().get(PROFILE, private.id))
        assertEquals(
            listOf(publicRequest.requestId),
            koin.get<CatalogueAcquisitionManager>().observeAcquisitions().first().map { it.requestId },
        )
        assertEquals(setOf("private", "public"), registry.getAllServers().filter { it.type == ServerType.Opds }.map(ServerConfig::id).toSet())
    }

    private data class BookCondition(
        val inLibrary: Boolean,
        val fileRows: Int,
        val fileOnDisk: Boolean,
        val coverOnDisk: Boolean,
        val unsentChanges: Int,
        val hasPosition: Boolean,
        val isFavorite: Boolean,
    )

    private suspend fun condition(koin: Koin, bookId: String): BookCondition =
        koin.get<ProfileDatabaseSession>().withProfile(PROFILE) {
            val book = koin.get<LibraryBooksDatabase>().getLibraryBookById(bookId)
            val files = koin.get<DeviceFilesDatabase>().getDeviceFiles(bookId)
            BookCondition(
                inLibrary = book != null && book.deletedAt == null,
                fileRows = files.size,
                fileOnDisk = files.isNotEmpty() && files.all { file -> File(file.filePath).exists() },
                coverOnDisk = book?.coverPath?.let { path -> File(path).exists() } == true,
                unsentChanges = koin.get<SyncOutboxDatabase>().getPendingIncludingUnassigned("no-cloud-user")
                    .count { entry -> entry.entityId == bookId },
                hasPosition = koin.get<PositionDatabase>().getPositionByBookUuid(bookId) != null,
                isFavorite = koin.get<FavoritesDatabase>().isFavorite(bookId),
            )
        }

    /** A book added the way the file picker adds one. */
    private suspend fun pickBook(graph: RealAppGraph, content: String): String {
        val picked = File(graph.libraryRoot, "picked/${content.hashCode()}.epub").apply { parentFile.mkdirs() }
        picked.writeBytes(content.toByteArray())
        val result = graph.koin.get<ProfileDatabaseSession>().withProfile(PROFILE) {
            graph.koin.get<StagedBookImportManager>()
                .importStagedEpub(StagedBookFile(picked.absolutePath, BookFileOrigin.Import))
        }
        return assertNotNull(result.get(), "import failed: ${result.getError()}").libraryBookId
    }

    /** Requests the catalogue's one book and waits until the request stops moving. */
    private suspend fun download(koin: Koin, source: ServerConfig): CatalogueAcquisition {
        val repository = assertNotNull(koin.get<CatalogueRepositoryProvider>().getRepository(source.id))
        val document = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val publication = document.publications.single()
        val locator = assertNotNull(
            assertIs<CatalogueAcquisitionRepository>(repository)
                .locate(document, publication, publication.acquisitionChoices.single()),
        )
        val queue = koin.get<CatalogueAcquisitionManager>()
        val queued = assertIs<CatalogueRequestOutcome.Queued>(
            queue.request(
                CatalogueAcquisitionRequest(
                    sourceId = source.id,
                    publicationKey = locator.publicationKey,
                    representationKey = locator.representationKey,
                    detailIdentity = null,
                    listingUrl = locator.documentUrl,
                    title = "Book",
                    author = null,
                    coverReference = null,
                    catalogueName = source.name,
                ),
            ),
        ).acquisition
        return queue.observeAcquisitions().first { all ->
            all.first { it.requestId == queued.requestId }.state.let { it == AcquisitionState.Done || it is AcquisitionState.Failed }
        }.first { it.requestId == queued.requestId }
    }

    private enum class Answer { Feed, Book, SignIn, Missing }

    private fun inGraph(
        respond: (path: String) -> Answer = { Answer.Feed },
        test: suspend (RealAppGraph) -> Unit,
    ) {
        val graph = RealAppGraph { request ->
            when (respond(request.url.encodedPath)) {
                Answer.Feed -> respond(FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
                Answer.Book -> respond(CatalogueTestEpub.BYTES, headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"))
                Answer.SignIn -> respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"books\""))
                Answer.Missing -> respond("", HttpStatusCode.NotFound)
            }
        }
        graph.use {
            runBlocking {
                withTimeout(TIMEOUT_MILLIS) {
                    val users = graph.koin.get<UserRegistry>()
                    users.createProfile(PROFILE, "A", null)
                    users.setActiveProfile(PROFILE)
                    test(graph)
                }
            }
        }
    }

    internal class TestPosition(
        override val bookUuid: String,
        override val libraryBookId: String?,
    ) : PositionEntity {
        override val timestamp: Long? = 1L
        override val createdAt: String? = "2026-10-08T00:00:00Z"
        override val updatedAt: String? = "2026-10-08T00:00:00Z"
        override val locatorHref: String? = "chapter1.xhtml"
        override val locatorType: String? = "application/xhtml+xml"
        override val locatorTitle: String? = null
        override val locatorTarget: Int? = null
        override val audioTimestampMs: Long? = null
        override val chapterIndex: Int? = 0
        override val progression: Double? = 0.5
        override val totalChapters: Int? = 1
        override val totalDurationMs: Long? = null
        override val totalProgression: Double? = 0.5
        override val position: Int? = 1
    }

    private companion object {
        val READ_AND_UNSENT = BookCondition(inLibrary = true, fileRows = 1, fileOnDisk = true, coverOnDisk = true, unsentChanges = 1, hasPosition = true, isFavorite = false)
        const val PROFILE = "a"
        const val SERVER_BOOK = "server-book"
        const val TIMEOUT_MILLIS = 30_000L
        const val ROOT = "https://books.example/opds/"
        const val FEED = """{"metadata":{"title":"Books"},"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1","language":"en"},"links":[{"href":"one","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]}]}"""
    }
}
