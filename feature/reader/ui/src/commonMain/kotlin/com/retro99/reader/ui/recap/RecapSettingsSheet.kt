package com.retro99.reader.ui.recap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.*
import com.retro99.reader.domain.recap.RecapPresentation
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.translations.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import resources.translations.*

/** Shared by settings, the reader and actionable Statistics messages. */
@Composable
fun RecapSettingsSheet(onDismiss: () -> Unit, settings: RecapSettings = koinInject()) {
    val consent by remember(settings) { settings.observeConsentGiven() }.collectAsState(false)
    val signedIn by remember(settings) { settings.observeSignedIn() }.collectAsState(false)
    val allowed by remember(settings) { settings.observeFeatureAvailable() }.collectAsState(false)
    val preference by remember(settings) { settings.observePresentation() }.collectAsState(RecapPresentation.AFTER_BREAK)
    val scope = rememberCoroutineScope()
    var confirmation by remember { mutableStateOf<Boolean?>(null) }
    var details by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun save(action: suspend () -> Unit) {
        if (saving) return
        scope.launch {
            saving = true
            failed = false
            try { action() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { saving = false }
        }
    }
    if (!allowed) return
    // Read eagerly in the host's window and passed down as plain Dp. The screen renders
    // in a full-window popup, and insets resolved lazily inside that popup come back zero.
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    val statusBarTop = with(density) { safeDrawing.getTop(density).toDp() }
    val navigationBarBottom = with(density) { safeDrawing.getBottom(density).toDp() }
    RecapSettingsContainer(onDismiss, navigationBarBottom) {
        Row(Modifier.fillMaxWidth().padding(top = statusBarTop).height(64.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(StringRes.general_back), tint = Ember.colors.ink)
            }
            Text(stringResource(StringRes.recap_settings_title),
                style = Ember.type.screenTitle.copy(fontSize = 26.sp, lineHeight = 32.sp),
                color = Ember.colors.ink, modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecapSettingsCard(Modifier.toggleable(value = consent, role = Role.Switch,
                enabled = !saving && (consent || signedIn), onValueChange = { confirmation = it })) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        RecapCardTitle(stringResource(StringRes.recap_switch_title))
                        RecapSecondaryText(stringResource(StringRes.recap_switch_subtitle))
                    }
                    EmberSwitch(checked = consent)
                }
            }
            RecapSettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecapCardTitle(stringResource(StringRes.recap_show_when))
                    EmberSegmented(options = RecapPresentation.entries, selected = preference, enabled = !saving,
                        onSelect = { value -> save { settings.setPresentation(value) } },
                        label = { value ->
                            stringResource(when (value) {
                                RecapPresentation.AFTER_BREAK -> StringRes.recap_after_break
                                RecapPresentation.EVERY_TIME -> StringRes.recap_every_time
                                RecapPresentation.NEVER -> StringRes.recap_never
                            })
                        })
                    RecapSecondaryText(stringResource(StringRes.recap_show_helper))
                }
            }
            RecapSettingsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecapCardTitle(stringResource(StringRes.recap_privacy_title))
                    RecapPrivacyRow(stringResource(StringRes.recap_privacy_text_lead), stringResource(StringRes.recap_privacy_text_body))
                    RecapPrivacyRow(stringResource(StringRes.recap_privacy_results_lead), stringResource(StringRes.recap_privacy_results_body))
                    RecapPrivacyRow(stringResource(StringRes.recap_privacy_provider_lead), stringResource(StringRes.recap_privacy_provider_body))
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = { details = true }),
                        contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(StringRes.recap_privacy_details), color = Ember.colors.accentText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
            RecapSecondaryText(stringResource(StringRes.recap_off_disclosure), Modifier.padding(horizontal = 4.dp))
            if (!signedIn) RecapSecondaryText(stringResource(StringRes.recap_signed_out))
            if (failed) Text(stringResource(StringRes.recap_setting_failed), color = Ember.colors.error,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp))
        }
    }
    if (details) EmberBottomSheet(onDismiss = { details = false }) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(StringRes.recap_privacy_details_title),
                    style = Ember.type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
                    color = Ember.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { details = false }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Close, stringResource(StringRes.general_close), tint = Ember.colors.ink2)
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Full disclosure stays reachable before consent, one sentence per paragraph.
                RecapLegalParagraph(stringResource(StringRes.recap_legal_excerpts))
                RecapLegalParagraph(stringResource(StringRes.recap_legal_summaries))
                RecapLegalParagraph(stringResource(StringRes.recap_legal_withdrawal))
                RecapLegalParagraph(stringResource(StringRes.recap_legal_recall))
                RecapLegalParagraph(stringResource(StringRes.recap_legal_existing))
            }
        }
    }
    confirmation?.let { turningOn ->
        EmberBottomSheet(onDismiss = { confirmation = null }) {
            Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(if (turningOn) StringRes.recap_consent_title else StringRes.recap_withdraw_title),
                    style = Ember.type.screenTitle, color = Ember.colors.ink)
                if (turningOn) {
                    Text(stringResource(StringRes.recap_consent_body), color = Ember.colors.ink)
                    PrivacyPoints()
                    TextButton(onClick = { details = true }) { Text(stringResource(StringRes.recap_privacy_details)) }
                } else Text(stringResource(StringRes.recap_off_disclosure), color = Ember.colors.ink)
                Row {
                    TextButton(onClick = { confirmation = null }) { Text(stringResource(StringRes.recap_not_now)) }
                    Button(onClick = {
                        confirmation = null
                        save { settings.setCloudRecapsEnabled(turningOn) }
                    }, enabled = !saving, colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent)) {
                        Text(stringResource(if (turningOn) StringRes.recap_turn_on else StringRes.recap_turn_off))
                    }
                }
            }
        }
    }
}

/**
 * Full-window screen, not a sheet: back and the toolbar arrow dismiss it. It covers the
 * home tab bar as well as reader/statistics overlays.
 *
 * Rendering in its own window is also what keeps the inset single-applied wherever this is
 * opened from — no host Scaffold or `statusBarsPadding()` can reach popup content — so the
 * top bar is the one and only place the status-bar inset is added.
 *
 * The popup is pinned to its window's top-left instead of aligned to the call site:
 * `Popup(alignment = …)` centres the popup on the nearest host layout node, so a
 * full-window screen slides with whatever boxes the host sits in (from the settings route
 * the home Scaffold's bottom-bar padding shortened the anchor and slid this screen up
 * under the status bar; from the statistics sheet it slid it far down).
 */
@Composable
private fun RecapSettingsContainer(
    onDismiss: () -> Unit,
    navigationBarBottom: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Popup(popupPositionProvider = WindowOriginPositionProvider, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        Surface(color = Ember.colors.bg, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(bottom = navigationBarBottom), content = content)
        }
    }
}

/** Pins a full-window popup to the window's top-left, whatever node it happens to anchor to. */
private object WindowOriginPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

@Composable
private fun RecapSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Ember.colors.surface,
        border = when {
            Ember.style.isEink -> BorderStroke(2.dp, Ember.colors.line)
            Ember.colors.bg == EmberDayColors.bg -> BorderStroke(1.dp, Ember.colors.line)
            else -> null
        }) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun RecapCardTitle(text: String) {
    Text(text, color = Ember.colors.ink,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold))
}

@Composable
private fun RecapSecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = Ember.colors.ink2,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp))
}

@Composable
private fun RecapLegalParagraph(text: String) {
    Text(
        text,
        color = Ember.colors.ink2,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
    )
}

@Composable
private fun RecapPrivacyRow(lead: String, body: String) {
    val ink = Ember.colors.ink
    val ink2 = Ember.colors.ink2
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 7.dp).size(6.dp).background(Ember.colors.accent, CircleShape))
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight.Bold)) { append(lead) }
            withStyle(SpanStyle(color = ink2)) { append(" "); append(body) }
        }, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PrivacyPoints() {
    Text(stringResource(StringRes.recap_privacy_text), color = Ember.colors.ink, fontSize = 15.sp)
    Text(stringResource(StringRes.recap_privacy_results), color = Ember.colors.ink, fontSize = 15.sp)
    Text(stringResource(StringRes.recap_privacy_provider), color = Ember.colors.ink, fontSize = 15.sp)
}
