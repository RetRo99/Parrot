package com.retro99.parrot.di

import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.catalogue.ui.add.CatalogueAddError
import com.retro99.catalogue.ui.add.CatalogueAddFlow
import com.retro99.catalogue.ui.add.CatalogueAddPhase
import com.retro99.catalogue.ui.add.CatalogueAddStore
import com.retro99.catalogue.ui.add.CatalogueAddressValidator
import com.retro99.catalogue.ui.browse.CatalogueBrowseContent
import com.retro99.catalogue.ui.browse.CatalogueBrowseGateway
import com.retro99.catalogue.ui.browse.CatalogueBrowseNavigation
import com.retro99.catalogue.ui.browse.CatalogueBrowseState
import com.retro99.catalogue.ui.browse.CatalogueBrowser
import com.retro99.catalogue.ui.downloads.ListDownloadState
import com.retro99.catalogue.ui.navigation.CataloguePlace
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.database.api.catalogue.CatalogueDocumentsDatabase
import com.retro99.database.api.library.DeviceFileEntity
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.parrot.initializer.CoilInitializer
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.server.api.CatalogueAccessStore
import com.retro99.server.api.CatalogueImageModel
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.OpdsCredentialStore
import com.retro99.server.api.ServerAccessState
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.user.api.UserRegistry
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Base64
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signed-in use from the first tap to the last, through the app's own graph: the add screen's
 * flow, the browser screen's state holder, the download queue, the file check, the library and
 * the real schema. Only the network is a stand-in: a catalogue that answers 401 Basic to
 * everything without the right account, and a file host elsewhere.
 *
 * This is the only automated cover for signed-in use end to end, so it checks every request
 * that leaves: who got the account details and who did not.
 */
class CatalogueSignedInEndToEndTest {
    private data class Sent(val host: String, val path: String, val authorization: String?, val cookie: String?, val scheme: String)

    private val sent = Collections.synchronizedList(mutableListOf<Sent>())

    private fun basic(user: String, password: String) = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    private val right = basic("patron", "right pass")
    private val wrong = basic("patron", "wrong pass")

    private fun MockRequestHandleScope.answer(request: HttpRequestData): HttpResponseData {
        val path = request.url.encodedPath + request.url.encodedQuery.let { if (it.isEmpty()) "" else "?$it" }
        sent += Sent(request.url.host, path, request.headers[HttpHeaders.Authorization], request.headers[HttpHeaders.Cookie], request.url.protocol.name)
        if (request.url.host == FILE_HOST) {
            return respond(CatalogueTestEpub.BYTES, headers = headersOf(HttpHeaders.ContentType, "application/epub+zip"))
        }
        check(request.url.host == CATALOGUE_HOST) { "Unexpected host" }
        if (request.headers[HttpHeaders.Authorization] != right) {
            return respond("<html>Members only</html>", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.WWWAuthenticate, "Basic realm=\"Members\""))
        }
        val atom = headersOf(HttpHeaders.ContentType, "application/atom+xml;profile=opds-catalog")
        return when {
            request.url.encodedPath == "/opds/" -> respond(ROOT_FEED, headers = atom)
            request.url.encodedPath == "/opds/new" -> respond(bookFeed("New books"), headers = atom)
            request.url.encodedPath == "/opds/osd.xml" -> respond(SEARCH_DESCRIPTION, headers = headersOf(HttpHeaders.ContentType, "application/opensearchdescription+xml"))
            request.url.encodedPath == "/opds/search" -> respond(bookFeed("Results"), headers = atom)
            request.url.encodedPath == "/covers/1.png" -> respond(PNG, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            else -> respond("", HttpStatusCode.NotFound)
        }
    }

    /** Everything sent since [from], and the position to pass as the next [from]. */
    private fun since(from: Int): List<Sent> = synchronized(sent) { sent.drop(from) }

    private suspend fun CatalogueBrowser.await(what: String, condition: (CatalogueBrowseState) -> Boolean): CatalogueBrowseState =
        try {
            withTimeout(STEP_TIMEOUT_MILLIS) { state.first(condition) }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what. The screen shows ${state.value.content::class.simpleName}, sign-in ${state.value.signIn}", timeout)
        }

    private suspend fun CatalogueBrowser.loaded(what: String): CatalogueBrowseContent.Loaded =
        assertIs<CatalogueBrowseContent.Loaded>(await(what) { it.content is CatalogueBrowseContent.Loaded }.content)

    @Test
    fun `add with a 401 - wrong then right details - browse - search - download from another host - library - remove the account`() {
        val graph = RealAppGraph { request -> answer(request) }
        graph.use {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    val koin = graph.koin
                    val users = koin.get<UserRegistry>()
                    users.createProfile(PROFILE, "A", null)
                    users.setActiveProfile(PROFILE)
                    val registry = koin.get<ServerRegistry>()
                    val credentials = koin.get<OpdsCredentialStore>()
                    val access = koin.get<CatalogueAccessStore>()
                    val queue = koin.get<CatalogueAcquisitionManager>()
                    val session = koin.get<ProfileDatabaseSession>()
                    val screens = CoroutineScope(coroutineContext + Job())
                    fun browser(start: CataloguePlace? = null, sourceId: String) = CatalogueBrowser(
                        sourceId = sourceId,
                        start = start,
                        gateway = koin.get<CatalogueBrowseGateway>(),
                        library = koin.get<CatalogueLibraryLookup>(),
                        scope = screens,
                        queue = queue,
                    )
                    fun catalogues() = runBlocking { registry.getAllServers().filter { it.type == ServerType.Opds } }

                    // ---- 1. Add a library: the address alone -----------------------------------
                    // The add screen's own flow, with the validator and store the app gives it.
                    val add = CatalogueAddFlow(
                        validator = koin.get<CatalogueAddressValidator>(),
                        store = koin.get<CatalogueAddStore>(),
                        allowHttp = true,
                    )
                    add.updateAddress(ROOT)
                    add.submit()

                    assertEquals(CatalogueAddError.SignInNeeded, add.state.value.error)
                    assertTrue(add.state.value.needsAccount, "the account fields are turned on")
                    assertNull(add.state.value.addedSourceId)
                    assertEquals(listOf(Sent(CATALOGUE_HOST, "/opds/", null, null, "https")), since(0), "one anonymous request")
                    assertTrue(catalogues().isEmpty(), "nothing is registered before the first page was read")

                    // ---- 2. Wrong details ------------------------------------------------------
                    var mark = sent.size
                    add.updateUsername("patron")
                    add.updatePassword("wrong pass")
                    add.submit()

                    assertEquals(CatalogueAddError.WrongCredentials, add.state.value.error)
                    assertEquals("", add.state.value.password, "the wrong password is cleared from the field")
                    assertEquals(CatalogueAddPhase.Idle, add.state.value.phase)
                    assertEquals(listOf(Sent(CATALOGUE_HOST, "/opds/", wrong, null, "https")), since(mark))
                    assertTrue(catalogues().isEmpty(), "wrong details register nothing")
                    assertNull(koin.get<Preferences>().getStringOrNull(PreferencesKey.UserScoped(PROFILE, PreferencesKey.OpdsCredentials.name)), "and save nothing")

                    // ---- 3. Right details ------------------------------------------------------
                    mark = sent.size
                    add.updatePassword("right pass")
                    add.submit()

                    val sourceId = assertNotNull(add.state.value.addedSourceId, "added; error: ${add.state.value.error}")
                    assertEquals(listOf(Sent(CATALOGUE_HOST, "/opds/", right, null, "https")), since(mark))
                    val source = catalogues().single()
                    assertEquals(sourceId, source.id)
                    assertEquals(ROOT, source.baseUrl, "the address is stored as it was typed")
                    assertEquals("Members' Library", source.name, "named after the first page")
                    assertEquals(OpdsAccountDetails("patron", "right pass"), credentials.get(PROFILE, sourceId))
                    // The account is kept apart from the catalogue's own settings and from the library servers' list.
                    val preferences = koin.get<Preferences>()
                    for (key in listOf(PreferencesKey.CatalogueSources, PreferencesKey.RegisteredServers, PreferencesKey.ServerCredentials, PreferencesKey.CatalogueAccessStatus)) {
                        val stored = preferences.getStringOrNull(PreferencesKey.UserScoped(PROFILE, key.name)).orEmpty()
                        assertFalse("right pass" in stored, "${key.name} holds the password")
                    }
                    assertFalse(registry.isAuthenticated(sourceId), "a catalogue account is not a library sign-in")
                    assertNull(registry.getCredentials(sourceId))

                    // ---- 4. Browse the first page ----------------------------------------------
                    mark = sent.size
                    val first = browser(sourceId = sourceId)
                    val firstPage = first.loaded("the first page")

                    assertEquals("Members' Library", first.state.value.catalogueName)
                    assertTrue(first.state.value.searchAvailable)
                    assertEquals(listOf("New books"), firstPage.folders.map { it.title })
                    assertNull(first.state.value.signIn)
                    assertEquals(ServerAccessState.SignedIn("patron"), access.get(PROFILE, sourceId).access)
                    assertTrue(since(mark).isNotEmpty() && since(mark).all { it == Sent(CATALOGUE_HOST, "/opds/", right, null, "https") }, since(mark).toString())

                    // ---- 5. Open a folder ------------------------------------------------------
                    mark = sent.size
                    first.openFolder(firstPage.folders.single().key)
                    val place = assertIs<CatalogueBrowseNavigation.OpenPage>(first.await("the folder to open") { it.navigation != null }.navigation).place
                    first.navigationHandled()
                    val folder = browser(place, sourceId)
                    // (A folder that holds one book only would open as that book's page.)
                    val rows = folder.loaded("the folder").books
                    assertEquals(listOf("A Catalogue Book", "Another Book"), rows.map { it.title })
                    val row = rows.first()

                    assertEquals("A Catalogue Book", row.title)
                    assertEquals("A. Writer", row.author)
                    assertFalse(row.inLibrary)
                    assertEquals(ListDownloadState.Available, row.download)
                    assertEquals(listOf(Sent(CATALOGUE_HOST, "/opds/new", right, null, "https")), since(mark))

                    // ---- 6. The row's cover, through the app's image path -----------------------
                    mark = sent.size
                    val coil = koin.get<CoilInitializer>()
                    val cover = assertNotNull(row.cover, "the row has a cover")
                    assertEquals(CatalogueImageModel(sourceId, "https://$CATALOGUE_HOST/covers/1.png"), cover)
                    val picture = assertNotNull(coil.catalogueImages.load(cover))

                    assertContentEquals(PNG, picture.bytes)
                    assertEquals(listOf(Sent(CATALOGUE_HOST, "/covers/1.png", right, null, "https")), since(mark), "the catalogue's own picture gets its account")
                    // The same address through the global loader, which serves library servers, gets nothing.
                    assertNull(coil.resolveTokenForUrl(io.ktor.http.Url("https://$CATALOGUE_HOST/covers/1.png")))

                    // ---- 7. Search -------------------------------------------------------------
                    mark = sent.size
                    first.onSearchTextChange("catalogue book")
                    first.submitSearch()
                    val results = assertIs<CatalogueBrowseContent.Loaded>(
                        first.await("search results") { it.searchQuery == "catalogue book" && it.content is CatalogueBrowseContent.Loaded }.content,
                    )

                    assertEquals(listOf("A Catalogue Book", "Another Book"), results.books.map { it.title })
                    assertEquals(
                        listOf(
                            Sent(CATALOGUE_HOST, "/opds/osd.xml", right, null, "https"),
                            Sent(CATALOGUE_HOST, "/opds/search?q=catalogue%20book", right, null, "https"),
                        ),
                        since(mark),
                    )
                    first.clearSearch()

                    // ---- 8. Download: the file is on another host ------------------------------
                    mark = sent.size
                    folder.downloadBook(row.key)
                    val done = withTimeout(STEP_TIMEOUT_MILLIS) {
                        queue.observeAcquisitions().first { all -> all.any { it.state == AcquisitionState.Done || it.state is AcquisitionState.Failed } }.single()
                    }

                    assertEquals(AcquisitionState.Done, done.state)
                    val downloadRequests = since(mark)
                    val fileRequest = downloadRequests.single { it.host == FILE_HOST }
                    assertEquals(Sent(FILE_HOST, "/books/one.epub?sig=abc", null, null, "https"), fileRequest, "the file host gets no account details and no cookie")
                    assertTrue(downloadRequests.filter { it.host == CATALOGUE_HOST }.all { it.path == "/opds/new" && it.authorization == right }, downloadRequests.toString())
                    assertTrue(downloadRequests.last() == fileRequest, "the listing is read again, then the file is fetched")

                    // ---- 9. It is in the library -----------------------------------------------
                    val bookId = assertNotNull(done.libraryBookId)
                    session.withProfile(PROFILE) {
                        val book = assertNotNull(koin.get<LibraryBooksDatabase>().getLibraryBookById(bookId))
                        assertEquals("A Catalogue Book", book.title)
                        val file = koin.get<DeviceFilesDatabase>().getDeviceFiles(bookId).single()
                        assertEquals(DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD, file.origin)
                        assertTrue(File(file.filePath).readBytes().contentEquals(CatalogueTestEpub.BYTES))
                        val provenance = koin.get<CatalogueBookSourcesDatabase>().getForBook(bookId).single()
                        assertEquals("https://$CATALOGUE_HOST:443", provenance.catalogueOrigin, "the catalogue is named by its origin, never its address")
                        assertFalse(listOf(provenance.toString()).any { "patron" in it || "right pass" in it || "sig=abc" in it || "/opds/" in it }, provenance.toString())
                    }
                    assertTrue(graph.stagingRoot.walk().none { it.isFile }, "the staged file was consumed")
                    folder.onReturn()
                    val inLibrary = folder.await("the row to say In your library") { state ->
                        (state.content as? CatalogueBrowseContent.Loaded)?.books?.firstOrNull()?.inLibrary == true
                    }
                    val rowsAfter = assertIs<CatalogueBrowseContent.Loaded>(inLibrary.content).books
                    assertEquals(listOf(ListDownloadState.InLibrary, ListDownloadState.Available), rowsAfter.map { it.download }, "only the downloaded book")
                    // Pages opened while signed in were saved for offline reading.
                    assertTrue(session.withProfile(PROFILE) { koin.get<CatalogueDocumentsDatabase>().count() } > 0)

                    // ---- 10. Remove the account details ----------------------------------------
                    val generationBefore = credentials.accessGeneration(PROFILE, sourceId)
                    registry.clearCredentials(sourceId)

                    assertNull(credentials.get(PROFILE, sourceId))
                    assertTrue(credentials.accessGeneration(PROFILE, sourceId) != generationBefore)
                    assertEquals(0L, session.withProfile(PROFILE) { koin.get<CatalogueDocumentsDatabase>().count() }, "pages fetched with the account are gone")
                    assertEquals(listOf(sourceId), catalogues().map { it.id }, "the catalogue itself stays")
                    session.withProfile(PROFILE) {
                        assertNotNull(koin.get<LibraryBooksDatabase>().getLibraryBookById(bookId), "the book stays")
                        assertEquals(1, koin.get<CatalogueBookSourcesDatabase>().getForBook(bookId).size, "and so does where it came from")
                    }

                    // ---- 11. The next request is anonymous -------------------------------------
                    mark = sent.size
                    val after = browser(sourceId = sourceId)
                    val asked = after.await("the sign-in sheet") { it.signIn != null && it.content == CatalogueBrowseContent.SignInNeeded }

                    assertFalse(assertNotNull(asked.signIn).wrongDetails)
                    assertTrue(since(mark).isNotEmpty())
                    assertTrue(since(mark).all { it == Sent(CATALOGUE_HOST, "/opds/", null, null, "https") }, "anonymous after removal: ${since(mark)}")
                    assertEquals(ServerAccessState.SignInNeeded, access.get(PROFILE, sourceId).access)
                    // A picture is anonymous too, and so not shown.
                    mark = sent.size
                    assertNull(coil.catalogueImages.load(cover))
                    assertTrue(since(mark).all { it.authorization == null }, since(mark).toString())

                    // ---- 12. Sign in again from the sheet: wrong, then right --------------------
                    mark = sent.size
                    after.signIn("patron", "wrong pass")
                    val refused = after.await("the sheet to say the details are wrong") { it.signIn?.wrongDetails == true && it.signIn?.working == false }

                    assertEquals(CatalogueBrowseContent.SignInNeeded, refused.content)
                    assertNull(credentials.get(PROFILE, sourceId), "wrong details are not saved")
                    assertEquals(listOf(Sent(CATALOGUE_HOST, "/opds/", wrong, null, "https")), since(mark))

                    mark = sent.size
                    after.signIn("patron", "right pass")
                    after.loaded("the first page after signing in again")

                    assertEquals(OpdsAccountDetails("patron", "right pass"), credentials.get(PROFILE, sourceId))
                    assertNull(after.state.value.signIn)
                    assertTrue(since(mark).all { it.host == CATALOGUE_HOST && it.authorization == right }, since(mark).toString())
                    assertEquals(ServerAccessState.SignedIn("patron"), access.get(PROFILE, sourceId).access)

                    // ---- Over the whole run ----------------------------------------------------
                    val everything = since(0)
                    assertEquals(setOf(CATALOGUE_HOST, FILE_HOST), everything.map { it.host }.toSet())
                    assertTrue(everything.all { it.scheme == "https" })
                    assertTrue(everything.none { it.cookie != null })
                    assertTrue(everything.filter { it.host != CATALOGUE_HOST }.all { it.authorization == null }, "account details left the catalogue's own address")
                    assertEquals(2, everything.count { it.authorization == wrong }, "each wrong attempt was sent once")
                    assertTrue(everything.none { "patron" in it.path || "pass" in it.path }, "account details never appear in an address")
                    assertTrue(graph.reported.isEmpty(), "nothing was reported as an error: ${graph.reported}")

                    screens.cancel()
                }
            }
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 60_000L
        const val STEP_TIMEOUT_MILLIS = 15_000L
        const val PROFILE = "a"
        const val CATALOGUE_HOST = "library.example"
        const val FILE_HOST = "files.cdn.example"
        const val ROOT = "https://$CATALOGUE_HOST/opds/"

        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

        const val ROOT_FEED = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:library:root</id><title>Members' Library</title>
<link rel="self" href="/opds/" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
<link rel="search" href="/opds/osd.xml" type="application/opensearchdescription+xml"/>
<entry><id>urn:library:new</id><title>New books</title>
<link rel="subsection" href="/opds/new" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/></entry>
</feed>"""

        const val SEARCH_DESCRIPTION = """<?xml version="1.0" encoding="UTF-8"?>
<OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/"><ShortName>Library</ShortName>
<Url type="application/atom+xml;profile=opds-catalog" template="https://library.example/opds/search?q={searchTerms}"/>
</OpenSearchDescription>"""

        fun bookFeed(title: String) = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom"><id>urn:library:${title.lowercase().replace(' ', '-')}</id><title>$title</title>
<entry><id>urn:book:1</id><title>A Catalogue Book</title><author><name>A. Writer</name></author>
<link rel="http://opds-spec.org/image" type="image/png" href="/covers/1.png"/>
<link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://$FILE_HOST/books/one.epub?sig=abc"/></entry>
<entry><id>urn:book:2</id><title>Another Book</title><author><name>B. Writer</name></author>
<link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://$FILE_HOST/books/two.epub"/></entry>
</feed>"""
    }
}
