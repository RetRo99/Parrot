package com.retro99.parrot.di

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.base.result.AppError
import com.retro99.catalogue.data.CatalogueAcquisitionStartup
import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueEntryIdentity
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.database.api.catalogue.CatalogueDocumentsDatabase
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.server.api.CatalogueAccessStore
import com.retro99.server.api.CatalogueAccessStatus
import com.retro99.server.api.CatalogueAccountEditor
import com.retro99.server.api.CatalogueAcquisitionRepository
import com.retro99.server.api.CatalogueErrorKind
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
import kotlinx.coroutines.delay
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

/** What goes and what stays when a book, a file, a catalogue or a profile is removed, in the app's own graph. */
class CatalogueCleanupRulesTest {

    @Test
    fun `a deleted book loses its catalogue records and can be downloaded again`() = inGraph { graph ->
        // Given
        val koin = graph.koin
        val source = koin.get<ServerRegistry>().addServerWithId("source", "Books", ServerType.Opds, ROOT)
        val first = download(koin, source)
        val bookId = assertNotNull(first.libraryBookId)
        val session = koin.get<ProfileDatabaseSession>()

        // When
        session.withProfile(PROFILE) { koin.get<LibraryBooksDatabase>().deleteBookFromDevice(bookId) }

        // Then
        session.withProfile(PROFILE) {
            assertTrue(koin.get<CatalogueBookSourcesDatabase>().getForBook(bookId).isEmpty())
            assertTrue(koin.get<CatalogueAcquisitionsDatabase>().getAll().isEmpty())
        }

        // And downloading it again gives a book again
        val second = download(koin, source)
        assertEquals(AcquisitionState.Done, second.state)
        val newBookId = assertNotNull(second.libraryBookId)
        assertEquals(newBookId, koin.get<CatalogueLibraryLookup>().libraryBookFor(source.id, CatalogueEntryIdentity(second.publicationKey)))
        assertEquals(2, graph.fileRequests)
    }

    @Test
    fun `a book kept without its file is downloaded again into the same book and keeps its progress`() = inGraph { graph ->
        // Given a downloaded book that was read, and then lost its file on this device
        val koin = graph.koin
        val source = koin.get<ServerRegistry>().addServerWithId("source", "Books", ServerType.Opds, ROOT)
        val first = download(koin, source)
        val bookId = assertNotNull(first.libraryBookId)
        val entry = CatalogueEntryIdentity(first.publicationKey)
        val session = koin.get<ProfileDatabaseSession>()
        session.withProfile(PROFILE) {
            koin.get<PositionDatabase>().upsertPosition(SignOutOfEverythingTest.TestPosition(bookId, libraryBookId = bookId))
            val file = koin.get<DeviceFilesDatabase>().getDeviceFiles(bookId).single()
            koin.get<DeviceFilesDatabase>().deleteDeviceFile(bookId, file.mediaType)
            assertTrue(File(file.filePath).delete())
        }
        val lookup = koin.get<CatalogueLibraryLookup>()
        assertNull(lookup.libraryBookFor(source.id, entry), "must not block downloading again")

        // When the catalogue still serves the same bytes
        val again = download(koin, source)

        // Then the file is back on the same book, which kept its position
        assertEquals(AcquisitionState.Done, again.state)
        assertEquals(bookId, again.libraryBookId)
        session.withProfile(PROFILE) {
            val file = koin.get<DeviceFilesDatabase>().getDeviceFiles(bookId).single()
            assertTrue(File(file.filePath).readBytes().contentEquals(CatalogueTestEpub.BYTES))
            assertEquals(1, koin.get<LibraryBooksDatabase>().countLibraryBooksWithDeviceFiles())
            assertNotNull(koin.get<PositionDatabase>().getPositionByBookUuid(bookId))
            assertEquals(1, koin.get<CatalogueBookSourcesDatabase>().getForBook(bookId).size)
        }
        assertEquals(bookId, lookup.libraryBookFor(source.id, entry))
    }

    @Test
    fun `a removed catalogue takes its unfinished downloads, account and status and leaves its books`() = inGraph { graph ->
        // Given a catalogue with account details, one book from it, and a download that did not finish
        val koin = graph.koin
        val registry = koin.get<ServerRegistry>()
        val source = registry.addServerWithId("source", "Books", ServerType.Opds, ROOT)
        koin.get<CatalogueAccountEditor>().saveAccount(source.id, OpdsAccountDetails("patron", "secret"))
        val bookId = assertNotNull(download(koin, source).libraryBookId)
        graph.refuseFiles = true
        val unfinished = download(koin, source, book = 1)
        assertEquals(AcquisitionState.Failed(AcquisitionFailureReason.SignIn), unfinished.state)

        val pages = koin.get<CatalogueDocumentsDatabase>()
        assertEquals(1L, koin.get<ProfileDatabaseSession>().withProfile(PROFILE) { pages.count() })

        // When
        registry.removeServer(source.id)

        // Then everything that belonged to the catalogue is gone
        assertEquals(0L, koin.get<ProfileDatabaseSession>().withProfile(PROFILE) { pages.count() })
        assertNull(registry.getServer(source.id))
        assertNull(koin.get<OpdsCredentialStore>().get(PROFILE, source.id))
        assertEquals(CatalogueAccessStatus(), koin.get<CatalogueAccessStore>().get(PROFILE, source.id))
        assertTrue(graph.stagingRoot.walk().none { it.isFile })
        val session = koin.get<ProfileDatabaseSession>()
        session.withProfile(PROFILE) {
            assertEquals(listOf(AcquisitionState.Done.key), koin.get<CatalogueAcquisitionsDatabase>().getAll().map { it.state })

            // And the book stays, with a record that does not need the catalogue to exist
            assertNotNull(koin.get<LibraryBooksDatabase>().getLibraryBookById(bookId))
            assertTrue(File(koin.get<DeviceFilesDatabase>().getDeviceFiles(bookId).single().filePath).exists())
            val provenance = koin.get<CatalogueBookSourcesDatabase>().getForBook(bookId).single()
            assertEquals("Books", provenance.catalogueName)
            assertEquals("https://books.example:443", provenance.catalogueOrigin)
            assertEquals(source.id, provenance.sourceId)
        }
    }

    @Test
    fun `a page that was opened is shown as a saved copy when the catalogue cannot be reached`() = inGraph { graph ->
        // Given a page opened online
        val koin = graph.koin
        val source = koin.get<ServerRegistry>().addServerWithId("source", "Books", ServerType.Opds, ROOT)
        val provider = koin.get<CatalogueRepositoryProvider>()
        val online = assertIs<CatalogueFeedDocument>(assertNotNull(provider.getRepository(source.id)).getRoot().get())
        assertNull(online.fetchStatus.savedCopyAt)
        val session = koin.get<ProfileDatabaseSession>()
        val saved = session.withProfile(PROFILE) { koin.get<CatalogueDocumentsDatabase>().oldestKeys(10) }.single()
        assertEquals(source.id, saved.sourceId)
        assertEquals(ROOT, saved.requestUrl)
        assertEquals(koin.get<OpdsCredentialStore>().accessGeneration(PROFILE, source.id), saved.accessGeneration)

        // When
        graph.offline = true
        val offline = assertIs<CatalogueFeedDocument>(assertNotNull(provider.getRepository(source.id)).getRoot().get())

        // Then
        assertNotNull(offline.fetchStatus.savedCopyAt)
        assertEquals(online.publications.map { it.title }, offline.publications.map { it.title })

        // And once account details are saved, that copy is gone
        koin.get<CatalogueAccountEditor>().saveAccount(source.id, OpdsAccountDetails("patron", "secret"))
        assertEquals(0L, session.withProfile(PROFILE) { koin.get<CatalogueDocumentsDatabase>().count() })
        val error = assertNotNull(provider.getRepository(source.id)).getRoot().getError()
        assertEquals(CatalogueErrorKind.OfflineNoSavedCopy.name, assertIs<AppError.ApiError>(error).message)
    }

    @Test
    fun `a deleted profile's staging folder is removed`() = inGraph { graph ->
        // Given a second profile with a file in staging, and the app started
        val koin = graph.koin
        val users = koin.get<UserRegistry>()
        users.createProfile("b", "B", null)
        val staged = File(graph.stagingRoot, "b/left-behind.epub.part").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        val mine = File(graph.stagingRoot, "$PROFILE/mine.epub.part").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(1)) }
        koin.get<CatalogueAcquisitionStartup>().initialize()

        // When
        users.deleteProfile("b")

        // Then
        while (staged.parentFile.exists()) delay(10)
        assertTrue(mine.exists())
    }

    /** Requests one book of the catalogue and waits until the request stops moving. */
    private suspend fun download(koin: Koin, source: ServerConfig, book: Int = 0): CatalogueAcquisition {
        val repository = assertNotNull(koin.get<CatalogueRepositoryProvider>().getRepository(source.id))
        val document = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val publication = document.publications[book]
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

    private class Graph(val real: RealAppGraph, private val counters: Counters) {
        val koin get() = real.koin
        val stagingRoot get() = real.stagingRoot
        val fileRequests get() = counters.fileRequests
        var refuseFiles: Boolean
            get() = counters.refuseFiles
            set(value) { counters.refuseFiles = value }
        var offline: Boolean
            get() = counters.offline
            set(value) { counters.offline = value }
    }

    private class Counters {
        @Volatile var fileRequests = 0
        @Volatile var refuseFiles = false
        @Volatile var offline = false
    }

    private fun inGraph(test: suspend (Graph) -> Unit) {
        val counters = Counters()
        val real = RealAppGraph { request ->
            val path = request.url.encodedPath
            when {
                counters.offline -> throw java.io.IOException("Unable to resolve host")
                !path.endsWith("/one") && !path.endsWith("/two") ->
                    respond(FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
                counters.refuseFiles ->
                    respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"books\""))
                else -> {
                    counters.fileRequests++
                    respond(CatalogueTestEpub.BYTES, headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"))
                }
            }
        }
        real.use {
            runBlocking {
                withTimeout(TIMEOUT_MILLIS) {
                    val users = real.koin.get<UserRegistry>()
                    users.createProfile(PROFILE, "A", null)
                    users.setActiveProfile(PROFILE)
                    test(Graph(real, counters))
                }
            }
        }
    }

    private companion object {
        const val PROFILE = "a"
        const val TIMEOUT_MILLIS = 30_000L
        const val ROOT = "https://books.example/opds/"
        const val FEED = """{"metadata":{"title":"Books"},"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1","language":"en"},"links":[{"href":"one","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]},{"metadata":{"title":"Other","identifier":"urn:book:other","language":"en"},"links":[{"href":"two","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]}]}"""
    }
}
