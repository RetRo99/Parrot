package com.retro99.catalogue.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.retro99.base.ui.compose.Ember
import com.retro99.catalogue.ui.browse.displayTitle
import com.retro99.catalogue.ui.publication.*
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Composable
fun CataloguePublicationScreen(
    sourceId: String,
    publicationRef: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCatalogueRoot: () -> Unit = {},
    onDownloads: () -> Unit = {},
    onRead: (String) -> Unit = {},
    onCatalogueSettings: () -> Unit = {},
) {
    val viewModel: CatalogueBookViewModel = koinViewModel { parametersOf(sourceId, publicationRef) }
    val page = viewModel.page
    val state by page.state.collectAsState()
    DisposableEffect(viewModel, state.book) { viewModel.visible(); onDispose { viewModel.hidden() } }
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val message = stringResource(StringRes.catalogue_download_notice, state.book?.displayTitle().orEmpty())
    val view = stringResource(StringRes.catalogue_view)
    val currentMessage by rememberUpdatedState(message)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { page.onReturn() }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.navigation) {
        when (val navigation = state.navigation) {
            BookNavigation.CatalogueRoot -> onCatalogueRoot()
            is BookNavigation.Read -> onRead(navigation.libraryBookId)
            null -> return@LaunchedEffect
        }
        page.navigationHandled()
    }
    LaunchedEffect(page) {
        page.state.map { it.downloadNotice }.distinctUntilChanged().filter { it }.collect {
            page.noticeHandled()
            if (snackbar.showSnackbar(currentMessage, actionLabel = view, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onDownloads()
        }
    }
    if (state.closed) return
    Box(modifier.fillMaxSize()) {
        CatalogueBookContentScreen(sourceId, state, CatalogueBookActions(
            onBack = onBack, onDownload = page::download, onFiles = page::openFiles, onCloseFiles = page::closeFiles, onChooseFile = page::chooseFile,
            onCancel = page::cancelDownload, onRead = page::readNow, onFailureAction = page::failureAction,
            onProvider = { link -> runCatching { uri.openUri(link) } }, onSignIn = page::signIn, onDismissSignIn = page::dismissSignIn,
            onRetryLoad = page::retryLoad, onCatalogueSettings = onCatalogueSettings,
        ))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { data ->
            Snackbar(data, containerColor = Ember.colors.ink, contentColor = Ember.colors.bg, actionColor = Ember.colors.bg)
        }
    }
}
