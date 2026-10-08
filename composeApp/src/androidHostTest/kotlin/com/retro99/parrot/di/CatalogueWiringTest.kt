package com.retro99.parrot.di

import com.retro99.base.AppInitializer
import com.retro99.books.domain.StagedBookImportManager
import com.retro99.catalogue.data.CatalogueAcquisitionStartup
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.opds.api.model.OpdsBudgets
import com.retro99.server.api.CatalogueAccessStore
import com.retro99.server.api.CatalogueWorkController
import com.retro99.server.api.OpdsCredentialStore
import com.retro99.server.api.ServerCatalogueRepositoryFactory
import com.retro99.server.api.ServerRegistry
import com.retro99.server.opds.OpdsCatalogueRepositoryFactory
import com.retro99.user.api.ProfileWorkRegistry
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the running app is handed, resolved from the generated graph and not from test objects. */
class CatalogueWiringTest {

    @Test
    fun `the registry clears the same account store the catalogue requests read`() = RealAppGraph().use { graph ->
        val registry = graph.koin.get<ServerRegistry>()
        val factory = graph.koin.getAll<ServerCatalogueRepositoryFactory>().filterIsInstance<OpdsCatalogueRepositoryFactory>().single()

        assertSame(graph.koin.get<OpdsCredentialStore>(), registry.privateField("opdsCredentials"))
        assertSame(factory.privateField("credentials"), registry.privateField("opdsCredentials"))
    }

    @Test
    fun `the registry clears the same access store the catalogue requests write`() = RealAppGraph().use { graph ->
        val registry = graph.koin.get<ServerRegistry>()
        val factory = graph.koin.getAll<ServerCatalogueRepositoryFactory>().filterIsInstance<OpdsCatalogueRepositoryFactory>().single()

        assertSame(graph.koin.get<CatalogueAccessStore>(), registry.privateField("catalogueAccess"))
        assertSame(factory.privateField("access"), registry.privateField("catalogueAccess"))
    }

    @Test
    fun `the registry is handed the download queue and the catalogue sessions to cancel`() = RealAppGraph().use { graph ->
        val registry = graph.koin.get<ServerRegistry>()
        val queue = graph.koin.get<CatalogueAcquisitionManager>()

        val controllers = assertIs<List<*>>(registry.privateField("catalogueWork"))

        assertTrue(controllers.any { it === queue }, "the download queue is not among ${controllers.size} controllers")
        assertTrue(controllers.any { it is OpdsCatalogueRepositoryFactory }, "the catalogue sessions are not among the controllers")
        assertTrue(controllers.all { it is CatalogueWorkController })
    }

    @Test
    fun `a profile switch stops the work the queue and the catalogue sessions registered`() = RealAppGraph().use { graph ->
        val users = graph.koin.get<UserRegistry>()

        assertSame(graph.koin.get<ProfileWorkRegistry>(), users.privateField("profileWork"))
    }

    @Test
    fun `a checked download is added through the library's own staged import`() = RealAppGraph().use { graph ->
        val queue = graph.koin.get<CatalogueAcquisitionManager>()

        val adder = assertNotNull(queue.privateField("adder"))
        val importer = assertIs<Lazy<*>>(adder.privateField("importer"))

        assertEquals("LibraryCatalogueBookAdder", adder::class.simpleName)
        assertSame(graph.koin.get<StagedBookImportManager>(), importer.value)
    }

    @Test
    fun `the screens get the lookup for books already in the library`() {
        RealAppGraph().use { graph -> assertNotNull(graph.koin.get<CatalogueLibraryLookup>()) }
    }

    @Test
    fun `the download limit shown to the user is the limit the transport enforces`() {
        assertEquals(OpdsBudgets.MAX_DOWNLOAD_BYTES, CatalogueAcquisitionLimits.MAX_FILE_BYTES)
    }

    @Test
    fun `at app start a download that was running when Parrot closed becomes interrupted`() = RealAppGraph().use { graph ->
        runBlocking {
            withTimeout(20_000) {
                // Given: a row a dead process left as "downloading"
                val users = graph.koin.get<UserRegistry>()
                users.createProfile("a", "A", null)
                users.setActiveProfile("a")
                val session = graph.koin.get<ProfileDatabaseSession>()
                val rows = graph.koin.get<CatalogueAcquisitionsDatabase>()
                session.withProfile("a") { rows.insert(leftDownloading()) }

                // When: the app's initializers run
                assertIs<AppInitializer>(graph.koin.get<CatalogueAcquisitionStartup>()).initialize()

                // Then
                val states = graph.koin.get<CatalogueAcquisitionManager>().observeAcquisitions()
                    .first { acquisitions -> acquisitions.any { it.state == AcquisitionState.Interrupted } }
                assertEquals(listOf("left-running"), states.map { it.requestId })
            }
        }
    }

    private fun leftDownloading() = CatalogueAcquisitionEntity(
        requestId = "left-running",
        sourceId = "source",
        publicationKey = "urn:book:1",
        detailIdentity = null,
        detailUrl = "https://books.example/opds/",
        representationKey = "application/epub+zip#1",
        title = "Book",
        author = null,
        coverReference = null,
        catalogueName = "Books",
        state = CatalogueAcquisitionEntity.STATE_DOWNLOADING,
        queuePosition = 1,
        stagingPath = null,
        expectedSizeBytes = null,
        bytesSoFar = 10,
        localHash = null,
        libraryBookId = null,
        failureReason = null,
        createdAt = 1,
        updatedAt = 1,
        completedAt = null,
        attempts = 1,
    )
}
