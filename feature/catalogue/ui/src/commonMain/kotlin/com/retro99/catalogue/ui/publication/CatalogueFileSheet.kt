package com.retro99.catalogue.ui.publication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.*

@Composable
fun CatalogueFileSheet(state: CatalogueBookState, actions: CatalogueBookActions) {
    val grouped = state.groups.size > 1
    EmberBottomSheet(onDismiss = actions.onCloseFiles, footer = {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 20.dp)) {
            val size = state.selectedFile?.size?.let(::catalogueSize)
            BookButton(if (size == null) stringResource(StringRes.catalogue_download) else stringResource(StringRes.catalogue_download_with_size, size), actions.onDownload, enabled = state.selectedFile?.openable == true)
        }
    }) {
        Text(stringResource(StringRes.catalogue_choose_file_title), style = Ember.type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp), color = Ember.colors.ink,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp).semantics { heading() })
        Text(if (grouped) stringResource(StringRes.catalogue_editions_intro, state.groups.size) else stringResource(StringRes.catalogue_choose_file_intro),
            style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Ember.colors.ink2, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp))
        val groupLabel = stringResource(StringRes.catalogue_choose_file_title)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp).padding(horizontal = 20.dp).selectableGroup().semantics { contentDescription = groupLabel }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.groups.forEachIndexed { index, group ->
                if (grouped) item(key = "edition$index") {
                    Text(group.label ?: stringResource(StringRes.catalogue_edition_fallback_label, index + 1), style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 4.dp, bottom = 2.dp).semantics { heading() })
                }
                items(group.files, key = { it.ordinal }) { file -> FileOption(file, state.selectedOrdinal == file.ordinal) { actions.onChooseFile(file.ordinal) } }
            }
        }
    }
}

@Composable
private fun FileOption(file: BookFile, selected: Boolean, onSelect: () -> Unit) {
    val eink = Ember.style.isEink
    val title = file.label ?: stringResource(StringRes.catalogue_file_fallback_label, file.ordinal)
    val size = file.size?.let(::catalogueSize) ?: stringResource(StringRes.catalogue_file_size_unknown)
    val a11y = stringResource(when {
        !file.openable -> StringRes.catalogue_a11y_file_option_unavailable
        file.best -> StringRes.catalogue_a11y_file_option_best
        else -> StringRes.catalogue_a11y_file_option
    }, title, size)
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(if (eink) Ember.colors.surface else if (selected) Ember.colors.surfaceSelected else Ember.colors.bg)
        .border(if (eink || selected) 2.dp else 1.dp, if (selected) Ember.colors.accent else Ember.colors.line, shape)
        .selectable(selected = selected, enabled = file.openable, role = Role.RadioButton, onClick = onSelect)
        .padding(horizontal = 16.dp, vertical = 12.dp).semantics { contentDescription = a11y }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp).alpha(if (file.openable) 1f else .4f).border(2.dp, if (eink) Ember.colors.ink else if (selected) Ember.colors.accent else Ember.colors.ink2, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(if (eink) 14.dp else 10.dp).clip(CircleShape).background(if (eink) Ember.colors.ink else Ember.colors.accent))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink)
            // Unsupported files retain full-contrast text; only the radio is dimmed.
            Text(if (!file.openable) stringResource(StringRes.catalogue_cant_open_in_parrot) else size, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 19.sp), color = Ember.colors.ink2)
        }
        if (file.best) Text(stringResource(StringRes.catalogue_file_best), style = Ember.type.meta.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
            color = if (eink) Ember.colors.ink else Ember.colors.accentText, modifier = Modifier.clip(CircleShape).background(if (eink) Ember.colors.surface else Ember.colors.navActive)
                .then(if (eink) Modifier.border(2.dp, Ember.colors.line, CircleShape) else Modifier).padding(horizontal = 9.dp, vertical = 3.dp))
    }
}
