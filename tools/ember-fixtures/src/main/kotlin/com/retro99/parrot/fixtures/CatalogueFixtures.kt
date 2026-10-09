package com.retro99.parrot.fixtures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.catalogue.ui.add.CatalogueAddFlow
import com.retro99.catalogue.ui.add.CatalogueAddStore
import com.retro99.catalogue.ui.add.CatalogueAddressValidator
import com.retro99.catalogue.ui.add.CatalogueValidation
import com.retro99.catalogue.ui.sources.CatalogueAccountState
import com.retro99.catalogue.ui.sources.CatalogueAddDialogs
import com.retro99.catalogue.ui.sources.CatalogueAddScreenContent
import com.retro99.catalogue.ui.sources.CataloguePreset
import com.retro99.catalogue.ui.sources.CatalogueSignInSheet
import com.retro99.catalogue.ui.sources.CatalogueSourceRow
import com.retro99.catalogue.ui.sources.CatalogueSourcesViewState
import com.retro99.catalogue.ui.sources.CatalogueSourcesContent
import com.retro99.catalogue.domain.CatalogueDescriptionFormat
import com.retro99.catalogue.domain.sanitizeCatalogueDescription
import com.retro99.catalogue.ui.description.CatalogueDescriptionText
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerType

/**
 * Book catalogue (OPDS) fixtures: one per design board, drawn by the production composables
 * from state built here. No network, account or database.
 *
 * To add a board, add one `fixture(view = "...", expect = "...") { ... }` line below. `view`
 * is the board's name without "opds-" (design/ember/catalogues/screens/opds-<view>-<theme>.png)
 * and names the capture: design/screens/catalogue-<view>-<theme>.png. `expect` is a text that
 * is on screen only when the fixture drew what it should; catalogue_capture.py reads both
 * from this file and fails the capture without it. Keep each call's `view = "..."` and
 * `expect = "..."` on one line, as literals.
 */
class CatalogueFixture(val view: String, val expect: String, val content: @Composable () -> Unit)

private fun fixture(view: String, expect: String, content: @Composable () -> Unit) = CatalogueFixture(view, expect, content)

val catalogueFixtures: List<CatalogueFixture> = listOf(
    fixture(view = "detail", expect = "Other files (3)") { CatalogueBookBoard("detail") },
    fixture(view = "detailWait", expect = "Two other books are downloading.") { CatalogueBookBoard("detailWait") },
    fixture(view = "detailWaitOne", expect = "Another book is downloading.") { CatalogueBookBoard("detailWaitOne") },
    fixture(view = "detailDl", expect = "0.5 of 1.2 MB") { CatalogueBookBoard("detailDl") },
    fixture(view = "detailUnknown", expect = "1.4 MB so far") { CatalogueBookBoard("detailUnknown") },
    fixture(view = "detailIos", expect = "Keep Parrot open until this finishes.") { CatalogueBookBoard("detailIos") },
    fixture(view = "detailAdding", expect = "Checking the file and preparing it for reading.") { CatalogueBookBoard("detailAdding") },
    fixture(view = "detailDone", expect = "downloaded just now") { CatalogueBookBoard("detailDone") },
    fixture(view = "blocked", expect = "This book is sold on") { CatalogueBookBoard("blocked") },
    fixture(view = "blockedSubscription", expect = "This book is part of a subscription") { CatalogueBookBoard("blockedSubscription") },
    fixture(view = "blockedBorrow", expect = "This book can be borrowed") { CatalogueBookBoard("blockedBorrow") },
    fixture(view = "blockedSample", expect = "The catalogue offers only a sample") { CatalogueBookBoard("blockedSample") },
    fixture(view = "blockedFormat", expect = "This book is only available as PDF") { CatalogueBookBoard("blockedFormat") },
    fixture(view = "blockedProtected", expect = "This book's file is protected (DRM)") { CatalogueBookBoard("blockedProtected") },
    fixture(view = "editions", expect = "Names come from the catalogue.") { CatalogueBookBoard("editions") },
    fixture(view = "editionsGrouped", expect = "This book has 2 editions in the catalogue.") { CatalogueBookBoard("editionsGrouped") },
    // Not a board: the description block of the book page, to prove the harness end to end.
    fixture(view = "descriptionText", expect = "Maps & notes") {
        FixturePage {
            CatalogueDescriptionText(
                sanitizeCatalogueDescription(
                    """<p><b>Winner of the Hugo Award.</b> A <i>sweeping</i> tale of the salt roads.</p>
                       <p>This edition includes:</p>
                       <ul><li>A new foreword</li><li>Maps &amp; notes<ol><li>The coast</li><li>The inland sea</li></ol></li></ul>
                       <p>See <a href="https://publisher.example/book">the publisher's page</a>.<br>© 2004 Ines Varga</p>
                       <script>document.title = "must not appear"</script><img src="x" onerror="alert(1)">""",
                    CatalogueDescriptionFormat.Html,
                ),
            )
        }
    },
    fixture(view = "add", expect = "Add a library") { CatalogueAddBoard("add") },
    fixture(view = "addError", expect = "This is a web page") { CatalogueAddBoard("addError") },
    fixture(view = "addInvalid", expect = "This address answered") { CatalogueAddBoard("addInvalid") },
    fixture(view = "addUnreachable", expect = "Can't reach this address") { CatalogueAddBoard("addUnreachable") },
    fixture(view = "addSignin", expect = "This catalogue needs an account") { CatalogueAddBoard("addSignin") },
    fixture(view = "checking", expect = "Checking address…") { CatalogueAddBoard("checking") },
    fixture(view = "catalogues", expect = "Project Gutenberg") { CatalogueGetBooksBoard("catalogues") },
    fixture(view = "cataloguesFew", expect = "Project Gutenberg") { CatalogueGetBooksBoard("cataloguesFew") },
    fixture(view = "cataloguesEmpty", expect = "START WITH ONE OF THESE") { CatalogueGetBooksBoard("cataloguesEmpty") },
    fixture(view = "preset", expect = "Project Gutenberg") { CataloguePresetBoard() },
    fixture(view = "signin", expect = "Sign in to Home Calibre") { CatalogueSignInBoard(wrongDetails = false) },
    fixture(view = "signinWrong", expect = "Username or password is incorrect.") { CatalogueSignInBoard(wrongDetails = true) },
    fixture(view = "http", expect = "This catalogue isn't secure") { CatalogueAddBoard("http") },
    fixture(view = "httpIos", expect = "This catalogue can't be added") { CatalogueAddBoard("httpIos") },
    fixture(view = "pwHttp", expect = "Passwords need a secure address") { CatalogueAddBoard("pwHttp") },
    fixture(view = "pwHttpBlocked", expect = "Passwords need a secure address") { CatalogueAddBoard("pwHttpBlocked") },
    fixture(view = "cert", expect = "Can't check this catalogue") { CatalogueAddBoard("cert") },
    fixture(view = "unsupported", expect = "Sign-in method not supported") { CatalogueAddBoard("unsupported") },
    fixture(view = "unsupportedBlocked", expect = "Can't add this catalogue yet") { CatalogueAddBoard("unsupportedBlocked") },
    // The catalogue browser. The seven failed* fixtures are the "Couldn't open this page" reasons; they have no board.
    fixture(view = "browse", expect = "Popular this week") { CatalogueBrowseBoard("browse") },
    fixture(view = "browsePlain", expect = "Unsorted imports") { CatalogueBrowseBoard("browsePlain") },
    fixture(view = "firstLoad", expect = "Opening Project Gutenberg…") { CatalogueBrowseBoard("firstLoad") },
    fixture(view = "emptyFolder", expect = "Nothing here yet") { CatalogueBrowseBoard("emptyFolder") },
    fixture(view = "limited", expect = "Too many requests") { CatalogueBrowseBoard("limited") },
    fixture(view = "offline", expect = "Showing the copy saved 2 h ago") { CatalogueBrowseBoard("offline") },
    fixture(view = "offlineNone", expect = "no saved copy to show") { CatalogueBrowseBoard("offlineNone") },
    fixture(view = "localNet", expect = "Open a device on your network?") { CatalogueBrowseBoard("localNet") },
    fixture(view = "list", expect = "The Count of Monte Cristo") { CatalogueBrowseBoard(if (Ember.style.isEink) "listEink" else "list") },
    fixture(view = "listFailed", expect = "The ones above are still here.") { CatalogueBrowseBoard("listFailed") },
    fixture(view = "search", expect = "in Project Gutenberg") { CatalogueBrowseBoard("search") },
    fixture(view = "noResults", expect = "No books found for") { CatalogueBrowseBoard("noResults") },
    fixture(view = "filter", expect = "54 options") { CatalogueBrowseBoard("filter") },
    fixture(view = "editionsList", expect = "lists this book 3 times") { CatalogueBrowseBoard("editionsList") },
    fixture(view = "failedTimedOut", expect = "took too long to answer") { CatalogueBrowseBoard("failedTimedOut") },
    fixture(view = "failedCatalogueError", expect = "had a problem on its side") { CatalogueBrowseBoard("failedCatalogueError") },
    fixture(view = "failedNotAllowed", expect = "allow access to this page") { CatalogueBrowseBoard("failedNotAllowed") },
    fixture(view = "failedNotFound", expect = "This page no longer exists") { CatalogueBrowseBoard("failedNotFound") },
    fixture(view = "failedTooLarge", expect = "This page is too large") { CatalogueBrowseBoard("failedTooLarge") },
    fixture(view = "failedNotACatalogue", expect = "no longer returns a book catalogue") { CatalogueBrowseBoard("failedNotACatalogue") },
    fixture(view = "failedCertificate", expect = "security certificate is no longer trusted") { CatalogueBrowseBoard("failedCertificate") },
    fixture(view = "servers", expect = "Ready · no account needed") { CatalogueLibrariesBoard() },
    fixture(view = "serversMore", expect = "Kavita at work") { CatalogueLibrariesBoard(more = true) },
    fixture(view = "serverPublic", expect = "No account") { CatalogueSettingsBoard("serverPublic") },
    fixture(view = "serverAccount", expect = "Saved on this phone only.") { CatalogueSettingsBoard("serverAccount") },
    fixture(view = "serverOff", expect = "Books you downloaded stay in your library.") { CatalogueSettingsBoard("serverOff") },
    fixture(view = "serverUnsupported", expect = "Can't be browsed right now") { CatalogueSettingsBoard("serverUnsupported") },
    fixture(view = "editAddress", expect = "The full address, including any key it needs.") { CatalogueSettingsBoard("editAddress") },
    fixture(view = "removeCat", expect = "Remove Home Calibre?") { CatalogueSettingsBoard("removeCat") },
    fixture(view = "signOutAll", expect = "Sign out of everything?") { CatalogueLibrariesBoard(signOut = true) },
)

@Composable
fun CatalogueFixtureScreen(view: String) {
    val fixture = catalogueFixtures.firstOrNull { it.view == view }
    if (fixture == null) {
        // Shown, not thrown: the capture script then reports the missing text and the view name.
        FixturePage { Text("Unknown catalogue fixture: $view", color = Ember.colors.error) }
    } else {
        fixture.content()
    }
}

/** For fixtures of a part of a screen. A fixture of a whole screen draws the screen itself. */
@Composable
private fun FixturePage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Ember.colors.bg).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
    ) { content() }
}

@Composable
private fun CatalogueAddBoard(view: String) {
    val address = when (view) {
        "add", "checking" -> "https://www.gutenberg.org/ebooks/search.opds/"
        "addError" -> "https://standardebooks.org"
        "addInvalid" -> "https://books.home.lan/feed"
        "addUnreachable", "addSignin" -> "https://books.home.lan/opds"
        else -> "http://books.home.lan/opds"
    }
    val needsAccount = view in setOf("addSignin", "pwHttp", "pwHttpBlocked")
    val answer = when (view) {
        "addError" -> CatalogueValidation.WebPage
        "addInvalid" -> CatalogueValidation.NotCatalogue
        "addUnreachable" -> CatalogueValidation.Unreachable
        "addSignin", "pwHttpBlocked" -> CatalogueValidation.NeedsBasic
        "cert" -> CatalogueValidation.CertificateFailure
        "unsupported" -> CatalogueValidation.Unsupported(rootAnswered401 = false)
        "unsupportedBlocked" -> CatalogueValidation.Unsupported(rootAnswered401 = true)
        else -> CatalogueValidation.Accepted("Gutenberg Books")
    }
    val flow = remember(view) {
        CatalogueAddFlow(
            validator = CatalogueAddressValidator { _, _ ->
                if (view == "checking") kotlinx.coroutines.awaitCancellation() else answer
            },
            store = FixtureCatalogueAddStore,
            allowHttp = view != "httpIos",
            initialAddress = address,
            needsAccount = needsAccount,
            username = if (view == "addSignin") "rox" else "",
        )
    }
    val state by flow.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(flow) {
        if (view !in setOf("add", "checking")) flow.submit()
        else if (view == "checking") flow.submit()
    }
    CatalogueAddScreenContent(
        state = state,
        flow = flow,
        onBack = {},
        modifier = Modifier.fillMaxSize(),
        scope = scope,
        requestErrorFocus = false,
    )
    if (state.dialog != null) CatalogueAddDialogs(
        state,
        flow,
        flow::dismissDialog,
        scope,
        deviceName = if (view == "httpIos") "this iPhone" else "this phone",
    )
}

@Composable
private fun CatalogueGetBooksBoard(view: String) {
    val presets = fixturePresets()
    val catalogueRows = when (view) {
        "cataloguesEmpty" -> emptyList()
        "catalogues" -> listOf(fixtureCatalogue("gutenberg", "Project Gutenberg", "gutenberg.org", CatalogueAccountState.Public), fixtureCatalogue("home-calibre", "Home Calibre", "books.home.lan", CatalogueAccountState.SignedIn("rok")))
        else -> listOf(fixtureCatalogue("gutenberg", "Project Gutenberg", "gutenberg.org", CatalogueAccountState.Public))
    }
    CatalogueSourcesContent(
        viewState = CatalogueSourcesViewState(
            catalogues = catalogueRows,
            presets = if (view == "cataloguesEmpty") presets else presets.drop(1),
            isLoadingPresets = false,
            activeOrFailedDownloads = 2,
        ),
        onBack = {},
        onDownloads = {},
        onCatalogueClick = {},
        onPresetClick = {},
        onAnotherClick = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CataloguePresetBoard() {
    com.retro99.catalogue.ui.sources.PresetDetailScreen(
        preset = fixturePresets().first(),
        isChecking = false,
        error = null,
        onBack = {},
        onAdd = {},
        onTerms = {},
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CatalogueSignInBoard(wrongDetails: Boolean) {
    val flow = remember(wrongDetails) {
        CatalogueAddFlow(
            validator = CatalogueAddressValidator { _, _ -> CatalogueValidation.InvalidCredentials },
            store = FixtureCatalogueAddStore,
            allowHttp = true,
            initialAddress = "https://books.home.lan/opds",
            needsAccount = true,
            username = "rok",
        )
    }
    val state by flow.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(flow) { if (wrongDetails) flow.submit(name = "Home Calibre") }
    CatalogueSignInBackdrop()
    CatalogueSignInSheet(
        preset = fixtureHomeCalibre(),
        state = state,
        flow = flow,
        onDismiss = {},
        scope = scope,
        deviceName = "this phone",
        requestErrorFocus = false,
    )
}

@Composable
private fun CatalogueSignInBackdrop() {
    Column(Modifier.fillMaxSize().background(Ember.colors.bg)) {
        EmberTopBar(
            title = "Home Calibre",
            subtitle = "Book catalogue · OPDS",
            onBack = {},
        )
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("⌕  Search Home Calibre", style = Ember.type.meta.copy(fontSize = 17.sp), color = Ember.colors.ink2)
            Text("Popular this week", style = Ember.type.cardTitle.copy(fontSize = 23.sp), color = Ember.colors.ink)
            Row {
                listOf("Frankenstein", "Pride and Prejudice", "Moby Dick", "Dracula").forEachIndexed { index, title ->
                    Column(Modifier.weight(1f).padding(end = 10.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (index % 2 == 0) Ember.colors.accent else Ember.colors.navActive),
                            contentAlignment = Alignment.BottomStart,
                        ) { Text(title, modifier = Modifier.padding(8.dp), color = Ember.colors.onAccent, style = Ember.type.meta.copy(fontSize = 12.sp)) }
                        Text(title, style = Ember.type.meta.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                    }
                }
            }
            Text("BROWSE", style = Ember.type.section, color = Ember.colors.accentText)
            Text("▱   Latest additions                         ›", style = Ember.type.meta.copy(fontSize = 18.sp), color = Ember.colors.ink)
        }
    }
}

private fun fixtureCatalogue(id: String, name: String, host: String, account: CatalogueAccountState) =
    CatalogueSourceRow(
        config = ServerConfig(id, name, ServerType.Opds, "https://$host/opds", 0),
        host = host,
        account = account,
        lastError = null,
    )

private fun fixtureHomeCalibre() = CataloguePreset(
    id = "home-calibre",
    name = "Home Calibre",
    address = "https://books.home.lan/opds",
    description = "Your personal book catalogue.",
    shortDescription = "Your personal book catalogue.",
    accountLabel = "No account needed",
    needsAccount = true,
    termsUrl = null,
)

private fun fixturePresets() = listOf(
    CataloguePreset("project-gutenberg", "Project Gutenberg", "https://www.gutenberg.org/ebooks/search.opds/", "A volunteer-run library of older books, mostly ones whose US copyright has expired. Books come as EPUB files you can read in Parrot.", "Older books, mostly ones whose US copyright has expired.", "No account needed", false, "https://www.gutenberg.org/policy/terms_of_use.html"),
    CataloguePreset("standard-ebooks", "Standard Ebooks", "https://standardebooks.org/feeds/opds", "Carefully edited editions of classic books.", "Carefully edited editions of classic books.", "Patron account needed", true, null),
)

private object FixtureCatalogueAddStore : CatalogueAddStore {
    override suspend fun existingAddresses(): Set<String> = emptySet()
    override suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?): String = "fixture-source"
}
