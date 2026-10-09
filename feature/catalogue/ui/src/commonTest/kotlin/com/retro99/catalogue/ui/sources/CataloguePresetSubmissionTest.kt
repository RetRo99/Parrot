package com.retro99.catalogue.ui.sources

import com.retro99.catalogue.ui.add.*
import com.retro99.server.api.OpdsAccountDetails
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CataloguePresetSubmissionTest {
    @Test fun effectKeyIsConsumedOnlyAfterSuspendingValidationAndPersistenceFinish() = runTest {
        val answer = CompletableDeferred<CatalogueValidation>()
        var consumed = false
        var saved = false
        val store = object : CatalogueAddStore {
            override suspend fun existingAddresses() = emptySet<String>()
            override suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?): String { saved = true; return "source" }
        }
        val flow = CatalogueAddFlow(CatalogueAddressValidator { _, _ -> answer.await() }, store, true, "https://example.org/opds")
        backgroundScope.launch { submitCataloguePreset(flow, "Public catalogue") { consumed = true } }
        runCurrent()
        assertEquals(CatalogueAddPhase.Checking, flow.state.value.phase)
        assertFalse(consumed, "clearing a LaunchedEffect key here would cancel the in-flight check")
        answer.complete(CatalogueValidation.Accepted()); runCurrent()
        assertTrue(saved); assertTrue(consumed)
        assertEquals("source", flow.state.value.addedSourceId)
    }
}
