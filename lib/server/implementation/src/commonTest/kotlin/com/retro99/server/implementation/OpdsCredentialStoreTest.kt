package com.retro99.server.implementation

import com.retro99.server.api.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class OpdsCredentialStoreTest {
    @Test fun path_edit_keeps_password_but_origin_edits_clear_it_and_all_edits_invalidate_work() = runTest {
        val preferences = RegistryPreferences()
        val store = OpdsCredentialStoreImpl(preferences)
        val cancelled = mutableListOf<String>()
        val work = object : CatalogueWorkController {
            override suspend fun cancel(profileId: String, sourceId: String) { cancelled += "$profileId:$sourceId" }
        }
        val registry = ServerRegistryImpl(preferences, RegistryUser("a"), emptyList(), store, CatalogueAccessStoreImpl(preferences), listOf(work))
        var source = registry.addServerWithId("source", "Books", ServerType.Opds, "https://books.example/opds/")
        val details = OpdsAccountDetails("patron", "")
        store.save("a", "source", details)
        source = source.copy(baseUrl = "https://books.example/other/?library=2")
        registry.updateServer(source)
        assertEquals(details, store.get("a", "source"))
        for (address in listOf("http://books.example/other/", "https://other.example/opds/", "https://other.example:8443/opds/")) {
            store.save("a", "source", details)
            source = source.copy(baseUrl = address)
            registry.updateServer(source)
            assertNull(store.get("a", "source"))
        }
        assertEquals(List(4) { "a:source" }, cancelled)
    }

    @Test fun credentials_are_typed_profile_and_source_scoped_and_empty_password_survives_restart() = runTest {
        val preferences = RegistryPreferences()
        val store = OpdsCredentialStoreImpl(preferences)
        val credentials = OpdsAccountDetails("patron@example.com", "")
        store.save("a", "source", credentials)
        assertEquals(credentials, OpdsCredentialStoreImpl(preferences).get("a", "source"))
        assertNull(store.get("b", "source"))
        assertNull(store.get("a", "other"))
        store.remove("a", "source")
        assertNull(store.get("a", "source"))
    }

    @Test fun access_generation_changes_with_every_account_change_survives_restart_and_is_never_reused() = runTest {
        val preferences = RegistryPreferences()
        val store = OpdsCredentialStoreImpl(preferences)
        val seen = mutableListOf(store.accessGeneration("a", "source"))
        suspend fun changed(change: suspend () -> Unit) {
            change()
            val generation = store.accessGeneration("a", "source")
            assertFalse(generation in seen, "generation $generation was used before: $seen")
            seen += generation
        }
        changed { store.save("a", "source", OpdsAccountDetails("patron", "one")) }
        changed { store.save("a", "source", OpdsAccountDetails("patron", "two")) }
        changed { store.remove("a", "source") }
        changed { store.save("a", "source", OpdsAccountDetails("patron", "one")) }

        // The same details again, or nothing to remove, is no change.
        store.save("a", "source", OpdsAccountDetails("patron", "one"))
        store.remove("a", "never-had-details")
        assertEquals(seen.last(), store.accessGeneration("a", "source"))
        assertEquals(0L, store.accessGeneration("a", "never-had-details"))

        // Another source and another profile count on their own.
        store.save("a", "other", OpdsAccountDetails("patron", ""))
        store.save("b", "source", OpdsAccountDetails("patron", ""))
        assertEquals(seen.last(), store.accessGeneration("a", "source"))
        assertFalse(store.accessGeneration("a", "other") in seen)

        // A restart reads the same number.
        assertEquals(seen.last(), OpdsCredentialStoreImpl(preferences).accessGeneration("a", "source"))
    }

    @Test fun catalogue_password_cannot_be_saved_as_a_bearer_session() = runTest {
        val registry = registryWithOwnStores(RegistryPreferences(), RegistryUser("a"))
        registry.addServerWithId("source", "Books", ServerType.Opds, "https://books.example/opds/")
        assertFailsWith<IllegalStateException> {
            registry.saveCredentials(ServerCredentials("source", "password", "patron"))
        }
        assertFalse(registry.isAuthenticated("source"))
        assertNull(registry.getCredentials("source"))
    }
}
