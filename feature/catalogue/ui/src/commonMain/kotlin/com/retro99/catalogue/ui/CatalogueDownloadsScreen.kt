package com.retro99.catalogue.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.retro99.catalogue.ui.downloads.*
import com.retro99.catalogue.ui.browse.*
import com.retro99.catalogue.ui.add.catalogueDeviceName
import org.koin.compose.viewmodel.koinViewModel

/** Downloads from catalogues. */
@Composable
fun CatalogueDownloadsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, onRead: (String) -> Unit = {}) {
    val viewModel: DownloadsViewModel = koinViewModel()
    val page = viewModel.page
    val state by page.state.collectAsState()
    DisposableEffect(page) { page.enter(); viewModel.visible(); onDispose { viewModel.hidden(); page.leave() } }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.openBookId) { state.openBookId?.let { onRead(it); page.navigationHandled() } }
    if (state.closed) return
    CatalogueDownloadsContent(state.rows, onBack = { page.leave(); onBack() }, onAction = page::action, modifier = modifier.fillMaxSize())
    state.signIn?.let { SignInSheet(state.signInCatalogue, it, CatalogueBrowseActions(onSignIn = page::signIn, onDismissSignIn = page::dismissSignIn), catalogueDeviceName()) }
}
