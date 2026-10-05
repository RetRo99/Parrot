package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.cloud_backup_note_add_count
import resources.translations.cloud_backup_note_add_count_one
import resources.translations.cloud_backup_note_dismiss

/**
 * The note above the library list offering to add the books on this phone to Parrot Cloud.
 * A note card, not a row of controls: the words are in ink, the cloud in the accent, and ✕
 * hides the note for good (the action stays available on the Parrot Cloud screen).
 */
@Composable
fun CloudBackupNoteCard(
    bookCount: Int,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val type = Ember.type
    val shape = RoundedCornerShape(16.dp)
    val label = if (bookCount == 1) {
        stringResource(StringRes.cloud_backup_note_add_count_one)
    } else {
        stringResource(StringRes.cloud_backup_note_add_count, bookCount)
    }
    val dismissLabel = stringResource(StringRes.cloud_backup_note_dismiss)

    Row(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .border(
                if (style.isEink) 2.dp else style.border,
                colors.line,
                shape,
            )
            .clip(shape)
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.CloudUpload,
            contentDescription = null,
            tint = colors.accentText,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = type.meta.copy(fontSize = 14.sp),
            color = colors.ink,
            // One line where it fits; a long count wraps rather than losing its words.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = dismissLabel,
                tint = colors.ink2,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
