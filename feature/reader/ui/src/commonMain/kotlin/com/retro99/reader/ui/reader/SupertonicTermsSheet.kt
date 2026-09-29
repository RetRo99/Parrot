package com.retro99.reader.ui.reader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_voices_pack_supertonic
import resources.translations.reader_voices_terms_accept
import resources.translations.reader_voices_terms_bullet_ai
import resources.translations.reader_voices_terms_bullet_device
import resources.translations.reader_voices_terms_bullet_use
import resources.translations.reader_voices_terms_checkbox
import resources.translations.reader_voices_terms_footnote
import resources.translations.reader_voices_terms_not_now
import resources.translations.reader_voices_terms_read_full
import resources.translations.reader_voices_terms_sheet_intro
import resources.translations.reader_voices_terms_sheet_intro_no_size
import resources.translations.reader_voices_terms_sheet_title

/** Short summary of the Supertonic model licence, with an explicit accept step before download. */
@Composable
internal fun SupertonicTermsSheet(
    sizeMb: Int?,
    isEink: Boolean,
    onDismiss: () -> Unit,
    onViewLicense: () -> Unit,
    onAccept: () -> Unit,
) {
    val colors = Ember.colors
    var accepted by remember { mutableStateOf(false) }
    val bullets = listOf(
        stringResource(StringRes.reader_voices_terms_bullet_ai),
        stringResource(StringRes.reader_voices_terms_bullet_device),
        stringResource(StringRes.reader_voices_terms_bullet_use),
    )
    EmberBottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    StringRes.reader_voices_terms_sheet_title,
                    stringResource(StringRes.reader_voices_pack_supertonic),
                ),
                style = Ember.type.screenTitle.copy(fontSize = 24.sp),
                color = colors.ink,
            )
            Text(
                if (sizeMb != null) {
                    stringResource(StringRes.reader_voices_terms_sheet_intro, sizeMb)
                } else {
                    stringResource(StringRes.reader_voices_terms_sheet_intro_no_size)
                },
                color = colors.ink2,
                fontSize = 15.sp,
            )
            bullets.forEach { bullet ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", color = colors.ink, fontSize = 16.sp)
                    Text(bullet, color = colors.ink, fontSize = 16.sp)
                }
            }
            LinkButton(stringResource(StringRes.reader_voices_terms_read_full), onViewLicense)
            val checkboxShape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(checkboxShape)
                    .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.ink else colors.chipBorder, checkboxShape)
                    .toggleable(value = accepted, role = Role.Checkbox) { checked -> accepted = checked }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = if (isEink) colors.ink else colors.accent,
                        checkmarkColor = if (isEink) colors.surface else colors.onAccent,
                        uncheckedColor = colors.ink2,
                    ),
                )
                Text(
                    stringResource(StringRes.reader_voices_terms_checkbox),
                    modifier = Modifier.padding(start = 12.dp),
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlineButton(
                    stringResource(StringRes.reader_voices_terms_not_now),
                    colors.ink,
                    isEink,
                    onDismiss,
                )
                PillButton(
                    text = stringResource(StringRes.reader_voices_terms_accept),
                    isEink = isEink,
                    onClick = onAccept,
                    enabled = accepted,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(StringRes.reader_voices_terms_footnote),
                color = colors.ink2,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
