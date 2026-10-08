package com.retro99.catalogue.ui.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.base.ui.compose.EmberSwitch
import com.retro99.base.ui.compose.EmberTextField
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.catalogue.ui.add.CatalogueAddDialog
import com.retro99.catalogue.ui.add.CatalogueAddError
import com.retro99.catalogue.ui.add.CatalogueAddFlow
import com.retro99.catalogue.ui.add.CatalogueAddPhase
import com.retro99.catalogue.ui.add.CatalogueAddStore
import com.retro99.catalogue.ui.add.CatalogueAddViewState
import com.retro99.catalogue.ui.add.CatalogueAddressValidator
import com.retro99.catalogue.ui.add.CatalogueHttpPolicy
import com.retro99.catalogue.ui.add.catalogueDeviceName
import com.retro99.translations.StringRes
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import resources.translations.catalogue_add_address_helper
import resources.translations.catalogue_add_address_label
import resources.translations.catalogue_add_anyway
import resources.translations.catalogue_add_by_address
import resources.translations.catalogue_add_catalogue
import resources.translations.catalogue_add_checking
import resources.translations.catalogue_add_error_duplicate
import resources.translations.catalogue_add_error_invalid
import resources.translations.catalogue_add_error_save_failed
import resources.translations.catalogue_add_error_sign_in_needed
import resources.translations.catalogue_add_error_unreachable
import resources.translations.catalogue_add_error_web_page
import resources.translations.catalogue_add_error_wrong_credentials
import resources.translations.catalogue_add_library_title
import resources.translations.catalogue_add_question
import resources.translations.catalogue_another_catalogue
import resources.translations.catalogue_another_catalogue_description
import resources.translations.catalogue_address_label
import resources.translations.catalogue_add_without_account
import resources.translations.catalogue_browse
import resources.translations.catalogue_cancel
import resources.translations.catalogue_certificate_body
import resources.translations.catalogue_certificate_title
import resources.translations.catalogue_change_address
import resources.translations.catalogue_downloads_title
import resources.translations.catalogue_downloads_count_accessibility
import resources.translations.catalogue_get_books
import resources.translations.catalogue_go_back
import resources.translations.catalogue_http_blocked_body
import resources.translations.catalogue_http_blocked_title
import resources.translations.catalogue_http_body
import resources.translations.catalogue_http_title
import resources.translations.catalogue_needs_account
import resources.translations.catalogue_needs_account_helper
import resources.translations.catalogue_no_account_needed
import resources.translations.catalogue_ok
import resources.translations.catalogue_opds_card_description
import resources.translations.catalogue_password
import resources.translations.catalogue_password_helper
import resources.translations.catalogue_password_http_blocked_body
import resources.translations.catalogue_password_http_body
import resources.translations.catalogue_password_http_title
import resources.translations.catalogue_preset_not_part_note
import resources.translations.catalogue_preset_terms_link
import resources.translations.catalogue_sign_in
import resources.translations.catalogue_sign_in_body
import resources.translations.catalogue_sign_in_title
import resources.translations.catalogue_add_signing_in
import resources.translations.catalogue_sources_lead
import resources.translations.catalogue_sources_lead_empty
import resources.translations.catalogue_sources_section_add
import resources.translations.catalogue_sources_section_start
import resources.translations.catalogue_sources_section_yours
import resources.translations.catalogue_status_off_title
import resources.translations.catalogue_status_public
import resources.translations.catalogue_status_sign_in_needed_title
import resources.translations.catalogue_status_signed_in
import resources.translations.catalogue_type_name
import resources.translations.catalogue_status_unsupported_title
import resources.translations.catalogue_unsupported_blocked_body
import resources.translations.catalogue_unsupported_blocked_title
import resources.translations.catalogue_unsupported_sign_in_body
import resources.translations.catalogue_username
import resources.translations.login_error_invalid_credentials
import resources.translations.login_hide_password
import resources.translations.login_show_password

private data class AddTarget(val preset: CataloguePreset?)

/** "Get books": the user's catalogues and the presets. */
@Composable
fun CatalogueSourcesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onBrowseCatalogue: (String) -> Unit = {},
    onDownloads: () -> Unit = {},
) {
    val viewModel: CatalogueSourcesViewModel = koinViewModel()
    val viewState by viewModel.state.collectAsState()
    val validator: CatalogueAddressValidator = koinInject()
    val store: CatalogueAddStore = koinInject()
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var selectedPreset by remember { mutableStateOf<CataloguePreset?>(null) }
    var addTarget by remember { mutableStateOf<AddTarget?>(null) }
    var signInSheet by remember { mutableStateOf(false) }
    var presetCheck by remember { mutableStateOf<CataloguePreset?>(null) }

    val flow = remember(addTarget) {
        addTarget?.let { target ->
            CatalogueAddFlow(
                validator = validator,
                store = store,
                allowHttp = CatalogueHttpPolicy.allowHttp,
                initialAddress = target.preset?.address.orEmpty(),
                needsAccount = target.preset?.needsAccount == true,
            )
        }
    }
    val observedAddState = flow?.state?.collectAsState()
    val addState = observedAddState?.value ?: CatalogueAddViewState()
    DisposableEffect(flow) {
        onDispose { flow?.cancel() }
    }

    LaunchedEffect(flow, presetCheck) {
        val preset = presetCheck ?: return@LaunchedEffect
        if (flow != null && addTarget?.preset?.id == preset.id) {
            presetCheck = null
            flow.submit(name = preset.name)
        }
    }
    LaunchedEffect(addState.addedSourceId) {
        if (addState.addedSourceId != null) {
            signInSheet = false
            presetCheck = null
            addTarget = null
            selectedPreset = null
        }
    }
    LaunchedEffect(addState.error, selectedPreset, addState.needsAccount) {
        if (selectedPreset != null && addState.error == CatalogueAddError.SignInNeeded && addState.needsAccount) {
            if (addTarget == null) addTarget = AddTarget(selectedPreset)
            signInSheet = true
        }
    }

    when {
        addTarget?.preset == null && addTarget != null -> CatalogueAddScreenContent(
            state = addState,
            flow = requireNotNull(flow),
            onBack = { addTarget = null },
            modifier = modifier,
            scope = scope,
        )
        selectedPreset != null -> PresetDetailScreen(
            preset = requireNotNull(selectedPreset),
            isChecking = addState.phase != CatalogueAddPhase.Idle,
            error = addState.error,
            onBack = {
                selectedPreset = null
                signInSheet = false
                addTarget = null
            },
            onAdd = {
                val preset = requireNotNull(selectedPreset)
                addTarget = AddTarget(preset)
                if (preset.needsAccount) signInSheet = true else presetCheck = preset
            },
            onTerms = { selectedPreset?.termsUrl?.let(uriHandler::openUri) },
            modifier = modifier,
        )
        else -> CatalogueSourcesContent(
            viewState = viewState,
            onBack = onBack,
            onDownloads = onDownloads,
            onCatalogueClick = { row -> onBrowseCatalogue(row.config.id) },
            onPresetClick = { selectedPreset = it },
            onAnotherClick = { addTarget = AddTarget(null) },
            modifier = modifier,
        )
    }

    if (signInSheet && flow != null && selectedPreset != null) {
        CatalogueSignInSheet(
            preset = selectedPreset!!,
            state = addState,
            flow = flow,
            onDismiss = { signInSheet = false },
            scope = scope,
        )
    }
    if (flow != null && addState.dialog != null) {
        CatalogueAddDialogs(
            state = addState,
            flow = flow,
            onDismiss = flow::dismissDialog,
            scope = scope,
        )
    }
}

/** Standalone entry used by the existing "Add a library" type picker. */
@Composable
fun CatalogueStandaloneAddScreen(
    onBack: () -> Unit,
    onCatalogueAdded: (String) -> Unit,
    onSelectOtherLibrary: () -> Unit = onBack,
    modifier: Modifier = Modifier,
    initialAddress: String = "",
    initialNeedsAccount: Boolean = false,
    initialUsername: String = "",
) {
    val validator: CatalogueAddressValidator = koinInject()
    val store: CatalogueAddStore = koinInject()
    val scope = rememberCoroutineScope()
    val flow = remember(initialAddress, initialNeedsAccount, initialUsername) {
        CatalogueAddFlow(
            validator = validator,
            store = store,
            allowHttp = CatalogueHttpPolicy.allowHttp,
            initialAddress = initialAddress,
            needsAccount = initialNeedsAccount,
            username = initialUsername,
        )
    }
    val state by flow.state.collectAsState()
    DisposableEffect(flow) {
        onDispose { flow.cancel() }
    }
    LaunchedEffect(state.addedSourceId) {
        state.addedSourceId?.let(onCatalogueAdded)
    }
    CatalogueAddScreenContent(
        state = state,
        flow = flow,
        onBack = {
            flow.cancel()
            onBack()
        },
        onSelectOtherLibrary = onSelectOtherLibrary,
        modifier = modifier,
        scope = scope,
    )
    if (state.dialog != null) {
        CatalogueAddDialogs(state, flow, flow::dismissDialog, scope)
    }
}

@Composable
fun CatalogueSourcesContent(
    viewState: CatalogueSourcesViewState,
    onBack: () -> Unit,
    onDownloads: () -> Unit,
    onCatalogueClick: (CatalogueSourceRow) -> Unit,
    onPresetClick: (CataloguePreset) -> Unit,
    onAnotherClick: () -> Unit,
    modifier: Modifier,
) {
    val hasCatalogues = viewState.catalogues.isNotEmpty()
    val downloadsDescription = if (viewState.activeOrFailedDownloads == 0) {
        stringResource(StringRes.catalogue_downloads_title)
    } else {
        stringResource(StringRes.catalogue_downloads_count_accessibility, viewState.activeOrFailedDownloads)
    }
    Column(modifier.fillMaxSize().background(Ember.colors.bg)) {
        EmberTopBar(
            title = stringResource(StringRes.catalogue_get_books),
            onBack = onBack,
            actions = {
                Button(
                    onClick = onDownloads,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.navActive, contentColor = Ember.colors.accentText),
                    modifier = Modifier.semantics {
                        contentDescription = downloadsDescription
                    },
                ) {
                    Text(stringResource(StringRes.catalogue_downloads_title), color = Ember.colors.accentText)
                    if (viewState.activeOrFailedDownloads > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Ember.colors.accent),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                viewState.activeOrFailedDownloads.toString(),
                                style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                color = Ember.colors.onAccent,
                            )
                        }
                    }
                }
            },
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(if (hasCatalogues) StringRes.catalogue_sources_lead else StringRes.catalogue_sources_lead_empty),
                style = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 24.sp),
                color = Ember.colors.ink2,
            )
            Spacer(Modifier.height(22.dp))
            if (hasCatalogues) {
                EmberSectionLabel(stringResource(StringRes.catalogue_sources_section_yours))
                Spacer(Modifier.height(10.dp))
                CatalogueCard {
                    viewState.catalogues.forEachIndexed { index, row ->
                        if (index > 0) EmberDivider()
                        CatalogueSourceRowItem(row, onCatalogueClick)
                    }
                }
                Spacer(Modifier.height(24.dp))
                EmberSectionLabel(stringResource(StringRes.catalogue_sources_section_add))
                Spacer(Modifier.height(10.dp))
                CatalogueCard {
                    viewState.presets.forEachIndexed { index, preset ->
                        if (index > 0) EmberDivider()
                        PresetRow(preset = preset, short = true, onClick = { onPresetClick(preset) })
                    }
                    if (viewState.presets.isNotEmpty()) EmberDivider()
                    AnotherCatalogueRow(onAnotherClick)
                }
            } else {
                EmberSectionLabel(stringResource(StringRes.catalogue_sources_section_start))
                Spacer(Modifier.height(10.dp))
                CatalogueCard {
                    viewState.presets.forEachIndexed { index, preset ->
                        if (index > 0) EmberDivider()
                        PresetRow(preset = preset, short = false, onClick = { onPresetClick(preset) })
                    }
                    if (viewState.presets.isNotEmpty()) EmberDivider()
                    AnotherCatalogueRow(onAnotherClick)
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun CatalogueSourceRowItem(row: CatalogueSourceRow, onClick: (CatalogueSourceRow) -> Unit) {
    val subtitle = when (val account = row.account) {
        CatalogueAccountState.Public -> "${row.host} · ${stringResource(StringRes.catalogue_status_public)}"
        is CatalogueAccountState.SignedIn -> "${row.host} · ${stringResource(StringRes.catalogue_status_signed_in, account.username)}"
        CatalogueAccountState.SignInNeeded -> stringResource(StringRes.catalogue_status_sign_in_needed_title)
        CatalogueAccountState.SignInUnsupported -> stringResource(StringRes.catalogue_status_unsupported_title)
        CatalogueAccountState.TurnedOff -> stringResource(StringRes.catalogue_status_off_title)
    }
    ClickableCatalogueRow(
        title = row.config.name,
        subtitle = subtitle,
        marker = row.config.name.firstOrNull()?.uppercase() ?: "B",
        onClick = { onClick(row) },
    )
}

@Composable
private fun PresetRow(preset: CataloguePreset, short: Boolean, onClick: () -> Unit) {
    ClickableCatalogueRow(
        title = preset.name,
        subtitle = if (short) preset.description else preset.shortDescription,
        marker = preset.name.first().uppercase(),
        onClick = onClick,
        detail = preset.accountLabel,
    )
}

@Composable
private fun AnotherCatalogueRow(onClick: () -> Unit) {
    ClickableCatalogueRow(
        title = stringResource(StringRes.catalogue_another_catalogue),
        subtitle = stringResource(StringRes.catalogue_another_catalogue_description),
        marker = "+",
        detail = stringResource(StringRes.catalogue_add_by_address),
        onClick = onClick,
    )
}

@Composable
private fun ClickableCatalogueRow(
    title: String,
    subtitle: String,
    marker: String,
    onClick: () -> Unit,
    detail: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Ember.colors.navActive),
            contentAlignment = Alignment.Center,
        ) {
            Text(marker, style = Ember.type.screenTitle.copy(fontSize = 25.sp), color = Ember.colors.navActiveContent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Ember.type.meta.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
            Text(subtitle, style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Ember.colors.ink2)
            if (detail != null) {
                Text(detail, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
            }
        }
        Icon(Icons.Outlined.ArrowForward, contentDescription = null, tint = Ember.colors.ink2)
    }
}

@Composable
private fun CatalogueCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Ember.colors.surface)
            .border(if (Ember.style.isEink) 2.dp else 1.dp, Ember.colors.line, RoundedCornerShape(20.dp)),
    ) { content() }
}

@Composable
private fun EmberDivider() {
    HorizontalDivider(color = Ember.colors.line, thickness = if (Ember.style.isEink) 2.dp else 1.dp)
}

@Composable
fun PresetDetailScreen(
    preset: CataloguePreset,
    isChecking: Boolean,
    error: CatalogueAddError?,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onTerms: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxSize().background(Ember.colors.bg)) {
        EmberTopBar(title = "", onBack = onBack)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(26.dp)).background(Ember.colors.navActive), contentAlignment = Alignment.Center) {
                Text(
                    preset.host.firstOrNull()?.uppercase() ?: preset.name.first().uppercase(),
                    style = Ember.type.screenTitle.copy(fontSize = 48.sp),
                    color = Ember.colors.navActiveContent,
                )
            }
            Text(preset.name, style = Ember.type.screenTitle.copy(fontSize = 34.sp, lineHeight = 42.sp), color = Ember.colors.ink)
            Text(
                preset.accountLabel,
                modifier = Modifier.clip(CircleShape).background(Ember.colors.navActive).padding(horizontal = 16.dp, vertical = 8.dp),
                style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = Ember.colors.accentText,
            )
            Text(preset.description, style = Ember.type.meta.copy(fontSize = 17.sp, lineHeight = 25.sp), color = Ember.colors.ink)
            CatalogueCard {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Text(stringResource(StringRes.catalogue_address_label), style = Ember.type.meta.copy(fontSize = 14.sp), color = Ember.colors.ink2)
                    Text(preset.host, style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                }
                if (preset.termsUrl != null) {
                    EmberDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onTerms).padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(StringRes.catalogue_preset_terms_link, preset.name),
                            modifier = Modifier.weight(1f),
                            style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                            color = Ember.colors.accentText,
                        )
                        Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = Ember.colors.accentText)
                    }
                }
            }
            Text(
                stringResource(StringRes.catalogue_preset_not_part_note, preset.name),
                style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = Ember.colors.ink2,
            )
            error?.let { CatalogueAddErrorLine(it) }
            Spacer(Modifier.height(12.dp))
        }
        Button(
            onClick = onAdd,
            enabled = !isChecking,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp).height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent),
        ) {
            if (isChecking) CircularProgressIndicator(Modifier.size(20.dp), color = Ember.colors.onAccent, strokeWidth = 2.dp)
            else Text(stringResource(StringRes.catalogue_add_catalogue), style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
fun CatalogueAddScreenContent(
    state: CatalogueAddViewState,
    flow: CatalogueAddFlow,
    onBack: () -> Unit,
    modifier: Modifier,
    scope: kotlinx.coroutines.CoroutineScope,
    onSelectOtherLibrary: () -> Unit = onBack,
    requestErrorFocus: Boolean = true,
) {
    val addressFocusRequester = remember { FocusRequester() }
    LaunchedEffect(state.focusAddress) {
        if (requestErrorFocus && state.focusAddress) addressFocusRequester.requestFocus()
    }
    Column(modifier.fillMaxSize().background(Ember.colors.bg).imePadding()) {
        EmberTopBar(
            title = stringResource(StringRes.catalogue_add_library_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(StringRes.catalogue_add_question), style = Ember.type.meta.copy(fontSize = 17.sp), color = Ember.colors.ink2)
            AddLibraryKindRow(
                title = "Storyteller",
                enabled = state.isEditable,
                onClick = onSelectOtherLibrary,
            )
            AddLibraryKindRow(
                title = "Audiobookshelf",
                enabled = state.isEditable,
                onClick = onSelectOtherLibrary,
            )
            SelectedCatalogueKindCard(enabled = state.isEditable)
            EmberTextField(
                value = state.address,
                onValueChange = flow::updateAddress,
                label = stringResource(StringRes.catalogue_add_address_label),
                labelStyle = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                helperText = stringResource(StringRes.catalogue_add_address_helper),
                isError = state.error != null,
                errorText = state.error?.let { addErrorMessage(it) },
                enabled = state.isEditable,
                modifier = Modifier.focusRequester(addressFocusRequester),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            )
            val accountDescription = stringResource(StringRes.catalogue_needs_account)
            val accountToggleDescription = "$accountDescription, ${if (state.needsAccount) "on" else "off"}"
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = state.isEditable, role = Role.Switch) { flow.updateNeedsAccount(!state.needsAccount) }
                    .semantics {
                        role = Role.Switch
                        toggleableState = if (state.needsAccount) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                        contentDescription = accountToggleDescription
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(StringRes.catalogue_needs_account), style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                    Text(stringResource(StringRes.catalogue_needs_account_helper), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Ember.colors.ink2)
                }
                EmberSwitch(checked = state.needsAccount)
            }
            if (state.needsAccount) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmberTextField(
                        value = state.username,
                        onValueChange = flow::updateUsername,
                        label = stringResource(StringRes.catalogue_username),
                        labelStyle = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                        enabled = state.isEditable,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    CataloguePasswordField(
                        value = state.password,
                        onValueChange = flow::updatePassword,
                        modifier = Modifier.weight(1f),
                        enabled = state.isEditable,
                        error = state.error == CatalogueAddError.WrongCredentials,
                        errorText = state.error?.let { addErrorMessage(it) }.takeIf { state.error == CatalogueAddError.WrongCredentials },
                    )
                }
                Text(stringResource(StringRes.catalogue_password_helper), style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Ember.colors.ink2)
            }
            Spacer(Modifier.height(10.dp))
        }
        Button(
            onClick = { scope.launch { flow.submit() } },
            enabled = state.isEditable && state.address.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp).height(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent),
        ) {
            if (state.phase != CatalogueAddPhase.Idle) {
                if (!Ember.style.isEink) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Ember.colors.onAccent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    stringResource(if (state.phase == CatalogueAddPhase.SigningIn) StringRes.catalogue_add_signing_in else StringRes.catalogue_add_checking),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                )
            } else Text(stringResource(StringRes.catalogue_add_catalogue), style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun AddLibraryKindRow(title: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ember.colors.surface)
            .border(if (Ember.style.isEink) 2.dp else 1.dp, Ember.colors.line, RoundedCornerShape(14.dp))
            .selectable(selected = false, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp).border(if (Ember.style.isEink) 2.dp else 1.dp, Ember.colors.ink2, CircleShape))
        Text(title, style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
    }
}

@Composable
private fun SelectedCatalogueKindCard(enabled: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(if (Ember.style.isEink) Ember.colors.surface else Ember.colors.navActive)
            .border(if (Ember.style.isEink) 3.dp else 2.dp, Ember.colors.accent, shape)
            .selectable(selected = true, enabled = enabled, role = Role.RadioButton, onClick = {})
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier.size(22.dp).border(2.dp, Ember.colors.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(10.dp).clip(CircleShape).background(Ember.colors.accent)) }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(StringRes.catalogue_type_name), style = Ember.type.meta.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
                Text("OPDS", style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink2)
            }
            Text(stringResource(StringRes.catalogue_opds_card_description), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Ember.colors.ink2)
        }
    }
}

@Composable
internal fun CataloguePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    errorText: String? = null,
    focusRequester: FocusRequester? = null,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordLabel = stringResource(StringRes.catalogue_password)
    LaunchedEffect(error, focusRequester) {
        if (error && focusRequester != null) focusRequester.requestFocus()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(StringRes.catalogue_password),
            style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = Ember.colors.ink,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = Ember.type.meta.copy(fontSize = 15.sp, color = Ember.colors.ink),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            cursorBrush = SolidColor(Ember.colors.accent),
            modifier = Modifier.fillMaxWidth().height(52.dp)
                .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester))
                .semantics { contentDescription = passwordLabel },
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                        .background(Ember.colors.surface)
                        .border(if (error || Ember.style.isEink) 2.dp else 1.dp, if (error) Ember.colors.destructive else Ember.colors.line, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) Text(stringResource(StringRes.catalogue_password), color = Ember.colors.ink2, style = Ember.type.meta.copy(fontSize = 15.sp))
                            inner()
                        }
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = stringResource(if (passwordVisible) StringRes.login_hide_password else StringRes.login_show_password),
                                tint = Ember.colors.ink2,
                            )
                        }
                    }
                }
            },
        )
        if (error && errorText != null) Text(errorText, color = Ember.colors.destructive, style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueSignInSheet(
    preset: CataloguePreset,
    state: CatalogueAddViewState,
    flow: CatalogueAddFlow,
    onDismiss: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    deviceName: String = catalogueDeviceName(),
    requestErrorFocus: Boolean = true,
) {
    val passwordFocusRequester = remember { FocusRequester() }.takeIf { requestErrorFocus }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ember.colors.surface,
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 22.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(StringRes.catalogue_sign_in_title, preset.name), style = Ember.type.screenTitle.copy(fontSize = 25.sp, lineHeight = 31.sp), color = Ember.colors.ink)
            Text(stringResource(StringRes.catalogue_sign_in_body, deviceName), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 22.sp), color = Ember.colors.ink2)
            EmberTextField(
                value = state.username,
                onValueChange = flow::updateUsername,
                label = stringResource(StringRes.catalogue_username),
                labelStyle = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                enabled = state.isEditable,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            )
            CataloguePasswordField(
                value = state.password,
                onValueChange = flow::updatePassword,
                enabled = state.isEditable,
                error = state.error == CatalogueAddError.WrongCredentials,
                errorText = state.error?.let { addErrorMessage(it) }.takeIf { state.error == CatalogueAddError.WrongCredentials },
                focusRequester = passwordFocusRequester,
            )
            Text(stringResource(StringRes.catalogue_password_helper), style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp), color = Ember.colors.ink2)
            Button(
                onClick = { scope.launch { flow.submit(name = preset.name) } },
                enabled = state.isEditable,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent),
            ) {
                if (state.phase != CatalogueAddPhase.Idle && !Ember.style.isEink) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Ember.colors.onAccent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    stringResource(if (state.phase == CatalogueAddPhase.Idle) StringRes.catalogue_sign_in else StringRes.catalogue_add_signing_in),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

@Composable
fun CatalogueAddDialogs(
    state: CatalogueAddViewState,
    flow: CatalogueAddFlow,
    onDismiss: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    deviceName: String = catalogueDeviceName(),
) {
    val address = state.address
    val host = address.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?').ifBlank { "this catalogue" }
    when (state.dialog) {
        CatalogueAddDialog.HttpWarning -> EmberDialog(
            onDismissRequest = onDismiss,
            title = stringResource(StringRes.catalogue_http_title),
            body = androidx.compose.ui.text.AnnotatedString(stringResource(StringRes.catalogue_http_body)),
            actions = listOf(
                EmberDialogAction(stringResource(StringRes.catalogue_go_back), EmberDialogActionStyle.Main, onClick = onDismiss),
                EmberDialogAction(stringResource(StringRes.catalogue_add_anyway), onClick = { scope.launch { flow.confirmHttp() } }),
            ),
        ) { DialogAddress(address) }
        CatalogueAddDialog.HttpBlocked -> EmberDialog(
            onDismissRequest = onDismiss,
            title = stringResource(StringRes.catalogue_http_blocked_title),
            body = androidx.compose.ui.text.AnnotatedString(stringResource(StringRes.catalogue_http_blocked_body, deviceName)),
            actions = listOf(EmberDialogAction(stringResource(StringRes.catalogue_ok), EmberDialogActionStyle.Main, onClick = flow::confirmDialogPrimary)),
        ) { DialogAddress(address) }
        CatalogueAddDialog.PasswordHttp -> EmberDialog(
            onDismissRequest = onDismiss,
            title = stringResource(StringRes.catalogue_password_http_title),
            body = androidx.compose.ui.text.AnnotatedString(stringResource(StringRes.catalogue_password_http_body)),
            actions = listOf(
                EmberDialogAction(stringResource(StringRes.catalogue_change_address), onClick = flow::changeAddress),
                EmberDialogAction(stringResource(StringRes.catalogue_add_without_account), EmberDialogActionStyle.Main, onClick = { scope.launch { flow.addWithoutAccount() } }),
            ),
        ) { DialogAddress(address) }
        CatalogueAddDialog.PasswordHttpBlocked -> EmberDialog(
            onDismissRequest = onDismiss,
            title = stringResource(StringRes.catalogue_password_http_title),
            body = androidx.compose.ui.text.AnnotatedString(stringResource(StringRes.catalogue_password_http_blocked_body)),
            actions = listOf(EmberDialogAction(stringResource(StringRes.catalogue_change_address), EmberDialogActionStyle.Main, onClick = flow::changeAddress)),
        ) { DialogAddress(address) }
        CatalogueAddDialog.Certificate -> EmberDialog(
            onDismissRequest = onDismiss,
            title = stringResource(StringRes.catalogue_certificate_title),
            body = androidx.compose.ui.text.AnnotatedString(stringResource(StringRes.catalogue_certificate_body, host)),
            actions = listOf(EmberDialogAction(stringResource(StringRes.catalogue_go_back), EmberDialogActionStyle.Main, onClick = flow::confirmDialogPrimary)),
        )
        CatalogueAddDialog.Unsupported -> EmberDialog(
            onDismissRequest = onDismiss,
            title = stringResource(StringRes.catalogue_status_unsupported_title),
            body = androidx.compose.ui.text.AnnotatedString(stringResource(StringRes.catalogue_unsupported_sign_in_body)),
            actions = listOf(
                EmberDialogAction(stringResource(StringRes.catalogue_cancel), onClick = onDismiss),
                EmberDialogAction(stringResource(StringRes.catalogue_add_without_account), EmberDialogActionStyle.Main, onClick = { scope.launch { flow.addWithoutAccount() } }),
            ),
        )
        CatalogueAddDialog.UnsupportedBlocked -> EmberDialog(
            onDismissRequest = onDismiss,
            title = stringResource(StringRes.catalogue_unsupported_blocked_title),
            body = androidx.compose.ui.text.AnnotatedString(stringResource(StringRes.catalogue_unsupported_blocked_body)),
            actions = listOf(EmberDialogAction(stringResource(StringRes.catalogue_go_back), EmberDialogActionStyle.Main, onClick = flow::confirmDialogPrimary)),
        )
        null -> Unit
    }
}

@Composable
private fun DialogAddress(address: String) {
    Text(address, style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CatalogueAddErrorLine(error: CatalogueAddError) {
    Text(
        addErrorMessage(error),
        style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
        color = Ember.colors.destructive,
    )
}

@Composable
private fun addErrorMessage(error: CatalogueAddError): String = when (error) {
    CatalogueAddError.WebPage -> stringResource(StringRes.catalogue_add_error_web_page)
    CatalogueAddError.Unreachable -> stringResource(StringRes.catalogue_add_error_unreachable)
    CatalogueAddError.NotCatalogue -> stringResource(StringRes.catalogue_add_error_invalid)
    CatalogueAddError.SignInNeeded -> stringResource(StringRes.catalogue_add_error_sign_in_needed)
    CatalogueAddError.WrongCredentials -> stringResource(StringRes.catalogue_add_error_wrong_credentials)
    CatalogueAddError.DuplicateAddress -> stringResource(StringRes.catalogue_add_error_duplicate)
    CatalogueAddError.SaveFailed -> stringResource(StringRes.catalogue_add_error_save_failed)
}
