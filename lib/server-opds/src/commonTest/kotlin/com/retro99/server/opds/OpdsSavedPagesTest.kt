package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.retro99.base.result.AppError
import com.retro99.server.api.*
import com.retro99.server.implementation.*
import com.retro99.user.implementation.ProfileWorkRegistryImpl
import com.retro99.user.implementation.UserRegistryImpl
import io.ktor.client.engine.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

/** The saved copy of a page: when it is shown, to whom, and when it goes. Registry and factory are the real ones. */
class OpdsSavedPagesTest {

    @Test fun `an unreachable catalogue shows the saved copy with the time it was saved`() = runTest {
        // Given a page opened while the catalogue could be reached
        val world = World()
        world.clock = 100
        val source = world.addSource()
        val online = assertIs<CatalogueFeedDocument>(world.factory.create(source).getRoot().get())
        assertNull(online.fetchStatus.savedCopyAt)
        assertFalse(online.fetchStatus.isSavedCopy)

        // When the device is offline, in a new session as after a restart
        world.offline = true
        world.clock = 500
        world.restart()
        val saved = assertIs<CatalogueFeedDocument>(world.factory.create(source).getRoot().get())

        // Then
        assertTrue(saved.fetchStatus.isSavedCopy)
        assertEquals(100L, saved.fetchStatus.savedCopyAt)
        assertEquals(500L, saved.fetchStatus.checkedAt)
        assertEquals(online.metadata, saved.metadata)
        assertEquals(online.publications.map { it.title }, saved.publications.map { it.title })
        assertEquals(CatalogueErrorKind.Unreachable, world.checks.get("a", source.id).lastCheck.lastError)
    }

    @Test fun `an unreachable catalogue with no saved copy is a result of its own`() = runTest {
        // Given
        val world = World()
        val source = world.addSource()
        val repository = world.factory.create(source)
        val feed = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        world.offline = true

        // When a page that was never opened is asked for
        val error = repository.getDocument(feed.navigation.single().links.single().target!!).getError()

        // Then
        assertEquals(CatalogueErrorKind.OfflineNoSavedCopy.name, assertIs<AppError.ApiError>(error).message)
        assertEquals(CatalogueErrorKind.Unreachable, world.checks.get("a", source.id).lastCheck.lastError)
    }

    @Test fun `a catalogue that answers with an error is not served from the saved copy`() = runTest {
        // Given
        val world = World()
        val source = world.addSource()
        val repository = world.factory.create(source)
        assertTrue(repository.getRoot().isOk)

        // When
        world.status = HttpStatusCode.InternalServerError

        // Then
        assertEquals(CatalogueErrorKind.ServerError.name, assertIs<AppError.ApiError>(repository.getRoot().getError()).message)
    }

    @Test fun `a saved page is checked again when the catalogue can be reached`() = runTest {
        // Given
        val world = World()
        world.eTag = "v1"
        val source = world.addSource()
        assertTrue(world.factory.create(source).getRoot().isOk)

        // When
        world.restart()
        world.notModified = true
        val again = assertIs<CatalogueFeedDocument>(world.factory.create(source).getRoot().get())

        // Then the validator went out and the saved bytes were used, as a live page
        assertEquals("v1", world.requests.last().headers[HttpHeaders.IfNoneMatch])
        assertTrue(again.fetchStatus.fromCache)
        assertFalse(again.fetchStatus.isSavedCopy)
    }

    @Test fun `a no-store page is not saved`() = runTest {
        // Given
        val world = World()
        world.cacheControl = "no-store"
        val source = world.addSource()
        val repository = world.factory.create(source)

        // When
        assertTrue(repository.getRoot().isOk)

        // Then
        assertEquals(emptyList(), world.documents.peek("a"))
        world.offline = true
        assertEquals(CatalogueErrorKind.OfflineNoSavedCopy.name, assertIs<AppError.ApiError>(repository.getRoot().getError()).message)
    }

    @Test fun `a page fetched with account details is not shown after the details change`() = runTest {
        // Given a page fetched as "patron"
        val world = World()
        val source = world.addSource()
        world.registry.saveAccount(source.id, OpdsAccountDetails("patron", "secret"))
        assertTrue(world.factory.create(source).getRoot().isOk)
        val savedAs = world.documents.peek("a").single().accessGeneration
        assertEquals(world.accounts.accessGeneration("a", source.id), savedAs)

        // When someone else's details are saved and the device is offline
        world.registry.saveAccount(source.id, OpdsAccountDetails("other", "secret"))
        world.offline = true

        // Then
        assertEquals(emptyList(), world.documents.peek("a"))
        assertNotEquals(savedAs, world.accounts.accessGeneration("a", source.id))
        val error = world.factory.create(source).getRoot().getError()
        assertEquals(CatalogueErrorKind.OfflineNoSavedCopy.name, assertIs<AppError.ApiError>(error).message)
    }

    @Test fun `even a page left behind under old account details is never served under new ones`() = runTest {
        // Given: the page of the old details is still in the table, as after a write that lost a race
        val world = World()
        val source = world.addSource()
        world.accounts.save("a", source.id, OpdsAccountDetails("patron", "secret"))
        assertTrue(world.factory.create(source).getRoot().isOk)
        world.accounts.save("a", source.id, OpdsAccountDetails("other", "secret"))
        assertEquals(1, world.documents.peek("a").size)

        // When
        world.offline = true
        val error = world.factory.create(source).getRoot().getError()

        // Then
        assertEquals(CatalogueErrorKind.OfflineNoSavedCopy.name, assertIs<AppError.ApiError>(error).message)
    }

    @Test fun `removing account details - turning off and removing a catalogue clear its saved pages and no other catalogue's`() = runTest {
        for (change in listOf<suspend (World, ServerConfig) -> Unit>(
            { world, source -> world.registry.clearCredentials(source.id) },
            { world, source -> world.registry.deactivateServer(source.id) },
            { world, source -> world.registry.removeServer(source.id) },
            { world, source -> world.registry.updateServer(source.copy(baseUrl = "https://elsewhere.example/opds/")) },
        )) {
            // Given two catalogues with a saved page each, one of them with account details
            val world = World()
            val source = world.addSource()
            val other = world.addSource("other")
            world.registry.saveAccount(source.id, OpdsAccountDetails("patron", "secret"))
            assertTrue(world.factory.create(source).getRoot().isOk)
            assertTrue(world.factory.create(other).getRoot().isOk)
            assertEquals(setOf("source", "other"), world.documents.peek("a").map { it.sourceId }.toSet())

            // When
            change(world, source)

            // Then
            assertEquals(listOf("other"), world.documents.peek("a").map { it.sourceId })
        }
    }

    @Test fun `signing out of a catalogue that has no account details keeps its saved pages`() = runTest {
        // Given
        val world = World()
        val source = world.addSource()
        assertTrue(world.factory.create(source).getRoot().isOk)

        // When
        world.registry.clearAllCredentials()

        // Then
        assertEquals(listOf("source"), world.documents.peek("a").map { it.sourceId })
    }

    @Test fun `a profile that closes keeps its saved pages and another profile never sees them`() = runTest {
        // Given
        val world = World()
        world.users.createProfile("b", "B", null)
        val source = world.addSource()
        assertTrue(world.factory.create(source).getRoot().isOk)

        // When profile b, with the same catalogue, is opened offline
        world.users.setActiveProfile("b")
        val sameInB = world.registry.addServerWithId(source.id, "Books", ServerType.Opds, ROOT)
        world.offline = true
        val error = world.factory.create(sameInB).getRoot().getError()

        // Then
        assertEquals(CatalogueErrorKind.OfflineNoSavedCopy.name, assertIs<AppError.ApiError>(error).message)
        assertEquals(1, world.documents.peek("a").size)
        assertEquals(emptyList(), world.documents.peek("b"))

        // And back in profile a the saved copy is still there
        world.users.setActiveProfile("a")
        assertTrue(assertIs<CatalogueFeedDocument>(world.factory.create(source).getRoot().get()).fetchStatus.isSavedCopy)
    }

    private class World {
        val preferences = TestPreferences()
        val profileWork = ProfileWorkRegistryImpl()
        val users = UserRegistryImpl(preferences, profileWork)
        val accounts = OpdsCredentialStoreImpl(preferences)
        val checks = CatalogueAccessStoreImpl(preferences)
        val documents = MemoryDocuments { users.getActiveProfileId() }
        var clock = 10L
        var offline = false
        var notModified = false
        var status = HttpStatusCode.OK
        var eTag: String? = null
        var cacheControl: String? = null
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        private val engines = object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine { request ->
                requests += request
                when {
                    offline -> throw IOException("Unable to resolve host")
                    notModified -> respond("", HttpStatusCode.NotModified)
                    status != HttpStatusCode.OK -> respond("", status)
                    else -> respond(
                        OpdsCatalogueRepositoryTest.FEED,
                        headers = headers {
                            append(HttpHeaders.ContentType, "application/opds+json")
                            eTag?.let { append(HttpHeaders.ETag, it) }
                            cacheControl?.let { append(HttpHeaders.CacheControl, it) }
                        },
                    )
                }
            }
        }
        var factory = newFactory()
            private set
        val registry = ServerRegistryImpl(
            preferences, users, emptyList(), accounts, checks,
            listOf(object : CatalogueWorkController {
                override suspend fun cancel(profileId: String, sourceId: String) = factory.cancel(profileId, sourceId)
                override suspend fun forget(profileId: String, sourceId: String) = factory.forget(profileId, sourceId)
            }),
        )

        private fun newFactory() =
            OpdsCatalogueRepositoryFactory(engines, users, accounts, checks, preferences, profileWork, documents.session, documents)
                .also { factory -> factory.now = { clock } }

        /** Parrot was closed and opened again: sessions are gone, preferences and the database are not. */
        fun restart() {
            factory = newFactory()
        }

        suspend fun addSource(id: String = "source"): ServerConfig {
            if (users.getActiveProfileId() == null) {
                users.createProfile("a", "A", null)
                users.setActiveProfile("a")
            }
            return registry.addServerWithId(id, "Books", ServerType.Opds, ROOT)
        }
    }

    private companion object {
        const val ROOT = OpdsCatalogueRepositoryTest.ROOT
    }
}
