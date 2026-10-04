package com.retro99.saved.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberHighlightColor
import com.retro99.base.ui.compose.EmberHighlights
import com.retro99.saved.domain.SavedItemsExport
import com.retro99.saved.domain.formatAudioTime
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.percent
import com.retro99.translations.PluralRes
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.saved_ago_days
import resources.translations.saved_ago_hours
import resources.translations.saved_ago_just_now
import resources.translations.saved_ago_minutes
import resources.translations.saved_ago_months
import resources.translations.saved_ago_weeks
import resources.translations.saved_ago_years
import resources.translations.saved_ago_yesterday
import resources.translations.saved_color_amber
import resources.translations.saved_color_rose
import resources.translations.saved_color_sage
import resources.translations.saved_color_sky
import resources.translations.saved_kind_bookmark
import resources.translations.saved_kind_highlight
import resources.translations.saved_meta_listening
import resources.translations.saved_meta_percent
import resources.translations.saved_note_label
import resources.translations.saved_row_a11y
import resources.translations.saved_row_a11y_listening
import resources.translations.saved_row_a11y_note
import resources.translations.saved_untitled_chapter
import kotlin.time.Clock
import kotlin.time.Instant

fun EmberHighlights.of(color: HighlightColor): EmberHighlightColor = when (color) {
    HighlightColor.Amber -> amber
    HighlightColor.Rose -> rose
    HighlightColor.Sage -> sage
    HighlightColor.Sky -> sky
}

/** The edge bar of a row: the accent for bookmarks, the highlight's own colour otherwise. */
@Composable
fun SavedItem.barColor(): Color = when {
    Ember.style.isEink -> Ember.colors.ink
    type == SavedItemType.Bookmark -> Ember.colors.accent
    else -> Ember.colors.highlights.of(color ?: HighlightColor.Default).bar
}

@Composable
fun HighlightColor.label(): String = stringResource(
    when (this) {
        HighlightColor.Amber -> StringRes.saved_color_amber
        HighlightColor.Rose -> StringRes.saved_color_rose
        HighlightColor.Sage -> StringRes.saved_color_sage
        HighlightColor.Sky -> StringRes.saved_color_sky
    },
)

@Composable
fun SavedItem.kindLabel(): String = stringResource(
    if (type == SavedItemType.Highlight) StringRes.saved_kind_highlight else StringRes.saved_kind_bookmark,
)

/** "41%" or "listening, 4:12:08". */
@Composable
fun SavedItem.whereText(): String? = audio?.let { position ->
    stringResource(StringRes.saved_meta_listening, formatAudioTime(position.offsetMs))
} ?: location.totalProgression?.let { progression ->
    stringResource(StringRes.saved_meta_percent, percent(progression))
}

/** "**Bookmark** · 41% · 2 weeks ago". */
@Composable
fun SavedItem.metaLine(now: Instant = Clock.System.now()): AnnotatedString {
    val kind = kindLabel()
    val rest = listOfNotNull(whereText(), agoText(createdAt, now))
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Ember.colors.ink)) { append(kind) }
        rest.forEach { part ->
            append(" · ")
            append(part)
        }
    }
}

/** What a screen reader says for a row: "Highlight, chapter 8, 62 percent: <quote>. Note: <note>". */
@Composable
fun SavedItem.accessibilityText(): String {
    val chapter = location.chapterTitle?.takeIf { title -> title.isNotBlank() }
        ?: stringResource(StringRes.saved_untitled_chapter)
    val text = displayText()
    val base = audio?.let { position ->
        stringResource(StringRes.saved_row_a11y_listening, kindLabel(), chapter, formatAudioTime(position.offsetMs), text)
    } ?: stringResource(
        StringRes.saved_row_a11y,
        kindLabel(),
        chapter,
        location.totalProgression?.let(::percent) ?: 0,
        text,
    )
    return if (hasNote) stringResource(StringRes.saved_row_a11y_note, base, note!!.trim()) else base
}

/** The sentence or quote; a migrated bookmark shows its chapter until its sentence is known. */
@Composable
fun SavedItem.displayText(): String = text?.collapseWhitespace()
    ?: location.chapterTitle?.takeIf { title -> title.isNotBlank() }
    ?: stringResource(StringRes.saved_untitled_chapter)

fun String.collapseWhitespace(): String = replace(Regex("\\s+"), " ").trim()

/** "just now", "5 minutes ago", "yesterday", "2 weeks ago". */
@Composable
fun agoText(then: Instant, now: Instant = Clock.System.now()): String {
    val minutes = ((now - then).inWholeMinutes).coerceAtLeast(0)
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> stringResource(StringRes.saved_ago_just_now)
        minutes < 60 -> pluralStringResource(PluralRes.saved_ago_minutes, minutes.toInt(), minutes.toInt())
        hours < 24 -> pluralStringResource(PluralRes.saved_ago_hours, hours.toInt(), hours.toInt())
        days < 2 -> stringResource(StringRes.saved_ago_yesterday)
        days < 14 -> pluralStringResource(PluralRes.saved_ago_days, days.toInt(), days.toInt())
        days < 60 -> pluralStringResource(PluralRes.saved_ago_weeks, (days / 7).toInt(), (days / 7).toInt())
        days < 730 -> pluralStringResource(PluralRes.saved_ago_months, (days / 30).toInt(), (days / 30).toInt())
        else -> pluralStringResource(PluralRes.saved_ago_years, (days / 365).toInt(), (days / 365).toInt())
    }
}

@Composable
fun savedExportLabels(): SavedItemsExport.Labels = SavedItemsExport.Labels(
    bookmark = stringResource(StringRes.saved_kind_bookmark),
    highlight = stringResource(StringRes.saved_kind_highlight),
    note = stringResource(StringRes.saved_note_label),
    listeningFormat = stringResource(StringRes.saved_meta_listening, "%s"),
    untitledChapter = stringResource(StringRes.saved_untitled_chapter),
)
