package com.retro99.parrot.di

import com.retro99.base.AppInitializer
import com.retro99.parrot.initializer.CoilInitializer
import com.retro99.server.api.CatalogueAccountEditor
import com.retro99.server.api.CatalogueImageModel
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.ServerCredentials
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.user.api.UserRegistry
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import java.util.Base64
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan §10.7 in the app's own graph: a library server's covers and a catalogue's pictures take
 * different paths even on one address. The image loader's client, the registry, the token
 * provider and the catalogue sessions are the ones the app resolves; only the network engine
 * is local, and it records what each request carried.
 */
class CatalogueCoverLoadingTest {
    private val seen: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())

    private fun authorizations() = seen.map { it.url.toString() to it.headers[HttpHeaders.Authorization] }

    @Test
    fun `a Storyteller cover gets its bearer token again when a catalogue shares its address`() = inGraph { koin ->
        val registry = koin.get<ServerRegistry>()
        registry.addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        registry.addServerWithId("library", "Library", ServerType.Storyteller, SHARED)
        registry.saveCredentials(ServerCredentials("library", "reader", "library-token"))
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)

        val client = koin.coil().newImageClient()
        try {
            client.get("$SHARED/api/books/1/cover")
        } finally {
            client.close()
        }

        assertEquals(listOf("$SHARED/api/books/1/cover" to "Bearer library-token"), authorizations())
    }

    @Test
    fun `the order the two were added in does not matter`() = inGraph { koin ->
        val registry = koin.get<ServerRegistry>()
        registry.addServerWithId("library", "Library", ServerType.Storyteller, SHARED)
        registry.saveCredentials(ServerCredentials("library", "reader", "library-token"))
        registry.addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")

        assertEquals("library-token", koin.coil().resolveTokenForUrl(io.ktor.http.Url("$SHARED/api/books/1/cover")))
    }

    @Test
    fun `a catalogue alone on an address gives the global loader nothing to send`() = inGraph { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)

        assertNull(koin.coil().resolveTokenForUrl(io.ktor.http.Url("$SHARED/covers/1.png")))
    }

    @Test
    fun `a catalogue picture never gets a bearer token`() = inGraph { koin ->
        val registry = koin.get<ServerRegistry>()
        registry.addServerWithId("library", "Library", ServerType.Storyteller, SHARED)
        registry.saveCredentials(ServerCredentials("library", "reader", "library-token"))
        registry.addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")

        val picture = koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.png"))

        assertContentEquals(PNG, assertNotNull(picture).bytes)
        assertEquals("image/png", picture.mediaType)
        assertEquals(listOf("$SHARED/covers/1.png" to null), authorizations())
    }

    @Test
    fun `a catalogue picture on the catalogue's address gets its account details`() = inGraph { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)

        assertNotNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.png")))

        assertEquals(listOf("$SHARED/covers/1.png" to BASIC), authorizations())
    }

    @Test
    fun `a catalogue picture on another host gets nothing`() = inGraph { koin ->
        val registry = koin.get<ServerRegistry>()
        registry.addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)
        // Even when the other host is a library server the user is signed in to.
        registry.addServerWithId("library", "Library", ServerType.Storyteller, "https://library.example")
        registry.saveCredentials(ServerCredentials("library", "reader", "library-token"))

        assertNotNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "https://library.example/covers/1.png")))

        assertEquals(listOf("https://library.example/covers/1.png" to null), authorizations())
    }

    @Test
    fun `a redirect off the catalogue's address drops the account details`() = inGraph(
        respond = { request ->
            if (request.url.host == "shared.example") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://cdn.example/1.png"))
            } else {
                respond(PNG)
            }
        },
    ) { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)

        assertNotNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.png")))

        assertEquals(listOf("$SHARED/covers/1.png" to BASIC, "https://cdn.example/1.png" to null), authorizations())
    }

    @Test
    fun `a turned-off catalogue's pictures are not requested`() = inGraph { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)
        koin.get<ServerRegistry>().deactivateServer("catalogue")

        assertNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.png")))

        assertEquals(emptyList(), authorizations())
    }

    @Test
    fun `a removed catalogue's pictures are not requested`() = inGraph { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)
        koin.get<ServerRegistry>().removeServer("catalogue")

        assertNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.png")))

        assertEquals(emptyList(), authorizations())
    }

    @Test
    fun `another profile's catalogue is never used`() = inGraph { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)
        val users = koin.get<UserRegistry>()
        users.createProfile("b", "B", null)
        users.setActiveProfile("b")

        assertNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.png")))
        assertEquals(emptyList(), authorizations())

        // The same id in this profile is this profile's catalogue, with this profile's (no) account.
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Other books", ServerType.Opds, "$SHARED/opds/")
        assertNotNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.png")))
        assertEquals(listOf("$SHARED/covers/1.png" to null), authorizations())
    }

    @Test
    fun `a library server's id is not a catalogue`() = inGraph { koin ->
        val registry = koin.get<ServerRegistry>()
        registry.addServerWithId("library", "Library", ServerType.Storyteller, SHARED)
        registry.saveCredentials(ServerCredentials("library", "reader", "library-token"))

        assertNull(koin.coil().catalogueImages.load(CatalogueImageModel("library", "$SHARED/api/books/1/cover")))

        assertEquals(emptyList(), authorizations())
    }

    @Test
    fun `a picture that is not a raster image is refused`() = inGraph(
        respond = { respond("""<svg xmlns="http://www.w3.org/2000/svg"/>""", headers = headersOf(HttpHeaders.ContentType, "image/png")) },
    ) { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")

        assertNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "$SHARED/covers/1.svg")))
    }

    @Test
    fun `an inline picture is decoded without a request`() = inGraph { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        val inline = "data:image/png;base64,${Base64.getEncoder().encodeToString(PNG)}"

        assertContentEquals(PNG, koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", inline))?.bytes)
        assertNull(koin.coil().catalogueImages.load(CatalogueImageModel("catalogue", "data:image/svg+xml;base64,PHN2Zy8+")))

        assertEquals(emptyList(), authorizations())
    }

    @Test
    fun `the cache key of a picture belongs to one profile, one catalogue and one set of account details`() = inGraph { koin ->
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        val model = CatalogueImageModel("catalogue", "$SHARED/covers/1.png")
        val images = koin.coil().catalogueImages

        val anonymous = assertNotNull(images.cacheKey(model))
        koin.get<CatalogueAccountEditor>().saveAccount("catalogue", ACCOUNT)
        val signedIn = assertNotNull(images.cacheKey(model))
        val users = koin.get<UserRegistry>()
        users.createProfile("b", "B", null)
        users.setActiveProfile("b")
        koin.get<ServerRegistry>().addServerWithId("catalogue", "Books", ServerType.Opds, "$SHARED/opds/")
        val otherProfile = assertNotNull(images.cacheKey(model))

        assertEquals(3, setOf(anonymous, signedIn, otherProfile).size)
        assertTrue(listOf(anonymous, signedIn, otherProfile).none { "secret" in it || "patron" in it })
        assertNull(images.cacheKey(CatalogueImageModel("catalogue", "data:image/png;base64,AAAA")))
    }

    // By its own type: the other initializers need Android. It is the instance bound as an AppInitializer.
    private fun Koin.coil(): CoilInitializer = get<CoilInitializer>().also { check(it is AppInitializer) }

    private fun inGraph(
        respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { respond(PNG) },
        test: suspend (Koin) -> Unit,
    ) {
        RealAppGraph { request -> seen += request; respond(request) }.use { graph ->
            runBlocking {
                withTimeout(20_000) {
                    val users = graph.koin.get<UserRegistry>()
                    users.createProfile("a", "A", null)
                    users.setActiveProfile("a")
                    test(graph.koin)
                }
            }
        }
    }

    private companion object {
        const val SHARED = "https://shared.example"
        val ACCOUNT = OpdsAccountDetails("patron", "secret")
        const val BASIC = "Basic cGF0cm9uOnNlY3JldA=="
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    }
}
