package com.retro99.server.opds

import com.retro99.opds.implementation.transport.KtorOpdsTransport
import com.retro99.server.api.*
import com.retro99.server.implementation.CatalogueAccessStoreImpl
import com.retro99.server.implementation.OpdsCredentialStoreImpl
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Pictures go through the catalogue's own transport, under the rules of its pages (plan §10.7). */
class OpdsCatalogueImageTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val account = OpdsAccountDetails("patron", "secret")
    private val seen = mutableListOf<HttpRequestData>()

    private suspend fun repository(
        root: String = "https://books.example/opds/",
        account: OpdsAccountDetails? = this.account,
        valid: () -> Boolean = { true },
        respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond(png) },
    ): OpdsCatalogueRepository {
        val preferences = TestPreferences()
        val credentials = OpdsCredentialStoreImpl(preferences)
        if (account != null) credentials.save("a", "source", account)
        val engine = MockEngine { request -> seen += request; respond(request) }
        val config = ServerConfig("source", "Books", ServerType.Opds, root, 0)
        return OpdsCatalogueRepository("a", config, KtorOpdsTransport(engine, root), credentials, CatalogueAccessStoreImpl(preferences), valid, { 10L })
    }

    private fun authorizations() = seen.map { it.headers[HttpHeaders.Authorization] }

    @Test fun a_picture_on_the_catalogues_origin_gets_its_account_details() = runTest {
        val repository = repository()
        assertContentEquals(png, repository.loadImage("https://books.example/covers/1.png"))
        assertEquals(listOf<String?>("Basic cGF0cm9uOnNlY3JldA=="), authorizations())
        assertTrue(seen.single().headers[HttpHeaders.Accept].orEmpty().startsWith("image/"))
        assertNull(seen.single().headers[HttpHeaders.Cookie])
    }

    @Test fun a_picture_on_another_host_gets_nothing() = runTest {
        val repository = repository()
        assertContentEquals(png, repository.loadImage("https://cdn.example/covers/1.png"))
        assertEquals(listOf<String?>(null), authorizations())
    }

    @Test fun another_port_or_scheme_is_another_origin() = runTest {
        val repository = repository()
        repository.loadImage("https://books.example:8443/covers/1.png")
        assertEquals(listOf<String?>(null), authorizations())
        // Plain http is not allowed for an https catalogue at all.
        assertNull(repository.loadImage("http://books.example/covers/1.png"))
        assertEquals(1, seen.size)
    }

    @Test fun a_redirect_off_the_origin_drops_the_account_details_and_does_not_get_them_back() = runTest {
        val repository = repository { request ->
            when (request.url.toString()) {
                "https://books.example/covers/1.png" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example/1.png"))
                "https://cdn.example/1.png" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://books.example/real/1.png"))
                else -> respond(png)
            }
        }
        assertContentEquals(png, repository.loadImage("https://books.example/covers/1.png"))
        assertEquals(listOf("Basic cGF0cm9uOnNlY3JldA==", null, null), authorizations())
    }

    @Test fun a_catalogue_without_an_account_sends_nothing() = runTest {
        val repository = repository(account = null)
        assertContentEquals(png, repository.loadImage("https://books.example/covers/1.png"))
        assertEquals(listOf<String?>(null), authorizations())
    }

    @Test fun a_picture_over_the_ceiling_is_not_returned() = runTest {
        val tooLong = ByteArray(CatalogueImageLimits.MAX_IMAGE_BYTES.toInt() + 1)
        val declared = repository { respond(tooLong, headers = headersOf(HttpHeaders.ContentLength, tooLong.size.toString())) }
        assertNull(declared.loadImage("https://books.example/covers/1.png"))
        // The MockEngine reports no length for a channel body, as a chunked response would.
        val undeclared = repository { respond(io.ktor.utils.io.ByteReadChannel(tooLong)) }
        assertNull(undeclared.loadImage("https://books.example/covers/1.png"))
        val atCeiling = repository { respond(ByteArray(CatalogueImageLimits.MAX_IMAGE_BYTES.toInt())) }
        assertEquals(CatalogueImageLimits.MAX_IMAGE_BYTES.toInt(), atCeiling.loadImage("https://books.example/covers/1.png")?.size)
    }

    @Test fun a_refused_picture_is_just_missing() = runTest {
        val repository = repository { respond("", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic")) }
        assertNull(repository.loadImage("https://books.example/covers/1.png"))
        assertNull(repository.loadImage("file:///etc/passwd"))
        assertNull(repository.loadImage("https://user:pass@books.example/covers/1.png"))
        assertEquals(1, seen.size)
    }

    @Test fun a_session_that_ended_loads_nothing() = runTest {
        var current = true
        val repository = repository(valid = { current })
        current = false
        assertFailsWith<CancellationException> { repository.loadImage("https://books.example/covers/1.png") }
        assertEquals(0, seen.size)
        val stopped = repository()
        stopped.stop()
        assertFailsWith<CancellationException> { stopped.loadImage("https://books.example/covers/1.png") }
        assertEquals(0, seen.size)
    }

    @Test fun a_picture_does_not_change_what_the_catalogues_status_says() = runTest {
        val preferences = TestPreferences()
        val access = CatalogueAccessStoreImpl(preferences)
        val engine = MockEngine { respond("", HttpStatusCode.Forbidden) }
        val root = "https://books.example/opds/"
        val repository = OpdsCatalogueRepository("a", ServerConfig("source", "Books", ServerType.Opds, root, 0),
            KtorOpdsTransport(engine, root), OpdsCredentialStoreImpl(preferences), access, { true }, { 10L })
        assertNull(repository.loadImage("https://books.example/covers/1.png"))
        assertNull(access.get("a", "source").lastCheck.lastError)
    }
}
