package com.retro99.server.implementation

import com.retro99.server.api.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CatalogueAccessStoreTest {
    @Test fun public_is_not_library_authentication_and_checks_survive_restart() = runTest {
        val preferences = RegistryPreferences()
        val store = CatalogueAccessStoreImpl(preferences)
        assertEquals(ServerAccessState.Public, store.get("a", "source").access)
        store.recordSuccess("a", "source", username = null, at = 10)
        store.recordFailure("a", "source", CatalogueErrorKind.SignInNeeded, at = 20, rootAnswered401 = true)
        val restored = CatalogueAccessStoreImpl(preferences).get("a", "source")
        assertEquals(ServerAccessState.SignInNeeded, restored.access)
        assertEquals(10L, restored.lastCheck.lastSuccessAt)
        assertEquals(CatalogueErrorKind.SignInNeeded, restored.lastCheck.lastError)
        assertEquals(20L, restored.lastCheck.lastErrorAt)
        assertTrue(restored.rootAnswered401)
        assertEquals(ServerAccessState.Public, store.get("b", "source").access)
    }

    @Test fun connectivity_failure_does_not_sign_out_and_success_clears_root_challenge() = runTest {
        val store = CatalogueAccessStoreImpl(RegistryPreferences())
        store.recordSuccess("a", "source", "Patron", 10)
        store.recordFailure("a", "source", CatalogueErrorKind.Unreachable, 20, false)
        assertEquals(ServerAccessState.SignedIn("Patron"), store.get("a", "source").access)
        store.recordFailure("a", "source", CatalogueErrorKind.SignInUnsupported, 30, true)
        assertEquals(ServerAccessState.SignInUnsupported, store.get("a", "source").access)
        store.recordSuccess("a", "source", null, 40, isRoot = true)
        assertEquals(ServerAccessState.Public, store.get("a", "source").access)
        assertFalse(store.get("a", "source").rootAnswered401)
        store.remove("a", "source")
        assertEquals(CatalogueAccessStatus(), store.get("a", "source"))
    }
}
