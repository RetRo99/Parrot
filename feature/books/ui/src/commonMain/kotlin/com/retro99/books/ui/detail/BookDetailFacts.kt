package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.CalendarDateLabel
import com.retro99.base.calendarDateLabel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_length
import resources.translations.book_detail_narrator
import resources.translations.book_detail_language
import resources.translations.book_detail_added
import resources.translations.book_detail_last_opened
import resources.translations.books_detail_publication_date
import resources.translations.book_detail_chapters
import resources.translations.book_detail_duration_hours
import resources.translations.book_detail_duration_minutes
import resources.translations.book_detail_fact_pair
import resources.translations.book_detail_today
import resources.translations.book_detail_yesterday

@Composable
internal fun durationText(millis: Long): String {
    val minutes = (millis / 60000).coerceAtLeast(0)
    return if (minutes >= 60) stringResource(
        StringRes.book_detail_duration_hours, minutes / 60, minutes % 60,
    ) else stringResource(StringRes.book_detail_duration_minutes, minutes)
}

@Composable
private fun factDate(raw: String?): String? = when (val label = calendarDateLabel(raw)) {
    CalendarDateLabel.Today -> stringResource(StringRes.book_detail_today)
    CalendarDateLabel.Yesterday -> stringResource(StringRes.book_detail_yesterday)
    is CalendarDateLabel.Medium -> label.text
    null -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookDetailFacts(state: BookDetailViewState) {
    val book = state.book ?: return
    val chapters = state.progressInfo?.totalChapters?.takeIf { count -> count > 0 }
        ?.let { count -> stringResource(StringRes.book_detail_chapters, count) }
    val duration = (state.progressInfo?.totalDurationMs ?: book.audioDurationMs)
        ?.takeIf { millis -> millis > 0 }?.let { millis -> durationText(millis) }
    val length = when {
        chapters != null && duration != null ->
            stringResource(StringRes.book_detail_fact_pair, chapters, duration)
        else -> chapters ?: duration
    }
    val facts = listOfNotNull(
        length?.let { value -> stringResource(StringRes.book_detail_length) to value },
        book.narrators.takeIf { names -> names.isNotEmpty() }?.let { names ->
            stringResource(StringRes.book_detail_narrator) to names.joinToString(", ")
        },
        formatPublicationDate(book.publicationDate)?.let { year ->
            stringResource(StringRes.books_detail_publication_date) to year
        },
        book.language?.takeIf { value -> value.isNotBlank() }?.let { value ->
            stringResource(StringRes.book_detail_language) to value
        },
        factDate(book.dateAdded)?.let { value ->
            stringResource(StringRes.book_detail_added) to value
        },
        factDate(book.lastOpened)?.let { value ->
            stringResource(StringRes.book_detail_last_opened) to value
        },
    )
    if (facts.isNotEmpty()) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Ember.colors.line)
                .border(Ember.style.detailBorder, Ember.colors.line, shape),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            facts.chunked(2).forEach { pair ->
                Row(Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    pair.forEach { (label, value) ->
                        Column(Modifier.weight(1f).fillMaxHeight().background(Ember.colors.surface)
                            .padding(14.dp)) {
                            Text(label, style = Ember.type.meta, color = Ember.colors.ink2)
                            Text(value, style = Ember.type.label, color = Ember.colors.ink)
                        }
                    }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        book.tags.forEach { tag ->
            Text(tag, style = Ember.type.meta, color = Ember.colors.ink2,
                modifier = Modifier.background(Ember.colors.chip, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}
