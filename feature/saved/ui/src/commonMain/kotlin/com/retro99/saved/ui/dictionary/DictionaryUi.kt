package com.retro99.saved.ui.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.dictionary.*
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import resources.translations.*

@Composable
fun DefinitionStrip(state: DefinitionState, onMore: () -> Unit, onDownload: () -> Unit) {
    val colors = Ember.colors
    Column(Modifier.fillMaxWidth()) {
        when (state) {
            is DefinitionState.Found -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.entry.headword, style = Ember.type.screenTitle.copy(fontSize = 19.sp, lineHeight = 24.sp), color = colors.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text(state.entry.partOfSpeech, color = colors.ink2, fontSize = 13.sp, fontStyle = FontStyle.Italic)
                    }
                    Text(state.entry.firstSense.gloss, color = colors.ink, fontSize = 14.sp, lineHeight = 20.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // Reserved pronunciation slot stays hidden until the audio work is approved.
                DictionaryPill(stringResource(StringRes.dictionary_more), onMore, Modifier.widthIn(min = 64.dp), accentLabel = true)
            }
            is DefinitionState.NotFound -> Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(StringRes.dictionary_no_definition, state.word), color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(StringRes.dictionary_missing_words), color = colors.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            is DefinitionState.Pack -> Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                Text(if (state.state.downloading) stringResource(StringRes.dictionary_downloading, state.state.percent)
                    else stringResource(StringRes.dictionary_not_downloaded), color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                state.state.error?.let { Text(it, color = colors.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                if (!state.state.downloading) DictionaryPill(
                    if (state.state.error != null) stringResource(StringRes.dictionary_try_again)
                    else stringResource(StringRes.dictionary_download_size, sizeMb(state.state.sizeBytes)),
                    onDownload, Modifier.padding(top = 8.dp), tinted = true)
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(colors.line))
    }
}

@Composable
fun DictionaryEntrySheet(
    entry: DictionaryEntry,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onHighlight: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    saved: Boolean = false,
    error: String? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    var attribution by remember { mutableStateOf(false) }
    BoxWithConstraints {
        val limit = maxHeight * .70f
        EmberBottomSheet(onDismiss) {
            Column(Modifier.fillMaxWidth().heightIn(max = limit).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.headword, style = Ember.type.screenTitle.copy(fontSize = 28.sp, lineHeight = 34.sp), color = Ember.colors.ink,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                        entry.ipa?.takeIf { it.isNotBlank() }?.let {
                            Text("/$it/", color = Ember.colors.ink2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp).padding(horizontal = 8.dp))
                        }
                    }
                    IconButton(onDismiss, Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, stringResource(StringRes.dictionary_close), tint = Ember.colors.ink2) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp)
                    .semantics { collectionInfo = CollectionInfo(entry.groups.sumOf { it.senses.size }, 1) }) {
                    var rowIndex = 0
                    entry.groups.forEach { group ->
                        Text(group.partOfSpeech, color = Ember.colors.accentText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp).semantics { heading() })
                        group.senses.forEachIndexed { index, sense ->
                            val semanticIndex = rowIndex++
                            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).semantics(mergeDescendants = true) { collectionItemInfo = CollectionItemInfo(semanticIndex, 1, 0, 1) }) {
                                Text("${index + 1}", color = Ember.colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(sense.gloss, color = Ember.colors.ink, fontSize = 15.sp, lineHeight = 21.sp)
                                    sense.example?.let { Text(it, color = Ember.colors.ink2, fontSize = 15.sp, lineHeight = 21.sp, fontStyle = FontStyle.Italic,
                                        modifier = Modifier.padding(top = 2.dp)) }
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = Ember.colors.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DictionaryPill(stringResource(StringRes.dictionary_copy), onCopy, Modifier.weight(1f))
                    onHighlight?.let { DictionaryPill(stringResource(StringRes.dictionary_highlight_word), it, Modifier.weight(1f)) }
                    onSave?.let { DictionaryPill(stringResource(if (saved) StringRes.dictionary_saved else StringRes.dictionary_save_word), it,
                        Modifier.weight(1f), tinted = true, enabled = !saved) }
                    onEdit?.let { DictionaryPill(stringResource(StringRes.dictionary_edit_note), it, Modifier.weight(1f)) }
                    onDelete?.let { DictionaryPill(stringResource(StringRes.dictionary_remove), it, Modifier.weight(1f)) }
                }
                Text(stringResource(StringRes.dictionary_attribution_footer), color = Ember.colors.ink2, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { attribution = true }.padding(vertical = 12.dp))
            }
        }
    }
    if (attribution) DictionaryAttributionSheet { attribution = false }
}

@Composable
fun DictionaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tinted: Boolean = false, enabled: Boolean = true, accentLabel: Boolean = false) {
    val colors = Ember.colors
    val eink = Ember.style.isEink
    Box(modifier.height(48.dp).clip(CircleShape)
        .background(if (tinted && !eink) colors.navActive else Color.Transparent)
        .border(if (eink) 2.dp else 1.dp, if (tinted && !eink) Color.Transparent else colors.chipBorder, CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { if (!enabled) { disabled(); liveRegion = LiveRegionMode.Polite } }
        .padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (tinted || accentLabel) colors.accentText else colors.ink, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun DictionaryPackCard(service: DictionaryService = koinInject()) {
    val state by service.state.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(stringResource(StringRes.dictionary_title), style = Ember.type.cardTitle, color = Ember.colors.ink, modifier = Modifier.semantics { heading() })
        Text(when {
            state.downloading -> stringResource(StringRes.dictionary_downloading, state.percent)
            state.bundled -> stringResource(StringRes.dictionary_bundled, sizeMb(state.sizeBytes))
            state.installed -> stringResource(StringRes.dictionary_installed, sizeMb(state.sizeBytes))
            else -> stringResource(StringRes.dictionary_not_downloaded_short)
        }, color = Ember.colors.ink2, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(StringRes.dictionary_offline_help), color = Ember.colors.ink2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
        state.error?.let { Text(it, color = Ember.colors.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        if (!state.downloading) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.installed || state.updateAvailable) DictionaryPill(
                if (state.error != null) stringResource(StringRes.dictionary_try_again)
                else stringResource(if (state.updateAvailable) StringRes.dictionary_update_size else StringRes.dictionary_download_size,
                    sizeMb(if (state.updateAvailable) state.updateSizeBytes else state.sizeBytes)),
                service::download, tinted = true)
            if (state.installed) {
                if (!state.bundled) DictionaryPill(stringResource(StringRes.dictionary_remove_update), { confirmRemove = true })
                DictionaryPill(stringResource(StringRes.dictionary_check_updates), service::checkForUpdates)
            }
        }
    }
    if (confirmRemove) EmberBottomSheet({ confirmRemove = false }) {
        Column(Modifier.padding(24.dp)) {
            Text(stringResource(StringRes.dictionary_remove_update_title), style = Ember.type.screenTitle, color = Ember.colors.ink)
            Text(stringResource(StringRes.dictionary_remove_update_help), color = Ember.colors.ink2, modifier = Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DictionaryPill(stringResource(StringRes.dictionary_cancel), { confirmRemove = false })
                DictionaryPill(stringResource(StringRes.dictionary_remove_confirm), { service.remove(); confirmRemove = false })
            }
        }
    }
}

@Composable
private fun DictionaryAttributionSheet(onDismiss: () -> Unit) {
    var notices by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        notices = resources.translations.Res.readBytes("files/dictionary/LICENSE.md").decodeToString() + "\n\n" +
            resources.translations.Res.readBytes("files/dictionary/WNDB_License.txt").decodeToString()
    }
    BoxWithConstraints {
    val limit = maxHeight * .70f
    EmberBottomSheet(onDismiss) {
        Column(Modifier.heightIn(max = limit).padding(24.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(StringRes.dictionary_source), style = Ember.type.screenTitle, color = Ember.colors.ink)
            Text(stringResource(StringRes.dictionary_attribution_changes), color = Ember.colors.ink2, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
            Text(notices, color = Ember.colors.ink, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
    }
}

fun sizeMb(bytes: Long): String = ((bytes + 999_999) / 1_000_000).toString()
