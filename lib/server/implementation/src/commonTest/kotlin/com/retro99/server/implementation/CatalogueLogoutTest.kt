package com.retro99.server.implementation

import com.retro99.server.api.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueLogoutTest {
    @Test fun logout_all_clears_catalogue_accounts_and_their_work_but_keeps_sources() = runTest {
        val preferences = RegistryPreferences()
        val credentials = OpdsCredentialStoreImpl(preferences)
        val cancelled = mutableListOf<String>()
        val controller = object : CatalogueWorkController {
            override suspend fun cancel(profileId: String, sourceId: String) { cancelled += "$profileId:$sourceId" }
        }
        val registry = ServerRegistryImpl(preferences, RegistryUser("a"), emptyList(), credentials, CatalogueAccessStoreImpl(preferences), listOf(controller))
        val public = registry.addServerWithId("public", "Public", ServerType.Opds, "https://books.example/public/")
        val private = registry.addServerWithId("private", "Private", ServerType.Opds, "https://books.example/private/")
        credentials.save("a", private.id, OpdsAccountDetails("patron", ""))
        credentials.save("b", private.id, OpdsAccountDetails("other", "password"))
        registry.clearAllCredentials()
        assertNull(credentials.get("a", private.id))
        assertNotNull(credentials.get("b", private.id))
        // A catalogue that never had account details has nothing private to lose.
        assertEquals(listOf("a:private"), cancelled)
        assertEquals(setOf(public, private), registry.getAllServers().toSet())
        assertTrue(registry.getAuthenticatedServers().isEmpty())
    }
}
