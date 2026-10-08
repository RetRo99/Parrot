package com.retro99.catalogue.ui.description

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.catalogue.domain.CatalogueRichText
import com.retro99.catalogue.domain.CatalogueRichText.Marker

/**
 * A catalogue's description, in the body style the library's own book page uses. It draws
 * what [CatalogueRichText] can hold and nothing else: there is nothing to tap and nothing
 * to load.
 */
@Composable
fun CatalogueDescriptionText(
    description: CatalogueRichText,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 23.25.sp),
) {
    val style = textStyle
    val color = Ember.colors.ink
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        description.blocks.forEach { block ->
            when (block) {
                is CatalogueRichText.Paragraph -> {
                    val text = remember(block) { block.spans.toAnnotatedString() }
                    Text(text, style = style, color = color)
                }
                is CatalogueRichText.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        val text = remember(item) { item.spans.toAnnotatedString() }
                        Row(modifier = Modifier.padding(start = LIST_INDENT * item.level)) {
                            // The marker is decoration; a screen reader gets the item's own text.
                            Text(
                                text = item.marker.label(),
                                style = style,
                                color = color,
                                textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(min = MARKER_WIDTH).padding(end = 8.dp).clearAndSetSemantics {},
                            )
                            Text(text, style = style, color = color)
                        }
                    }
                }
            }
        }
    }
}

private val LIST_INDENT = 20.dp
private val MARKER_WIDTH = 20.dp

internal fun Marker.label(): String = when (this) {
    Marker.Bullet -> "•"
    is Marker.Number -> "$value."
}

internal fun List<CatalogueRichText.Span>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    forEach { span ->
        if (!span.bold && !span.italic) {
            append(span.text)
        } else {
            withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                ),
            ) { append(span.text) }
        }
    }
}
