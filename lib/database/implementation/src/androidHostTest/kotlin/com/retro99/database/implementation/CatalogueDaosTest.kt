package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity
import com.retro99.database.api.catalogue.CatalogueBookSourceEntity
import com.retro99.database.api.catalogue.CatalogueDocumentEntity
import com.retro99.database.api.catalogue.CatalogueLibraryMatch
import com.retro99.database.api.library.LibraryImportJournalEntry
import com.retro99.database.implementation.dao.catalogue.CatalogueAcquisitionsSqlDelightDao
import com.retro99.database.implementation.dao.catalogue.CatalogueBookSourcesSqlDelightDao
import com.retro99.database.implementation.dao.catalogue.CatalogueDocumentsSqlDelightDao
import com.retro99.database.implementation.dao.library.LibraryImportJournalSqlDelightDao
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogueDaosTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase
    private lateinit var acquisitions: CatalogueAcquisitionsSqlDelightDao
    private lateinit var sources: CatalogueBookSourcesSqlDelightDao
    private lateinit var documents: CatalogueDocumentsSqlDelightDao

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        acquisitions = CatalogueAcquisitionsSqlDelightDao { database }
        sources = CatalogueBookSourcesSqlDelightDao { database }
        documents = CatalogueDocumentsSqlDelightDao { database }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `an acquisition round-trips with every column`() = runBlocking {
        // Given
        val acquisition = acquisition("r1", position = 1).copy(
            detailIdentity = "urn:entry:1",
            detailUrl = "https://books.example/opds/new",
            author = "A. Writer",
            coverReference = "https://books.example/covers/1.jpg",
            stagingPath = "/staging/r1.epub.part",
            expectedSizeBytes = 1200,
            bytesSoFar = 500,
            localHash = "abc",
            libraryBookId = "lib-1",
            failureReason = "connection",
            completedAt = 99,
            attempts = 2,
            rightsText = "Public domain in the USA.",
            catalogueUpdated = "2026-10-01T00:00:00Z",
            neededBytes = 2048,
        )

        // When
        acquisitions.insert(acquisition)

        // Then
        assertEquals(acquisition, acquisitions.get("r1"))
        assertNull(acquisitions.get("missing"))
    }

    @Test
    fun `requests come back in queue order and new ones go last`() = runBlocking {
        // Given
        assertEquals(1L, acquisitions.nextQueuePosition())
        acquisitions.insert(acquisition("late", position = 7))
        acquisitions.insert(acquisition("early", position = 2))

        // When
        val all = acquisitions.getAll()

        // Then
        assertEquals(listOf("early", "late"), all.map { it.requestId })
        assertEquals(8L, acquisitions.nextQueuePosition())
    }

    @Test
    fun `the unfinished request for one file is found and a finished one is not`() = runBlocking {
        // Given
        acquisitions.insert(acquisition("done", position = 1, state = CatalogueAcquisitionEntity.STATE_DONE))
        acquisitions.insert(acquisition("failed", position = 2, state = CatalogueAcquisitionEntity.STATE_FAILED))
        acquisitions.insert(acquisition("other", position = 3).copy(representationKey = "epub#2"))

        // When
        val found = acquisitions.findUnfinished("source-1", "urn:book:1", "epub#1")

        // Then
        assertEquals("failed", found?.requestId)
        assertNull(acquisitions.findUnfinished("source-2", "urn:book:1", "epub#1"))
    }

    @Test
    fun `interrupting touches only downloading, checking and adding`() = runBlocking {
        // Given
        val states = listOf(
            CatalogueAcquisitionEntity.STATE_WAITING,
            CatalogueAcquisitionEntity.STATE_DOWNLOADING,
            CatalogueAcquisitionEntity.STATE_CHECKING,
            CatalogueAcquisitionEntity.STATE_ADDING,
            CatalogueAcquisitionEntity.STATE_DONE,
            CatalogueAcquisitionEntity.STATE_FAILED,
            CatalogueAcquisitionEntity.STATE_INTERRUPTED,
        )
        states.forEachIndexed { index, state ->
            acquisitions.insert(acquisition(state, position = index.toLong(), state = state))
        }

        // When
        val interrupted = acquisitions.interruptRunning(updatedAt = 500)

        // Then
        assertEquals(3, interrupted)
        assertEquals(
            listOf("waiting", "interrupted", "interrupted", "interrupted", "done", "failed", "interrupted"),
            acquisitions.getAll().map { it.state },
        )
        assertEquals(500L, acquisitions.get(CatalogueAcquisitionEntity.STATE_CHECKING)?.updatedAt)
        assertEquals(10L, acquisitions.get(CatalogueAcquisitionEntity.STATE_WAITING)?.updatedAt)
    }

    @Test
    fun `progress is written only while the request is downloading`() = runBlocking {
        // Given
        acquisitions.insert(acquisition("dl", position = 1, state = CatalogueAcquisitionEntity.STATE_DOWNLOADING))
        acquisitions.insert(acquisition("wait", position = 2))

        // When
        acquisitions.updateProgress("dl", bytesSoFar = 40, expectedSizeBytes = null, updatedAt = 20)
        acquisitions.updateProgress("wait", bytesSoFar = 40, expectedSizeBytes = 80, updatedAt = 20)

        // Then
        assertEquals(40L, acquisitions.get("dl")?.bytesSoFar)
        assertNull(acquisitions.get("dl")?.expectedSizeBytes)
        assertEquals(0L, acquisitions.get("wait")?.bytesSoFar)
    }

    @Test
    fun `finished requests are listed by completion time and purged by age`() = runBlocking {
        // Given
        fun done(id: String, completedAt: Long) =
            acquisition(id, position = completedAt, state = CatalogueAcquisitionEntity.STATE_DONE)
                .copy(completedAt = completedAt)
        acquisitions.insert(done("old", 100))
        acquisitions.insert(done("mid", 200))
        acquisitions.insert(done("new", 300))
        acquisitions.insert(acquisition("waiting", position = 9))

        // When
        val recent = acquisitions.getCompletedSince(200)
        acquisitions.deleteCompletedBefore(200)

        // Then
        assertEquals(listOf("new", "mid"), recent.map { it.requestId })
        assertEquals(setOf("mid", "new", "waiting"), acquisitions.getAll().map { it.requestId }.toSet())

        // When
        acquisitions.deleteCompleted()

        // Then
        assertEquals(listOf("waiting"), acquisitions.getAll().map { it.requestId })
    }

    @Test
    fun `one library book can have several provenance rows`() = runBlocking {
        // Given
        sources.insert(source("p1", book = "lib-1", sourceId = "source-1", publication = "urn:book:1", at = 1))
        sources.insert(source("p2", book = "lib-1", sourceId = "source-2", publication = "urn:x:9", at = 2))
        sources.insert(source("p3", book = "lib-2", sourceId = "source-1", publication = "urn:book:2", at = 3))
        sources.insert(source("p4", book = "lib-2", sourceId = "source-1", publication = "urn:book:2b", at = 4))

        // Then
        assertEquals(listOf("p1", "p2"), sources.getForBook("lib-1").map { it.id })
        assertEquals(listOf("p1"), sources.getForPublication("source-1", "urn:book:1").map { it.id })
        assertEquals(emptyList(), sources.getForPublication("source-2", "urn:book:1"))
        assertEquals(2L, sources.countBooksForSource("source-1"))
        assertEquals(source("p1", "lib-1", "source-1", "urn:book:1", 1), sources.getForBook("lib-1").first())
    }

    @Test
    fun `writing the same provenance row again changes nothing`() = runBlocking {
        // Given
        val first = source("request-1", book = "lib-1", sourceId = "source-1", publication = "urn:book:1", at = 1)
        sources.insertIfAbsent(first)

        // When
        sources.insertIfAbsent(first.copy(libraryBookId = "lib-other", acquiredAt = 9))

        // Then
        assertEquals(listOf(first), sources.getForSource("source-1"))
    }

    @Test
    fun `an acquired publication is found by its key or by the listing entry it came from`() = runBlocking {
        // Given: the edition urn:edition:1 was downloaded from the listing entry urn:entry:p1
        libraryBook("lib-1")
        sources.insert(source("p1", book = "lib-1", sourceId = "source-1", publication = "urn:edition:1", at = 1))

        // Then
        val match = CatalogueLibraryMatch("urn:edition:1", "urn:entry:p1", "lib-1")
        assertEquals(listOf(match), sources.findInLibrary("source-1", listOf("urn:edition:1")))
        assertEquals(listOf(match), sources.findInLibrary("source-1", listOf("urn:entry:p1")))
        assertEquals(listOf(match), sources.findInLibrary("source-1", listOf("urn:entry:p1", "urn:edition:1", "urn:x")))
        assertEquals(emptyList(), sources.findInLibrary("source-1", listOf("urn:other")))
        assertEquals(emptyList(), sources.findInLibrary("source-2", listOf("urn:edition:1")))
        assertEquals(emptyList(), sources.findInLibrary("source-1", emptyList()))
    }

    @Test
    fun `a publication whose library book is gone is not in the library`() = runBlocking {
        // Given
        libraryBook("lib-kept")
        libraryBook("lib-removed", deletedAt = "2026-10-02T00:00:00Z")
        sources.insert(source("p1", book = "lib-kept", sourceId = "source-1", publication = "urn:book:1", at = 1))
        sources.insert(source("p2", book = "lib-removed", sourceId = "source-1", publication = "urn:book:2", at = 2))
        sources.insert(source("p3", book = "lib-never", sourceId = "source-1", publication = "urn:book:3", at = 3))

        // When
        val found = sources.findInLibrary("source-1", listOf("urn:book:1", "urn:book:2", "urn:book:3"))

        // Then
        assertEquals(listOf("lib-kept"), found.map { it.libraryBookId })
    }

    @Test
    fun `a whole page of entries is looked up at once`() = runBlocking {
        // Given: more identities than fit one statement
        libraryBook("lib-1")
        sources.insert(source("p1", book = "lib-1", sourceId = "source-1", publication = "urn:book:900", at = 1))
        val page = (1..1000).map { "urn:book:$it" }

        // When
        val found = sources.findInLibrary("source-1", page)

        // Then
        assertEquals(listOf("urn:book:900"), found.map { it.publicationKey })
    }

    @Test
    fun `the import journal keeps an entry until it is cleared`() = runBlocking {
        // Given
        val journal = LibraryImportJournalSqlDelightDao { database }
        val withCover = LibraryImportJournalEntry("e1", "lib-1", "ebook", "/library/lib-1_ebook.epub", "/covers/lib-1.png", 5)
        val plain = LibraryImportJournalEntry("e2", "lib-2", "readaloud", "/library/lib-2_readaloud.epub", null, 6)

        // When
        journal.record(plain)
        journal.record(withCover)
        journal.record(withCover)

        // Then
        assertEquals(listOf(withCover, plain), journal.getAll())

        // When
        journal.clear("e1")

        // Then
        assertEquals(listOf(plain), journal.getAll())
    }

    @Test
    fun `provenance follows a merged book and goes with a deleted one`() = runBlocking {
        // Given
        sources.insert(source("p1", book = "lib-1", sourceId = "source-1", publication = "urn:book:1", at = 1))
        sources.insert(source("p2", book = "lib-2", sourceId = "source-1", publication = "urn:book:2", at = 2))

        // When
        sources.moveToBook(fromLibraryBookId = "lib-1", toLibraryBookId = "lib-2")

        // Then
        assertEquals(listOf("p1", "p2"), sources.getForBook("lib-2").map { it.id })
        assertEquals(1L, sources.countBooksForSource("source-1"))

        // When
        sources.deleteForBook("lib-2")

        // Then
        assertEquals(emptyList(), sources.getForSource("source-1"))
    }

    @Test
    fun `a saved page is keyed by source, access generation and request url`() = runBlocking {
        // Given
        val page = document("source-1", generation = 3, url = "https://books.example/opds", at = 10, bytes = 4)

        // When
        documents.upsert(page)
        documents.upsert(document("source-1", generation = 4, url = "https://books.example/opds", at = 11, bytes = 2))
        documents.upsert(document("source-1", generation = 3, url = "https://books.example/opds", at = 12, bytes = 6))

        // Then
        val stored = documents.get("source-1", 3, "https://books.example/opds")
        assertEquals(12L, stored?.storedAt)
        assertContentEquals(ByteArray(6) { it.toByte() }, stored?.payload)
        assertEquals("\"v1\"", stored?.eTag)
        assertEquals("Wed, 07 Oct 2026 10:00:00 GMT", stored?.lastModified)
        assertEquals("application/atom+xml", stored?.contentType)
        assertEquals(2L, documents.count())
        assertEquals(8L, documents.totalSizeBytes())
        assertNull(documents.get("source-2", 3, "https://books.example/opds"))
    }

    @Test
    fun `saved pages can be evicted oldest first, by source and by generation`() = runBlocking {
        // Given
        documents.upsert(document("source-1", generation = 1, url = "a", at = 30, bytes = 1))
        documents.upsert(document("source-1", generation = 2, url = "b", at = 10, bytes = 2))
        documents.upsert(document("source-2", generation = 1, url = "c", at = 20, bytes = 3))

        // Then
        assertEquals(listOf("b", "c"), documents.oldestKeys(2).map { it.requestUrl })

        // When
        documents.deleteOtherGenerations("source-1", accessGeneration = 2)

        // Then
        assertEquals(listOf("b", "c"), documents.oldestKeys(10).map { it.requestUrl })

        // When
        documents.deleteForSource("source-1")
        documents.delete("source-2", 1, "missing")

        // Then
        assertEquals(listOf("c"), documents.oldestKeys(10).map { it.requestUrl })
        assertEquals(3L, documents.totalSizeBytes())
    }

    private fun libraryBook(id: String, deletedAt: String? = null) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO library_books(library_book_id, title, added_at, deleted_at) VALUES (?, 'Book', 'a', ?)",
            parameters = 2,
        ) {
            bindString(0, id)
            bindString(1, deletedAt)
        }
    }

    private fun acquisition(
        id: String,
        position: Long,
        state: String = CatalogueAcquisitionEntity.STATE_WAITING,
    ) = CatalogueAcquisitionEntity(
        requestId = id,
        sourceId = "source-1",
        publicationKey = "urn:book:1",
        detailIdentity = null,
        detailUrl = null,
        representationKey = "epub#1",
        title = "The Lantern Ferry",
        author = null,
        coverReference = null,
        catalogueName = "Home shelf",
        state = state,
        queuePosition = position,
        stagingPath = null,
        expectedSizeBytes = null,
        bytesSoFar = 0,
        localHash = null,
        libraryBookId = null,
        failureReason = null,
        createdAt = 10,
        updatedAt = 10,
        completedAt = null,
        attempts = 0,
    )

    private fun source(id: String, book: String, sourceId: String, publication: String, at: Long) =
        CatalogueBookSourceEntity(
            id = id,
            libraryBookId = book,
            sourceId = sourceId,
            catalogueName = "Home shelf",
            catalogueOrigin = "https://books.example:443",
            publicationKey = publication,
            detailIdentity = "urn:entry:$id",
            selectedFormat = "application/epub+zip",
            rightsText = "Public domain in the USA.",
            catalogueUpdated = "2026-10-01T00:00:00Z",
            contentHash = "hash-$id",
            acquiredAt = at,
        )

    private fun document(source: String, generation: Long, url: String, at: Long, bytes: Int) =
        CatalogueDocumentEntity(
            sourceId = source,
            accessGeneration = generation,
            requestUrl = url,
            contentType = "application/atom+xml",
            eTag = "\"v1\"",
            lastModified = "Wed, 07 Oct 2026 10:00:00 GMT",
            storedAt = at,
            payload = ByteArray(bytes) { it.toByte() },
        )
}
