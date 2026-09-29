package com.retro99.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.settings.ui.model.toDomainModel
import com.retro99.translations.StringRes
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import resources.translations.general_close
import resources.translations.reader_settings_title
import resources.translations.reader_tab_controls
import resources.translations.reader_tab_page
import resources.translations.reader_tab_progress
import resources.translations.reader_tab_read_aloud
import resources.translations.reader_tab_text
import resources.translations.settings_changed
import resources.translations.settings_retry
import resources.translations.settings_save_failed
import resources.translations.settings_undo

/** Share of the screen height used by the live preview at the top. */
private const val PREVIEW_WEIGHT = 0.4f

/** Remembers the last tab across openings of the sheet within one app run. */
private object ReaderSettingsTabMemory {
    var lastTab: ReaderSettingsTab = ReaderSettingsTab.TEXT
}

/**
 * Reader settings sheet: five tabs (Text, Page, Progress, Controls, Read aloud). The book page
 * stays visible above the sheet and updates live, so the sheet has no preview of its own.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    preview: @Composable (
        settings: ReaderSettingsDomainModel,
        showReadAloudHighlight: Boolean,
        modifier: Modifier,
    ) -> Unit = { _, _, _ -> },
    viewModel: SettingsViewModel = koinViewModel(),
) {
    BaseScreen(
        modifier = modifier,
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        SettingsScreenContent(
            viewState = viewState,
            intentDispatcher = intentDispatcher,
            onClose = onClose,
            preview = preview,
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsScreenContent(
    viewState: SettingsViewState,
    intentDispatcher: IntentDispatcher<SettingsIntent>,
    onClose: (() -> Unit)?,
    preview: @Composable (
        settings: ReaderSettingsDomainModel,
        showReadAloudHighlight: Boolean,
        modifier: Modifier,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val snackbarHostState = remember { SnackbarHostState() }
    val undoMessage = stringResource(StringRes.settings_changed)
    val undoLabel = stringResource(StringRes.settings_undo)
    val saveFailureMessage = stringResource(StringRes.settings_save_failed)
    val retryLabel = stringResource(StringRes.settings_retry)
    var selectedTab by remember { mutableStateOf(ReaderSettingsTabMemory.lastTab) }
    val fontPickerLauncher = rememberFilePickerLauncher(
        type = PickerType.File(extensions = listOf("ttf", "otf", "woff", "woff2")),
        mode = PickerMode.Single,
    ) { file ->
        if (file == null) {
            intentDispatcher(SettingsIntent.OnCustomFontImportCancelled)
        } else {
            intentDispatcher(SettingsIntent.OnCustomFontSelected(file))
        }
    }

    LaunchedEffect(viewState.undoRequestId) {
        if (viewState.undoReaderSettings != null) {
            val result = snackbarHostState.showSnackbar(
                message = undoMessage,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            when (result) {
                SnackbarResult.ActionPerformed ->
                    intentDispatcher(SettingsIntent.OnUndoSettingsChange)

                SnackbarResult.Dismissed ->
                    intentDispatcher(SettingsIntent.OnDismissSettingsUndo)
            }
        }
    }

    LaunchedEffect(viewState.settingSaveFailureRequestId) {
        if (viewState.settingSaveFailureRequestId != null) {
            val result = snackbarHostState.showSnackbar(
                message = saveFailureMessage,
                actionLabel = retryLabel,
                duration = SnackbarDuration.Indefinite,
            )
            when (result) {
                SnackbarResult.ActionPerformed ->
                    intentDispatcher(SettingsIntent.OnRetrySettingsSave)

                SnackbarResult.Dismissed ->
                    intentDispatcher(SettingsIntent.OnDismissSettingsSaveFailure)
            }
        }
    }

    val tabs = listOf(
        ReaderSettingsTab.TEXT to stringResource(StringRes.reader_tab_text),
        ReaderSettingsTab.PAGE to stringResource(StringRes.reader_tab_page),
        ReaderSettingsTab.PROGRESS to stringResource(StringRes.reader_tab_progress),
        ReaderSettingsTab.CONTROLS to stringResource(StringRes.reader_tab_controls),
        ReaderSettingsTab.READ_ALOUD to stringResource(StringRes.reader_tab_read_aloud),
    )

    Box(modifier = modifier.fillMaxSize().background(colors.bg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Live preview of the sample page; not interactive.
            Box(modifier = Modifier.weight(PREVIEW_WEIGHT).fillMaxWidth()) {
                preview(
                    viewState.readerSettings.toDomainModel(),
                    selectedTab == ReaderSettingsTab.READ_ALOUD,
                    Modifier.fillMaxSize(),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f - PREVIEW_WEIGHT)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(colors.surface)
                    .then(
                        if (Ember.style.isEink) {
                            Modifier.border(2.dp, colors.line, RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                        } else {
                            Modifier
                        },
                    ),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(StringRes.reader_settings_title),
                    style = Ember.type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(StringRes.general_close),
                            tint = colors.ink,
                        )
                    }
                }
            }

            ReaderTabRow(
                tabs = tabs,
                selected = selectedTab,
                onSelected = { tab ->
                    selectedTab = tab
                    ReaderSettingsTabMemory.lastTab = tab
                },
            )

            key(selectedTab) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (selectedTab) {
                        ReaderSettingsTab.TEXT -> TextTab(
                            viewState = viewState,
                            intentDispatcher = intentDispatcher,
                            onAddFont = { fontPickerLauncher.launch() },
                        )

                        ReaderSettingsTab.PAGE -> PageTab(viewState, intentDispatcher)
                        ReaderSettingsTab.PROGRESS -> ProgressTab(viewState, intentDispatcher)
                        ReaderSettingsTab.CONTROLS -> ControlsTab(viewState, intentDispatcher)
                        ReaderSettingsTab.READ_ALOUD -> ReadAloudTab(viewState, intentDispatcher)
                    }
                }
            }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
