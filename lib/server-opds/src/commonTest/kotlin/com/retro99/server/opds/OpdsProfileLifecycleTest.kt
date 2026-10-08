package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.retro99.preferences.api.*
import com.retro99.server.api.*
import com.retro99.server.implementation.*
import com.retro99.user.implementation.UserRegistryImpl
import com.retro99.user.implementation.ProfileWorkRegistryImpl
import com.retro99.user.api.UserRegistry
import io.ktor.client.engine.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.*

class OpdsProfileLifecycleTest {
    @Test fun registry_disable_remove_account_and_remove_source_have_distinct_real_request_effects() = runTest {
        val preferences = TestPreferences()
        val profileWork = ProfileWorkRegistryImpl()
        val users = UserRegistryImpl(preferences, profileWork)
        users.createProfile("a", "A", null)
        users.setActiveProfile("a")
        val accounts = OpdsCredentialStoreImpl(preferences)
        val checks = CatalogueAccessStoreImpl(preferences)
        var requiresAccount = false
        val engines = object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine { request ->
                if (requiresAccount && request.headers[HttpHeaders.Authorization] == null) respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=books"))
                else respond(OpdsCatalogueRepositoryTest.FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
            }
        }
        val factory = OpdsCatalogueRepositoryFactory(engines, users, accounts, checks, preferences, profileWork)
        val registry = ServerRegistryImpl(preferences, users, emptyList(), accounts, checks, listOf(factory))
        val source = registry.addServerWithId("source", "Books", ServerType.Opds, OpdsCatalogueRepositoryTest.ROOT)
        accounts.save("a", source.id, OpdsAccountDetails("patron", ""))
        val signedIn = factory.create(source)
        assertTrue(signedIn.getRoot().isOk)
        registry.deactivateServer(source.id)
        assertNotNull(accounts.get("a", source.id))
        assertFalse(assertNotNull(registry.getServer(source.id)).enabled)
        assertFailsWith<CancellationException> { signedIn.getRoot() }
        registry.updateServer(source)
        registry.clearCredentials(source.id)
        assertEquals(source, registry.getServer(source.id))
        assertNull(accounts.get("a", source.id))
        assertEquals(ServerAccessState.SignedIn("patron"), checks.get("a", source.id).access)
        assertTrue(factory.create(source).getRoot().isOk)
        assertEquals(ServerAccessState.Public, checks.get("a", source.id).access)
        requiresAccount = true
        val public = factory.create(source)
        assertTrue(public.getRoot().isErr)
        assertEquals(ServerAccessState.SignInNeeded, checks.get("a", source.id).access)
        registry.removeServer(source.id)
        assertNull(registry.getServer(source.id))
        assertEquals(CatalogueAccessStatus(), checks.get("a", source.id))
        assertFailsWith<CancellationException> { public.getRoot() }
    }

    @Test fun profile_switch_cancels_catalogue_work_before_returning_even_if_observers_are_delayed() = runTest {
        val preferences = TestPreferences()
        val profileWork = ProfileWorkRegistryImpl()
        val actualUsers = UserRegistryImpl(preferences, profileWork)
        actualUsers.createProfile("a", "A", null)
        actualUsers.createProfile("b", "B", null)
        actualUsers.setActiveProfile("a")
        val users = object : UserRegistry by actualUsers { override fun observeActiveProfile() = emptyFlow<com.retro99.user.api.UserProfile?>() }
        val source = OpdsCatalogueRepositoryTest.SOURCE
        preferences.putObject(PreferencesKey.UserScoped("a", PreferencesKey.CatalogueSources.name), listOf(source))
        val entered = CompletableDeferred<Unit>()
        val engines = object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine { entered.complete(Unit); awaitCancellation() }
        }
        val checks = CatalogueAccessStoreImpl(preferences)
        val factory = OpdsCatalogueRepositoryFactory(engines, users, OpdsCredentialStoreImpl(preferences), checks, preferences, profileWork)
        val repository = factory.create(source)
        val request = launch { repository.getRoot() }
        entered.await()
        actualUsers.setActiveProfile("b")
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(CatalogueAccessStatus(), checks.get("a", source.id))
    }

    @Test fun profile_switch_cannot_reuse_private_cache_credentials_or_old_references() = runTest {
        val preferences = TestPreferences()
        val profileWork = ProfileWorkRegistryImpl()
        val users = UserRegistryImpl(preferences, profileWork)
        users.createProfile("a", "A", null)
        users.createProfile("b", "B", null)
        users.setActiveProfile("a")
        val source = OpdsCatalogueRepositoryTest.SOURCE
        for (profile in listOf("a", "b")) preferences.putObject(PreferencesKey.UserScoped(profile, PreferencesKey.CatalogueSources.name), listOf(source))
        val accounts = OpdsCredentialStoreImpl(preferences)
        val checks = CatalogueAccessStoreImpl(preferences)
        accounts.save("a", source.id, OpdsAccountDetails("a", "secret"))
        var calls = 0
        val engines = object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = MockEngine { request ->
                calls++
                assertNull(request.headers[HttpHeaders.IfNoneMatch], "Another profile's validators must not be reused")
                if (users.getActiveProfileId() == "a") assertNotNull(request.headers[HttpHeaders.Authorization]) else assertNull(request.headers[HttpHeaders.Authorization])
                respond(OpdsCatalogueRepositoryTest.FEED, headers = headersOf(HttpHeaders.ContentType to listOf("application/opds+json"), HttpHeaders.ETag to listOf("private-a")))
            }
        }
        val factory = OpdsCatalogueRepositoryFactory(engines, users, accounts, checks, preferences, profileWork)
        val a = factory.create(source)
        val feedA = assertIs<CatalogueFeedDocument>(a.getRoot().get())
        users.setActiveProfile("b")
        assertFailsWith<CancellationException> { a.getRoot() }
        val b = factory.create(source)
        assertTrue(b.getRoot().isOk)
        assertTrue(b.getDocument(feedA.navigation.single().links.single().target!!).isErr)
        assertEquals(2, calls)
        assertEquals(ServerAccessState.Public, checks.get("b", source.id).access)
        factory.cancel("b", source.id)
        assertFailsWith<CancellationException> { b.getRoot() }
        users.deleteProfile("a")
        assertNull(accounts.get("a", source.id))
        assertEquals(CatalogueAccessStatus(), checks.get("a", source.id))
    }

    @Test fun factory_rejects_unsupported_unregistered_and_disabled_sources_before_creating_an_engine() = runTest {
        val preferences = TestPreferences()
        val profileWork = ProfileWorkRegistryImpl()
        val users = UserRegistryImpl(preferences, profileWork)
        users.createProfile("a", "A", null)
        users.setActiveProfile("a")
        val engine = object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = error("Must not create an engine")
        }
        val factory = OpdsCatalogueRepositoryFactory(engine, users, OpdsCredentialStoreImpl(preferences), CatalogueAccessStoreImpl(preferences), preferences, profileWork)
        val source = OpdsCatalogueRepositoryTest.SOURCE
        assertFailsWith<IllegalArgumentException> { factory.create(source.copy(type = ServerType.Local)) }
        assertFailsWith<IllegalArgumentException> { factory.create(source.copy(enabled = false)) }
        assertFailsWith<IllegalStateException> { factory.create(source) }
    }
}
