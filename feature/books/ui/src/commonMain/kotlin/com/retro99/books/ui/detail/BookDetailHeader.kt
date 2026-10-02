package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Headphones
import com.retro99.books.domain.model.BookType
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.ui.compose.Ember
import com.retro99.books.ui.model.BookUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_preparing_format
import resources.translations.book_detail_series_number

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookDetailHeader(
    book: BookUiModel,
    media: List<DetailMedia>,
    onSeries: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val shape = RoundedCornerShape(6.dp)
        CoilImage(
            data = book.coverUrl,
            cacheKey = book.uuid,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(112.dp, 168.dp)
                .shadow(Ember.style.coverElevation, shape).clip(shape)
                .then(if (Ember.style.isEink) {
                    Modifier.border(Ember.style.detailBorder, Ember.colors.line, shape)
                } else Modifier),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                book.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = Ember.type.bookTitle.copy(fontSize = 24.sp, lineHeight = 28.sp),
                color = Ember.colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            if (book.authors.isNotEmpty()) {
                Text(
                    book.authors.joinToString(", "),
                    style = Ember.type.meta.copy(fontSize = 15.sp, fontStyle = FontStyle.Normal),
                    color = Ember.colors.ink2,
                )
            }
            book.series.forEach { series ->
                OutlinedButton(
                    onClick = { onSeries(series.uuid) },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(Ember.style.detailBorder, Ember.colors.chipBorder),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Ember.colors.chip,
                        contentColor = Ember.colors.accentText,
                    ),
                ) {
                    Text(
                        if (series.position == null) series.name + " ›" else stringResource(
                            StringRes.book_detail_series_number,
                            series.name,
                            series.position.toString().removeSuffix(".0"),
                        ),
                        style = Ember.type.label,
                        color = Ember.colors.accentText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                media.forEach { item ->
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(Ember.colors.chip)
                            .border(Ember.style.detailBorder, Ember.colors.chipBorder,
                                RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Icon(if (item.type == BookType.AUDIOBOOK) Icons.Outlined.Headphones
                            else Icons.AutoMirrored.Outlined.MenuBook,
                            contentDescription = null, tint = Ember.colors.ink,
                            modifier = Modifier.size(12.dp))
                        Text(
                            if (item.preparing) stringResource(
                                StringRes.book_detail_preparing_format, mediaLabel(item.type),
                            ) else mediaLabel(item.type),
                            style = Ember.type.label.copy(fontSize = 13.sp),
                            color = if (item.preparing) Ember.colors.ink2 else Ember.colors.ink,
                        )
                    }
                }
            }
            book.rating?.takeIf { rating -> rating > 0 }?.let { rating ->
                Text("★ $rating", style = Ember.type.meta, color = Ember.colors.ink2)
            }
        }
    }
}
