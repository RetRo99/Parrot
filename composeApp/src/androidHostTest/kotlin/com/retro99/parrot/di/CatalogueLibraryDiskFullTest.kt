package com.retro99.parrot.di

import com.github.michaelbull.result.get
import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryImportJournalDatabase
import com.retro99.server.api.CatalogueAcquisitionRepository
import com.retro99.server.api.CatalogueFeedDocument
import com.retro99.server.api.CatalogueRepositoryProvider
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.user.api.UserRegistry
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The disk fills up at the last step: the checked file cannot be moved into the library. */
class CatalogueLibraryDiskFullTest {

    @Test
    fun `a full disk during the move into the library fails as storage - leaves nothing behind - and a retry adds the book once`() {
        var full = true
        var fileRequests = 0
        val graph = RealAppGraph(libraryIsFull = { full }) { request ->
            if (request.url.encodedPath.endsWith("/one")) {
                fileRequests++
                respond(CatalogueTestEpub.BYTES, headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"))
            } else {
                respond(FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
            }
        }
        graph.use {
            runBlocking {
                withTimeout(TIMEOUT_MILLIS) {
                    // Given
                    val koin = graph.koin
                    val users = koin.get<UserRegistry>()
                    users.createProfile("a", "A", null)
                    users.setActiveProfile("a")
                    val source = koin.get<ServerRegistry>().addServerWithId("source", "Books", ServerType.Opds, ROOT)
                    val repository = assertNotNull(koin.get<CatalogueRepositoryProvider>().getRepository(source.id))
                    val document = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
                    val publication = document.publications.single()
                    val locator = assertNotNull(assertIs<CatalogueAcquisitionRepository>(repository).locate(document, publication, publication.acquisitionChoices.single()))
                    val request = CatalogueAcquisitionRequest(
                        sourceId = source.id, publicationKey = locator.publicationKey, representationKey = locator.representationKey,
                        detailIdentity = null, listingUrl = locator.documentUrl, title = "Book", author = null, coverReference = null, catalogueName = "Books",
                    )
                    val queue = koin.get<CatalogueAcquisitionManager>()
                    val session = koin.get<ProfileDatabaseSession>()
                    suspend fun settled() = queue.observeAcquisitions()
                        .first { all -> all.singleOrNull()?.state.let { it == AcquisitionState.Done || it is AcquisitionState.Failed } }
                        .single()

                    // When
                    val queued = assertIs<CatalogueRequestOutcome.Queued>(queue.request(request)).acquisition
                    val failed = settled()

                    // Then: a storage failure the user can retry
                    assertEquals(AcquisitionState.Failed(AcquisitionFailureReason.Storage), failed.state)
                    assertEquals(queued.requestId, failed.requestId)
                    assertEquals(null, failed.libraryBookId)

                    // And nothing is left: no book, no file row, no half-done import, no file in staging or the library
                    session.withProfile("a") {
                        assertTrue(koin.get<LibraryBooksDatabase>().observeLibraryBooks().first().isEmpty(), "no book row")
                        assertTrue(koin.get<LibraryImportJournalDatabase>().getAll().isEmpty(), "no import left half done")
                        assertTrue(koin.get<CatalogueBookSourcesDatabase>().getForSource(source.id).isEmpty(), "no provenance")
                    }
                    assertTrue(graph.stagingRoot.walk().none { it.isFile }, "staging: ${graph.stagingRoot.walk().filter { it.isFile }.toList()}")
                    assertTrue(graph.libraryRoot.walk().none { it.isFile }, "library: ${graph.libraryRoot.walk().filter { it.isFile }.toList()}")

                    // When: there is room again and the user taps Try again
                    full = false
                    assertTrue(queue.retry(failed.requestId))
                    val done = settled()

                    // Then: downloaded again from zero, and in the library once
                    assertEquals(AcquisitionState.Done, done.state)
                    assertEquals(2, fileRequests)
                    val bookId = assertNotNull(done.libraryBookId)
                    session.withProfile("a") {
                        assertEquals(listOf(bookId), koin.get<LibraryBooksDatabase>().observeLibraryBooks().first().map { it.libraryBookId })
                        assertEquals(1, koin.get<DeviceFilesDatabase>().getDeviceFiles(bookId).size)
                        assertEquals(1, koin.get<CatalogueBookSourcesDatabase>().getForBook(bookId).size)
                        assertTrue(koin.get<LibraryImportJournalDatabase>().getAll().isEmpty())
                    }
                    assertTrue(graph.stagingRoot.walk().none { it.isFile })
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val ROOT = "https://books.example/opds/"
        const val FEED = """{"metadata":{"title":"Books"},"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1","language":"en"},"links":[{"href":"one","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]}]}"""
    }
}
