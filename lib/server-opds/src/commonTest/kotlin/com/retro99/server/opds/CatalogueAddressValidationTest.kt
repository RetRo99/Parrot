package com.retro99.server.opds

import com.retro99.server.api.CatalogueConnectionResult
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import com.retro99.user.implementation.ProfileWorkRegistryImpl
import com.retro99.user.implementation.UserRegistryImpl
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The one-page check behind "Add by address" and "Change address", given addresses nobody should type. */
class CatalogueAddressValidationTest {
    private var requests = 0
    private var engines = 0

    private fun factory(): OpdsCatalogueRepositoryFactory {
        val preferences = TestPreferences()
        val profileWork = ProfileWorkRegistryImpl()
        val users = UserRegistryImpl(preferences, profileWork)
        val documents = MemoryDocuments { users.getActiveProfileId() }
        val engineFactory = object : HttpClientEngineFactory<MockEngineConfig> {
            override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine {
                engines++
                return MockEngine { requests++; respond(OpdsCatalogueRepositoryTest.FEED, headers = headersOf(HttpHeaders.ContentType, "application/opds+json")) }
            }
        }
        return OpdsCatalogueRepositoryFactory(engineFactory, users, OpdsCredentialStoreImpl(preferences), CatalogueAccessStoreImpl(preferences), preferences, profileWork, documents.session, documents)
    }

    @Test fun an_address_that_cannot_be_read_as_one_is_answered_and_never_thrown_or_asked() = runTest {
        val factory = factory()
        val broken = listOf(
            "https://books.example:port/opds",
            "https://books.example:99999999999/opds",
            "https://[::1/opds",
            "https://",
            "https:///opds",
            "https://user:pass@books.example/opds",
            "https://books.example/o pds",
            "ftp://books.example/opds",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "books.example/opds",
            "",
            "\u0000",
        )

        val accepted = broken.filter { address -> factory.validate(address, null) is CatalogueConnectionResult.Accepted }

        assertEquals(emptyList(), accepted)
        assertEquals(0, requests, "nothing was asked for")
    }

    @Test fun a_good_address_is_still_accepted() = runTest {
        assertIs<CatalogueConnectionResult.Accepted>(factory().validate(OpdsCatalogueRepositoryTest.ROOT, null))
        assertEquals(1, requests)
        assertEquals(1, engines)
    }
}
