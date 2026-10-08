package com.retro99.server.opds

import com.github.michaelbull.result.get
import com.retro99.opds.implementation.transport.KtorOpdsTransport
import com.retro99.server.api.*
import com.retro99.server.implementation.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogueAccountVerificationTest {
    @Test fun temporary_details_verify_the_protected_page_without_saving_or_closing_the_live_session() = runTest {
        val preferences = TestPreferences()
        val credentials = OpdsCredentialStoreImpl(preferences)
        val checks = CatalogueAccessStoreImpl(preferences)
        val authorizations = mutableListOf<String?>()
        val engine = MockEngine { request ->
            val auth = request.headers[HttpHeaders.Authorization]
            authorizations += auth
            if (request.url.encodedPath.endsWith("child") && auth != "Basic cm9rOg==") {
                respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"books\""))
            } else respond(OpdsCatalogueRepositoryTest.FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json"))
        }
        val repository = OpdsCatalogueRepository("profile", OpdsCatalogueRepositoryTest.SOURCE, KtorOpdsTransport(engine, OpdsCatalogueRepositoryTest.ROOT), credentials, checks, { true })
        val root = assertIs<CatalogueFeedDocument>(repository.getRoot().get())
        val target = root.navigation.single().links.single().target!!
        assertTrue(repository.checkAccount(target, OpdsAccountDetails("rok", "wrong")).isErr)
        assertNull(credentials.get("profile", repository.serverId))
        assertTrue(repository.checkAccount(target, OpdsAccountDetails("rok", "")).isOk)
        assertEquals("Basic cm9rOg==", authorizations.last())
        assertNull(credentials.get("profile", repository.serverId))
        assertTrue(repository.getDocument(target).isErr) // No credential or private cache leaked.
        assertTrue(repository.getRoot().isOk) // The temporary request did not close the transport.
        repository.dispose()
    }
}
