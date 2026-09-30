package com.retro99.books.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.retro99.base.ui.compose.Ember

/**
 * Bold matches of [query] in [text] on a soft highlight; on e-ink bold and underlined instead,
 * since a background tint does not survive the display.
 */
@Composable
fun highlightedText(
    text: String,
    query: String,
): AnnotatedString {
    val isEink = Ember.style.isEink
    val highlight = Ember.colors.navActive
    return remember(text, query, isEink, highlight) {
        if (query.isBlank()) return@remember AnnotatedString(text)
        val matchStyle = if (isEink) {
            SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
        } else {
            SpanStyle(fontWeight = FontWeight.Bold, background = highlight)
        }
        val needle = query.trim()
        buildAnnotatedString {
            var cursor = 0
            while (needle.isNotEmpty()) {
                val index = text.indexOf(needle, cursor, ignoreCase = true)
                if (index < 0) break
                append(text.substring(cursor, index))
                withStyle(matchStyle) { append(text.substring(index, index + needle.length)) }
                cursor = index + needle.length
            }
            append(text.substring(cursor))
        }
    }
}
