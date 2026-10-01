package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_about
import resources.translations.book_detail_read_more
import resources.translations.books_detail_show_less

@Composable
internal fun BookDetailAbout(description: String?) {
    val text = remember(description) { plainTextDescription(description.orEmpty()) }
    if (text.isBlank()) return
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column {
        Text(stringResource(StringRes.book_detail_about), style = Ember.type.eyebrow.copy(
            fontSize = if (Ember.style.isEink) 13.sp else 11.sp,
        ),
            color = Ember.colors.accentText)
        Spacer(Modifier.height(8.dp))
        Text(
            text,
            style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 23.25.sp),
            color = Ember.colors.ink,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> if (!expanded) overflows = result.hasVisualOverflow },
        )
        if (expanded || overflows) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) StringRes.books_detail_show_less
                    else StringRes.book_detail_read_more), color = Ember.colors.accentText)
            }
        }
    }
}
