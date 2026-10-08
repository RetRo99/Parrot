package com.retro99.catalogue.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.*
import com.retro99.catalogue.ui.add.*
import com.retro99.catalogue.ui.browse.catalogueTimeAgo
import com.retro99.catalogue.ui.browse.timeAgoText
import com.retro99.catalogue.ui.sources.CatalogueAddDialogs
import com.retro99.catalogue.ui.sources.CataloguePasswordField
import com.retro99.catalogue.ui.sources.addErrorMessage
import com.retro99.server.api.*
import com.retro99.translations.StringRes
import com.retro99.translations.PluralRes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.*
import kotlin.time.Clock

@Composable
fun CatalogueSettingsScreen(
    sourceId: String,
    onBack: () -> Unit,
    onBrowse: (String) -> Unit,
    initiallyEditAccount: Boolean = false,
) {
    val viewModel: CatalogueSettingsViewModel = koinViewModel(key = "catalogue-settings-$sourceId") { parametersOf(sourceId) }
    val controller = viewModel.controller
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<CatalogueAddFlow?>(null) }
    var initialHandled by remember { mutableStateOf(false) }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LaunchedEffect(state.source, initiallyEditAccount) {
        if (!initialHandled && state.source != null) {
            initialHandled = true
            if (initiallyEditAccount) editor = controller.openAccountEditor()
        }
    }
    val dismiss = { controller.dismissDialog(); editor = null }
    val editorState = editor?.state?.collectAsState()?.value
    LaunchedEffect(editorState?.addedSourceId) {
        if (editorState?.addedSourceId != null) dismiss()
    }
    CatalogueSettingsContent(
        state = state,
        onBack = onBack,
        onBrowse = { if (state.canBrowse) onBrowse(sourceId) },
        onShowAddress = controller::toggleAddressVisibility,
        onEditAddress = { editor = controller.openAddressEditor() },
        onEditAccount = { editor = controller.openAccountEditor() },
        onRemoveAccount = controller::askRemoveAccount,
        onEnabled = { scope.launch { controller.setEnabled(it) } },
        onRemoveCatalogue = { scope.launch { controller.askRemoveCatalogue() } },
        onConfirmRemoveAccount = { scope.launch { controller.confirmRemoveAccount() } },
        onConfirmRemoveCatalogue = { scope.launch { controller.confirmRemoveCatalogue() } },
        onDismissDialog = dismiss,
    )
    if (editor != null && state.dialog in setOf(CatalogueSettingsDialog.Address, CatalogueSettingsDialog.Account)) {
        CatalogueSettingsEditor(requireNotNull(editor), state.dialog == CatalogueSettingsDialog.Account, state.source?.config?.name.orEmpty(), dismiss)
    }
}

/** Production content is also the fixture surface; callbacks never persist fixture data. */
@Composable
fun CatalogueSettingsContent(
    state: CatalogueSettingsState,
    onBack: () -> Unit = {},
    onBrowse: () -> Unit = {},
    onShowAddress: () -> Unit = {},
    onEditAddress: () -> Unit = {},
    onEditAccount: () -> Unit = {},
    onRemoveAccount: () -> Unit = {},
    onEnabled: (Boolean) -> Unit = {},
    onRemoveCatalogue: () -> Unit = {},
    onConfirmRemoveAccount: () -> Unit = {},
    onConfirmRemoveCatalogue: () -> Unit = {},
    onDismissDialog: () -> Unit = {},
    now: Long = Clock.System.now().toEpochMilliseconds(),
    deviceName: String = catalogueDeviceName(),
) {
    val source = state.source
    Column(Modifier.fillMaxSize().background(Ember.colors.bg).statusBarsPadding()) {
        EmberTopBar(source?.config?.name.orEmpty(), onBack = onBack)
        if (source == null) {
            if (state.failed) Text(stringResource(StringRes.settings_server_list_load_failed), color = Ember.colors.error, modifier = Modifier.padding(24.dp))
            return@Column
        }
        val status = if (source.config.enabled) source.status else source.status.copy(access = ServerAccessState.TurnedOff)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CatalogueTile(source.config.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    CatalogueStatusLine(catalogueLibraryStatus(status, now), now)
                    val checked = catalogueSettingsCheckedAt(status)
                    val header = if (checked == null) stringResource(StringRes.catalogue_settings_header)
                        else stringResource(StringRes.catalogue_settings_header_time, timeAgoText(catalogueTimeAgo(checked, now)))
                    Text(header, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 19.sp), color = Ember.colors.ink2)
                }
            }
            if (state.canBrowse) {
                CatalogueActionButton(stringResource(StringRes.catalogue_browse), onBrowse, true, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), !state.busy,
                    stringResource(StringRes.catalogue_a11y_browse, source.config.name))
            }
            if (!source.config.enabled || status.access == ServerAccessState.TurnedOff) {
                EmberCard(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), contentPadding = 16.dp) {
                    Text(stringResource(StringRes.catalogue_turned_off_note), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Ember.colors.ink)
                    Spacer(Modifier.height(12.dp))
                    CatalogueActionButton(stringResource(StringRes.catalogue_turn_on), { onEnabled(true) }, true, enabled = !state.busy)
                }
            } else if (status.access == ServerAccessState.SignInUnsupported) {
                CatalogueMessageBox(stringResource(StringRes.catalogue_unsupported_note_title), stringResource(StringRes.catalogue_unsupported_note_body), Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            if (state.failed) Text(stringResource(StringRes.settings_server_operation_failed), style = Ember.type.meta, color = Ember.colors.error, modifier = Modifier.padding(24.dp))
            EmberSectionHeader(stringResource(StringRes.catalogue_settings_group_address))
            CatalogueGroupCard {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // A zero-width break opportunity at every boundary, including long keys and ports.
                    Text(state.displayAddress.toCharArray().joinToString("\u200b"), style = Ember.type.label.copy(fontSize = 16.sp, lineHeight = 21.sp, lineBreak = LineBreak.Simple), color = Ember.colors.ink,
                        modifier = Modifier.weight(1f).clearAndSetSemantics { text = AnnotatedString(state.displayAddress) })
                    if (state.hasKey) {
                        Spacer(Modifier.width(10.dp))
                        CatalogueActionButton(stringResource(if (state.showAddress) StringRes.catalogue_address_hide else StringRes.catalogue_address_show), onShowAddress,
                            accessibility = stringResource(if (state.showAddress) StringRes.catalogue_a11y_address_hide else StringRes.catalogue_a11y_address_show))
                    }
                }
                EmberRowDivider()
                CatalogueSettingAction(stringResource(StringRes.catalogue_edit_address), onEditAddress, enabled = !state.busy)
            }
            EmberSectionHeader(stringResource(StringRes.catalogue_settings_group_account))
            CatalogueGroupCard {
                if (status.access != ServerAccessState.SignInUnsupported) {
                    EmberSettingRow(
                        title = source.accountName?.let { stringResource(StringRes.catalogue_status_signed_in, it) } ?: stringResource(StringRes.catalogue_account_none_title),
                        subtitle = if (source.accountName != null) stringResource(StringRes.catalogue_account_saved_on_device, deviceName)
                            else if (status.access == ServerAccessState.Public) stringResource(StringRes.catalogue_account_none_body) else null,
                    )
                    EmberRowDivider()
                    CatalogueSettingAction(stringResource(if (source.accountName == null) StringRes.catalogue_add_account_details else StringRes.catalogue_edit_account_details), onEditAccount, enabled = !state.busy)
                    if (source.accountName != null) EmberRowDivider()
                }
                if (source.accountName != null || status.access == ServerAccessState.SignInUnsupported) {
                    CatalogueSettingAction(stringResource(StringRes.catalogue_remove_account_details), onRemoveAccount, enabled = !state.busy)
                }
            }
            EmberSectionHeader(stringResource(StringRes.catalogue_settings_group_catalogue))
            CatalogueGroupCard {
                EmberSwitchRow(
                    title = stringResource(StringRes.catalogue_use_this_catalogue),
                    subtitle = stringResource(if (source.config.enabled) StringRes.catalogue_use_this_catalogue_on else StringRes.catalogue_use_this_catalogue_off),
                    enabled = !state.busy,
                    checked = source.config.enabled,
                    onCheckedChange = onEnabled,
                )
                EmberRowDivider()
                CatalogueSettingAction(stringResource(StringRes.catalogue_remove_catalogue_row), onRemoveCatalogue, destructive = true, enabled = !state.busy)
            }
        }
    }
    if (source != null) when (state.dialog) {
        CatalogueSettingsDialog.RemoveAccount -> CatalogueConfirmDialog(
            stringResource(StringRes.catalogue_remove_account_title, source.config.name), stringResource(StringRes.catalogue_remove_account_body),
            stringResource(StringRes.catalogue_remove), onConfirmRemoveAccount, onDismissDialog, state.busy,
        )
        CatalogueSettingsDialog.RemoveCatalogue -> state.downloadedBooks?.let { count ->
            val body = if (count == 0L) stringResource(StringRes.catalogue_remove_body_none, deviceName)
                else pluralStringResource(PluralRes.catalogue_remove_body_count, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), count, deviceName)
            CatalogueConfirmDialog(stringResource(StringRes.catalogue_remove_title, source.config.name), body, stringResource(StringRes.catalogue_remove_catalogue), onConfirmRemoveCatalogue, onDismissDialog, state.busy)
        }
        else -> Unit
    }
}

@Composable
private fun CatalogueSettingAction(text: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Ember.type.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = if (destructive) Ember.colors.destructive else Ember.colors.accentText, modifier = Modifier.weight(1f))
        if (!destructive) EmberChevron()
    }
}

@Composable
private fun CatalogueGroupCard(content: @Composable ColumnScope.() -> Unit) {
    EmberCard(contentPadding = 0.dp, modifier = Modifier.padding(horizontal = 20.dp)
        .then(if (!Ember.style.isEink) Modifier.border(Ember.style.border, Ember.colors.line, RoundedCornerShape(20.dp)) else Modifier), content = content)
}

@Composable
private fun CatalogueConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, dismiss: () -> Unit, busy: Boolean) {
    EmberDialog(onDismissRequest = { if (!busy) dismiss() }, title = title, body = AnnotatedString(body), actions = emptyList()) {
        CatalogueActionButton(confirm, onConfirm, filled = true, modifier = Modifier.fillMaxWidth(), enabled = !busy, destructive = true)
        CatalogueActionButton(stringResource(StringRes.catalogue_cancel), dismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy, border = false)
    }
}

@Composable
fun CatalogueSettingsEditor(flow: CatalogueAddFlow, accountOnly: Boolean, name: String, onDismiss: () -> Unit, deviceName: String = catalogueDeviceName(), requestFocus: Boolean = true) {
    val state by flow.state.collectAsState()
    val scope = rememberCoroutineScope()
    val passwordFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val addressLabel = stringResource(StringRes.catalogue_add_address_label)
    LaunchedEffect(state.error, state.focusAddress) {
        if (requestFocus && state.error == CatalogueAddError.WrongCredentials) passwordFocus.requestFocus()
        else if (requestFocus && state.focusAddress && !accountOnly) addressFocus.requestFocus()
    }
    if (state.dialog == null) EmberDialog(onDismissRequest = onDismiss,
        title = stringResource(if (accountOnly) StringRes.catalogue_sign_in_title else StringRes.catalogue_edit_address, *if (accountOnly) arrayOf(name) else emptyArray()), actions = emptyList()) {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!accountOnly) {
                Text(stringResource(StringRes.catalogue_edit_address_origin_warning), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ember.colors.ink2)
                Text(addressLabel, style = Ember.type.label, color = Ember.colors.ink)
                BasicTextField(
                    value = state.address, onValueChange = flow::updateAddress, readOnly = !state.isEditable,
                    textStyle = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 22.sp, color = Ember.colors.ink), cursorBrush = SolidColor(Ember.colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp).focusRequester(addressFocus)
                        .background(Ember.colors.bg, RoundedCornerShape(16.dp)).border(2.dp, if (state.error != null) Ember.colors.error else Ember.colors.accent, RoundedCornerShape(16.dp)).padding(16.dp)
                        .semantics { contentDescription = addressLabel },
                )
                Text(stringResource(StringRes.catalogue_edit_address_helper), style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.ink2)
            } else Text(stringResource(StringRes.catalogue_sign_in_body, deviceName), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ember.colors.ink2)
            if (state.needsAccount) {
                EmberTextField(value = state.username, onValueChange = flow::updateUsername, label = stringResource(StringRes.catalogue_username), enabled = state.isEditable,
                    isError = state.error == CatalogueAddError.WrongCredentials, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                CataloguePasswordField(state.password, flow::updatePassword, enabled = state.isEditable, error = state.error == CatalogueAddError.WrongCredentials,
                    errorText = if (state.error == CatalogueAddError.WrongCredentials) addErrorMessage(CatalogueAddError.WrongCredentials) else null, focusRequester = passwordFocus)
                Text(stringResource(StringRes.catalogue_password_helper), style = Ember.type.meta.copy(fontSize = 13.sp), color = Ember.colors.ink2)
            }
            if (state.error != null && state.error != CatalogueAddError.WrongCredentials) Text(addErrorMessage(requireNotNull(state.error)), style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val action = when (state.phase) {
                CatalogueAddPhase.Checking -> StringRes.catalogue_add_checking
                CatalogueAddPhase.SigningIn -> StringRes.catalogue_add_signing_in
                CatalogueAddPhase.Idle -> if (accountOnly) StringRes.catalogue_sign_in else StringRes.catalogue_save
            }
            CatalogueActionButton(stringResource(action), { scope.launch { flow.submit() } }, true, Modifier.fillMaxWidth(), state.isEditable)
            CatalogueActionButton(stringResource(StringRes.catalogue_cancel), onDismiss, modifier = Modifier.fillMaxWidth(), border = false)
        }
    }
    CatalogueAddDialogs(state, flow, flow::dismissDialog, scope, deviceName)
}

@Composable
fun CatalogueLibraryCard(
    config: ServerConfig,
    allSources: List<ServerConfig>,
    status: CatalogueAccessStatus,
    onDetails: () -> Unit,
    onAction: (CatalogueLibraryAction) -> Unit,
    now: Long = Clock.System.now().toEpochMilliseconds(),
    enabled: Boolean = true,
    patronAccount: Boolean = false,
) {
    val presented = catalogueLibraryStatus(if (config.enabled) status else status.copy(access = ServerAccessState.TurnedOff), now)
    val summary = catalogueStatusText(presented, now)
    val cardDescription = stringResource(StringRes.catalogue_a11y_library_card, config.name, summary)
    EmberCard(contentPadding = 0.dp, onClick = onDetails, modifier = Modifier
        .then(if (!Ember.style.isEink) Modifier.border(Ember.style.border, Ember.colors.line, RoundedCornerShape(20.dp)) else Modifier)
        .semantics { contentDescription = cardDescription }) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                CatalogueTile(config.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(config.name, style = Ember.type.label.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                    Text(rowAddress(config, allSources), style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.ink2)
                }
                EmberChevron()
            }
            Spacer(Modifier.height(12.dp))
            if (presented.action in setOf(CatalogueLibraryAction.Account, CatalogueLibraryAction.Details, CatalogueLibraryAction.Retry)) {
                val body = when (presented.action) {
                    CatalogueLibraryAction.Account -> if (patronAccount) StringRes.catalogue_status_sign_in_needed_body_patron else StringRes.catalogue_status_sign_in_needed_body
                    CatalogueLibraryAction.Details -> StringRes.catalogue_status_unsupported_body
                    else -> StringRes.catalogue_status_error_body
                }
                CatalogueMessageBox(summary, stringResource(body))
            } else {
                CatalogueStatusLine(presented, now)
                val sub = presented.checkedAt?.let { stringResource(StringRes.catalogue_checked_time, timeAgoText(catalogueTimeAgo(it, now))) }
                    ?: if (presented.action == CatalogueLibraryAction.TurnOn) stringResource(StringRes.catalogue_status_off_body) else null
                if (sub != null) Text(sub, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Ember.colors.ink2, modifier = Modifier.padding(start = 16.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                val label = when (presented.action) {
                    CatalogueLibraryAction.Browse -> StringRes.catalogue_browse
                    CatalogueLibraryAction.Account -> StringRes.catalogue_add_account_details
                    CatalogueLibraryAction.Details -> StringRes.catalogue_details
                    CatalogueLibraryAction.TurnOn -> StringRes.catalogue_turn_on
                    CatalogueLibraryAction.Retry -> StringRes.catalogue_try_again
                }
                val a11y = when (presented.action) {
                    CatalogueLibraryAction.Browse -> StringRes.catalogue_a11y_browse
                    CatalogueLibraryAction.Account -> StringRes.catalogue_a11y_add_account_details
                    CatalogueLibraryAction.Details -> StringRes.catalogue_a11y_details
                    CatalogueLibraryAction.TurnOn -> StringRes.catalogue_a11y_turn_on
                    CatalogueLibraryAction.Retry -> StringRes.catalogue_a11y_try_again
                }
                CatalogueActionButton(stringResource(label), { onAction(presented.action) }, presented.action in setOf(CatalogueLibraryAction.Account, CatalogueLibraryAction.Retry), enabled = enabled,
                    accessibility = stringResource(a11y, config.name), modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(StringRes.catalogue_type_name), style = Ember.type.meta.copy(fontSize = 12.sp), color = Ember.colors.ink2)
            }
        }
    }
}

@Composable
private fun CatalogueTile(name: String) {
    Box(Modifier.size(44.dp).background(Ember.colors.navActive, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = Ember.type.cardTitle.copy(fontSize = 20.sp), color = Ember.colors.navActiveContent)
    }
}

@Composable
private fun catalogueStatusText(status: CatalogueLibraryStatus, now: Long): String = when {
    status.isLastError -> stringResource(StringRes.catalogue_error_time, timeAgoText(catalogueTimeAgo(status.errorAt ?: now, now)))
    else -> when (val access = status.access) {
        ServerAccessState.Public -> stringResource(StringRes.catalogue_status_public)
        is ServerAccessState.SignedIn -> stringResource(StringRes.catalogue_status_signed_in, access.name)
        ServerAccessState.SignInNeeded -> stringResource(StringRes.catalogue_status_sign_in_needed_title)
        ServerAccessState.SignInUnsupported -> stringResource(StringRes.catalogue_status_unsupported_title)
        ServerAccessState.TurnedOff -> stringResource(StringRes.catalogue_status_off_title)
    }
}

@Composable
private fun CatalogueStatusLine(status: CatalogueLibraryStatus, now: Long) {
    val color = when {
        status.isLastError || status.access in setOf(ServerAccessState.SignInNeeded, ServerAccessState.SignInUnsupported) -> Ember.colors.error
        status.access is ServerAccessState.SignedIn -> Ember.colors.success
        status.access == ServerAccessState.TurnedOff -> Ember.colors.ink2
        else -> Ember.colors.ink
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val hollow = status.access == ServerAccessState.Public && !status.isLastError
        Box(Modifier.size(8.dp).then(if (hollow) Modifier.border(1.dp, Ember.colors.ink2, CircleShape) else Modifier.background(color, CircleShape)))
        Spacer(Modifier.width(8.dp))
        Text(catalogueStatusText(status, now), style = Ember.type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = color)
    }
}

@Composable
private fun CatalogueMessageBox(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Ember.colors.errorContainer, RoundedCornerShape(14.dp))
        .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, RoundedCornerShape(14.dp)) else Modifier).padding(14.dp)) {
        Text(title, style = Ember.type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Ember.colors.error)
        Spacer(Modifier.height(3.dp))
        Text(body, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Ember.colors.error)
    }
}

@Composable
fun CatalogueActionButton(text: String, onClick: () -> Unit, filled: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, accessibility: String = text, destructive: Boolean = false, border: Boolean = true) {
    Row(modifier.heightIn(min = if (filled) 52.dp else 44.dp)
        .background(if (filled) { if (destructive) Ember.colors.destructive else Ember.colors.accent } else Ember.colors.surface, CircleShape)
        .then(if (!filled && border) Modifier.border(Ember.style.border, Ember.colors.line, CircleShape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = accessibility }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text(text, style = Ember.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = if (filled) Ember.colors.onAccent else if (border) Ember.colors.ink else Ember.colors.accentText)
    }
}
