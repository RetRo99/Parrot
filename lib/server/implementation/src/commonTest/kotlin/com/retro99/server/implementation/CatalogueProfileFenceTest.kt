package com.retro99.server.implementation

import com.retro99.server.api.*
import com.retro99.user.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueProfileFenceTest {
    @Test fun synchronous_lookups_and_mutations_do_not_wait_for_profile_observer() = runTest {
        var active = "a"
        val users = object : UserRegistry by RegistryUser("a") {
            override fun getActiveProfileId() = active
            override fun observeActiveProfile(): Flow<UserProfile?> = emptyFlow()
        }
        val preferences = RegistryPreferences()
        val accounts = OpdsCredentialStoreImpl(preferences)
        val registry = ServerRegistryImpl(preferences, users, emptyList(), accounts, CatalogueAccessStoreImpl(preferences), emptyList())
        val a = registry.addServerWithId("same", "A", ServerType.Opds, "https://a.example/opds/")
        accounts.save("a", a.id, OpdsAccountDetails("a", "secret"))
        active = "b"
        assertTrue(registry.getAllServers().isEmpty())
        val b = registry.addServerWithId("same", "B", ServerType.Opds, "https://b.example/opds/")
        accounts.save("b", b.id, OpdsAccountDetails("b", ""))
        registry.clearCredentials(b.id)
        assertNotNull(accounts.get("a", a.id))
        assertNull(accounts.get("b", b.id))
        assertEquals(b, registry.getServer("same"))
        active = "a"
        assertEquals(a, registry.getServer("same"))
    }
}
