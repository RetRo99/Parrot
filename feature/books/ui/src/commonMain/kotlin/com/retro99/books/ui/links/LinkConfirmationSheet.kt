package com.retro99.books.ui.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.base.ui.compose.EmberCard
import com.retro99.books.ui.model.BookUiModel

@Composable
internal fun LinkConfirmationSheet(
    first: BookUiModel,
    second: BookUiModel,
    isLinking: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    EmberBottomSheet(onDismiss = { if (!isLinking) onDismiss() }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Link these as one book?", style = Ember.type.screenTitle, color = Ember.colors.ink)
            listOf(first, second).forEach { book ->
                EmberCard {
                    Text(book.title, style = Ember.type.meta, color = Ember.colors.ink)
                    Text(book.authors.joinToString(", "), style = Ember.type.meta, color = Ember.colors.ink2)
                }
            }
            Text("You’ll see one book in your library with both versions. Files and your reading place in each stay as they are. You can separate them again any time.",
                style = Ember.type.meta, color = Ember.colors.ink2)
            error?.let { Text(it, style = Ember.type.meta, color = Ember.colors.error) }
            Button(onClick = onConfirm, enabled = !isLinking,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), elevation = null,
                colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent)) {
                Text(if (isLinking) "Linking…" else "Link as one book")
            }
        }
    }
}
