package com.retro99.parrot.di

import com.github.michaelbull.result.get
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueEntryIdentity
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.database.api.library.LibraryImportJournalDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One catalogue book from the network to the library, through the app's own graph: the
 * registered catalogue, the queue, the file check, the library import and the real schema.
 * Only the network, the disk locations and the Android EPUB reader are stand-ins.
 */
class CatalogueDownloadToLibraryTest {

    @Test
    fun `a downloaded book becomes one library book with provenance and is then in your library`() {
        var fileRequests = 0
        val graph = RealAppGraph { request ->
            if (request.url.encodedPath.endsWith("/one")) {
                fileRequests++
                respond(EPUB, headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"))
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
                    val locator = assertNotNull(
                        assertIs<CatalogueAcquisitionRepository>(repository)
                            .locate(document, publication, publication.acquisitionChoices.single()),
                    )
                    val request = CatalogueAcquisitionRequest(
                        sourceId = source.id,
                        publicationKey = locator.publicationKey,
                        representationKey = locator.representationKey,
                        detailIdentity = "urn:listing:entry",
                        listingUrl = locator.documentUrl,
                        title = "Book",
                        author = null,
                        coverReference = null,
                        catalogueName = "Books",
                        rightsText = "Rights",
                    )
                    val queue = koin.get<CatalogueAcquisitionManager>()

                    // When
                    val queued = assertIs<CatalogueRequestOutcome.Queued>(queue.request(request)).acquisition
                    val done = queue.observeAcquisitions()
                        .first { all -> all.single().state.let { it == AcquisitionState.Done || it is AcquisitionState.Failed } }
                        .single()

                    // Then: the request is done and names its book
                    assertEquals(AcquisitionState.Done, done.state)
                    val bookId = assertNotNull(done.libraryBookId)

                    // And the library has one book with one file, from a catalogue, with the sync entry an import gets
                    val session = koin.get<ProfileDatabaseSession>()
                    session.withProfile("a") {
                        val book = assertNotNull(koin.get<LibraryBooksDatabase>().getLibraryBookById(bookId))
                        assertEquals("A Catalogue Book", book.title)
                        val file = koin.get<DeviceFilesDatabase>().getDeviceFiles(bookId).single()
                        assertEquals(DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD, file.origin)
                        assertEquals(done.localHash, file.contentHash)
                        assertTrue(File(file.filePath).readBytes().contentEquals(EPUB))
                        assertTrue(File(assertNotNull(book.coverPath)).exists())
                        val pending = koin.get<SyncOutboxDatabase>().getPendingIncludingUnassigned("no-cloud-user").single()
                        assertEquals(SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK, pending.entityType)
                        assertEquals(bookId, pending.entityId)
                        assertTrue(koin.get<LibraryImportJournalDatabase>().getAll().isEmpty())

                        // And one provenance row, naming the catalogue by origin only
                        val provenance = koin.get<CatalogueBookSourcesDatabase>().getForBook(bookId).single()
                        assertEquals(queued.requestId, provenance.id)
                        assertEquals("https://books.example:443", provenance.catalogueOrigin)
                        assertEquals(locator.publicationKey, provenance.publicationKey)
                        assertEquals("urn:listing:entry", provenance.detailIdentity)
                        assertEquals("application/epub+zip", provenance.selectedFormat)
                        assertEquals("Rights", provenance.rightsText)
                        assertEquals(done.localHash, provenance.contentHash)
                    }
                    assertTrue(graph.stagingRoot.walk().none { it.isFile }, "the staged file was consumed")

                    // And asking again, by the book or by the listing entry, starts nothing
                    assertEquals(CatalogueRequestOutcome.InLibrary(bookId), queue.request(request))
                    assertEquals(
                        CatalogueRequestOutcome.InLibrary(bookId),
                        queue.request(request.copy(publicationKey = "urn:listing:entry", detailIdentity = null)),
                    )
                    assertEquals(1, fileRequests)
                    val lookup = koin.get<CatalogueLibraryLookup>()
                    val entry = CatalogueEntryIdentity(locator.publicationKey)
                    assertEquals(mapOf(entry to bookId), lookup.libraryBooksFor(source.id, listOf(entry, CatalogueEntryIdentity("urn:other"))))

                    // And once the book is deleted from the device it is not "in your library"
                    session.withProfile("a") { koin.get<LibraryBooksDatabase>().deleteBookFromDevice(bookId) }
                    assertNull(lookup.libraryBookFor(source.id, entry))
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val ROOT = "https://books.example/opds/"
        const val FEED = """{"metadata":{"title":"Books"},"publications":[{"metadata":{"title":"Book","identifier":"urn:book:1","language":"en"},"links":[{"href":"one","rel":"http://opds-spec.org/acquisition/open-access","type":"application/epub+zip"}]}]}"""

        /** The smallest file the EPUB check accepts: mimetype first and stored, a container, a package, one chapter. */
        val EPUB: ByteArray = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                val mimetype = "application/epub+zip".toByteArray()
                zip.putNextEntry(
                    ZipEntry("mimetype").apply {
                        method = ZipEntry.STORED
                        size = mimetype.size.toLong()
                        compressedSize = mimetype.size.toLong()
                        crc = CRC32().apply { update(mimetype) }.value
                    },
                )
                zip.write(mimetype)
                zip.closeEntry()
                mapOf(
                    "META-INF/container.xml" to """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                    "OEBPS/content.opf" to """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:book:1</dc:identifier><dc:title>A Catalogue Book</dc:title><dc:language>en</dc:language></metadata><manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""",
                    "OEBPS/chapter1.xhtml" to """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>Text.</p></body></html>""",
                ).forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }
}
