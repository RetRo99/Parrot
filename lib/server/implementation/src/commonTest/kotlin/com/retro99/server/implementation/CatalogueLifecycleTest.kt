package com.retro99.server.implementation

import com.retro99.server.api.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueLifecycleTest {
    @Test fun turn_off_keeps_account_and_source_and_cancels_work() = runTest {
        val fixture = Fixture()
        val source = fixture.add()
        fixture.registry.deactivateServer(source.id)
        assertEquals(source.copy(enabled = false), fixture.registry.getServer(source.id))
        assertNotNull(fixture.credentials.get("a", source.id))
        assertEquals(listOf(source.id), fixture.cancelled)
    }
    @Test fun remove_account_keeps_enabled_source_and_rechecks_access_on_next_request() = runTest {
        val fixture = Fixture()
        val source = fixture.add()
        fixture.status.recordSuccess("a", source.id, "patron", 10)
        fixture.registry.clearCredentials(source.id)
        assertEquals(source, fixture.registry.getServer(source.id))
        assertNull(fixture.credentials.get("a", source.id))
        // Last verified access is historical until a real request, never a synthetic login.
        assertEquals(ServerAccessState.SignedIn("patron"), fixture.status.get("a", source.id).access)
        fixture.status.recordSuccess("a", source.id, null, 20)
        assertEquals(ServerAccessState.Public, fixture.status.get("a", source.id).access)
        fixture.status.recordFailure("a", source.id, CatalogueErrorKind.SignInNeeded, 30, true)
        assertEquals(ServerAccessState.SignInNeeded, fixture.status.get("a", source.id).access)
    }
    @Test fun remove_catalogue_clears_account_work_and_persisted_status_without_book_mutations() = runTest {
        val fixture = Fixture()
        val source = fixture.add()
        fixture.status.recordFailure("a", source.id, CatalogueErrorKind.SignInNeeded, 10, true)
        fixture.registry.removeServer(source.id)
        assertNull(fixture.registry.getServer(source.id))
        assertNull(fixture.credentials.get("a", source.id))
        assertEquals(CatalogueAccessStatus(), fixture.status.get("a", source.id))
        assertEquals(listOf(source.id), fixture.cancelled)
        assertTrue(ServerRegistryImpl(fixture.preferences, RegistryUser("a"), emptyList()).getAllServers().isEmpty())
    }
    @Test fun existing_server_deactivation_still_only_clears_bearer_credentials() = runTest {
        val fixture = Fixture()
        val source = fixture.registry.addServerWithId("library", "Library", ServerType.Storyteller, "https://library.example")
        fixture.registry.saveCredentials(ServerCredentials(source.id, "user", "token"))
        fixture.registry.deactivateServer(source.id)
        assertEquals(source, fixture.registry.getServer(source.id))
        assertNull(fixture.registry.getCredentials(source.id))
        assertTrue(fixture.cancelled.isEmpty())
    }
    @Test fun registration_cannot_retarget_an_existing_catalogue_id() = runTest {
        val fixture = Fixture()
        val source = fixture.add()
        assertFailsWith<IllegalStateException> { fixture.registry.addServerWithId(source.id, "Other", ServerType.Opds, "https://other.example/") }
        assertEquals(source, fixture.registry.getServer(source.id))
        assertNotNull(fixture.credentials.get("a", source.id))
    }

    private class Fixture {
        val preferences = RegistryPreferences()
        val credentials = OpdsCredentialStoreImpl(preferences)
        val status = CatalogueAccessStoreImpl(preferences)
        val cancelled = mutableListOf<String>()
        val controller = object : CatalogueWorkController { override suspend fun cancel(profileId: String, sourceId: String) { cancelled += sourceId } }
        val registry = ServerRegistryImpl(preferences, RegistryUser("a"), emptyList(), credentials, status, listOf(controller))
        suspend fun add(): ServerConfig {
            val source = registry.addServerWithId("source", "Books", ServerType.Opds, "https://books.example/opds/")
            credentials.save("a", source.id, OpdsAccountDetails("patron", ""))
            return source
        }
    }
}
