package com.retro99.parrot.fixtures

import androidx.compose.runtime.*
import com.retro99.base.ui.IntentDispatcher
import com.retro99.catalogue.ui.add.*
import com.retro99.catalogue.ui.settings.*
import com.retro99.server.api.*
import com.retro99.settings.ui.servers.*
import com.retro99.settings.ui.servers.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock

private val fixtureNow = Clock.System.now().toEpochMilliseconds()

private fun catalogue(id: String, name: String, address: String, enabled: Boolean = true) =
    ServerConfig(id, name, ServerType.Opds, address, 0, enabled = enabled)

@Composable
fun CatalogueLibrariesBoard(more: Boolean = false, signOut: Boolean = false) {
    val catalogues = if (more) listOf(
        CatalogueSourceUiModel(catalogue("kavita", "Kavita at work", "https://kavita.office.example/opds").toUiModel(), CatalogueAccessStatus(ServerAccessState.SignInUnsupported, CatalogueLastCheck(lastErrorAt = fixtureNow - 3_600_000))),
        CatalogueSourceUiModel(catalogue("old", "Old Calibre", "https://calibre.old.lan/opds", false).toUiModel(), CatalogueAccessStatus(ServerAccessState.TurnedOff)),
        CatalogueSourceUiModel(catalogue("club", "Club library", "https://opds.bookclub.example/opds").toUiModel(), CatalogueAccessStatus(lastCheck = CatalogueLastCheck(lastError = CatalogueErrorKind.Unreachable, lastErrorAt = fixtureNow - 7_200_000))),
    ) else listOf(
        CatalogueSourceUiModel(catalogue("gutenberg", "Project Gutenberg", "https://www.gutenberg.org/ebooks/search.opds/").toUiModel(), CatalogueAccessStatus(lastCheck = CatalogueLastCheck(fixtureNow - 300_000))),
        CatalogueSourceUiModel(catalogue("home", "Home Calibre", "https://books.home.lan/opds").toUiModel(), CatalogueAccessStatus(ServerAccessState.SignedIn("rok"), CatalogueLastCheck(fixtureNow - 3_600_000))),
        CatalogueSourceUiModel(catalogue("standard", "Standard Ebooks", "https://standardebooks.org/feeds/opds").toUiModel(), CatalogueAccessStatus(ServerAccessState.SignInNeeded)),
    )
    val storyteller = ServerWithStatusUiModel(ServerUiModel("storyteller", "Storyteller", ServerType.Storyteller, "https://books.retar.si"), ServerAuthState.Authenticated("storyteller", "rok", fixtureNow - 300_000))
    ServerManagementScreenContent(
        viewState = ServerManagementViewState(isLoading = false, servers = if (more) emptyList() else listOf(storyteller), catalogueSources = catalogues),
        intentDispatcher = IntentDispatcher {}, failedLoginServerIds = emptySet(), onBack = {}, onOpenSyncAndBackup = {},
    )
    if (signOut) CatalogueSignOutEverythingDialog(hasCatalogue = true, onConfirm = {}, onDismiss = {}, deviceName = "this phone")
}

@Composable
fun CatalogueSettingsBoard(view: String) {
    val account = view in setOf("serverAccount", "serverOff", "editAddress", "removeCat")
    val off = view == "serverOff"
    val unsupported = view == "serverUnsupported"
    val address = if (view in setOf("serverAccount", "editAddress", "removeCat")) "https://books.home.lan:8443/opds/v1.2/catalog?library=fiction&apikey=7f3c9a12e4b8"
        else "https://books.home.lan/opds/v1.2/catalog?library=fiction"
    val config = catalogue("home", "Home Calibre", address, enabled = !off)
    val status = CatalogueAccessStatus(when {
        off -> ServerAccessState.TurnedOff
        unsupported -> ServerAccessState.SignInUnsupported
        account -> ServerAccessState.SignedIn("rok")
        else -> ServerAccessState.Public
    }, CatalogueLastCheck(fixtureNow - if (off) 3 * 86_400_000 else 3_600_000))
    var state by remember { mutableStateOf(CatalogueSettingsState(
        source = CatalogueSettingsSource("fixture", config, status, if (account || unsupported) "rok" else null), loading = false,
        dialog = if (view == "removeCat") CatalogueSettingsDialog.RemoveCatalogue else null,
        downloadedBooks = if (view == "removeCat") 12 else null,
    )) }
    CatalogueSettingsContent(state, onShowAddress = { state = state.copy(showAddress = !state.showAddress) }, now = fixtureNow, deviceName = "this phone")
    if (view == "editAddress") {
        val flow = remember { CatalogueAddFlow(CatalogueAddressValidator { _, _ -> CatalogueValidation.Accepted() }, FixtureEditStore(), true, initialAddress = address) }
        CatalogueSettingsEditor(flow, accountOnly = false, name = config.name, onDismiss = {}, deviceName = "this phone", requestFocus = false)
    }
}

private class FixtureEditStore : CatalogueAddStore {
    override suspend fun existingAddresses() = emptySet<String>()
    override suspend fun addValidated(name: String, address: String, account: OpdsAccountDetails?) = "home"
}
