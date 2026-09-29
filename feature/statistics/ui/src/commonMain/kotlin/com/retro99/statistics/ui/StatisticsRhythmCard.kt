package com.retro99.statistics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberCard
import com.retro99.statistics.domain.model.ReadingRhythm
import com.retro99.statistics.domain.model.TimeOfDay
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.statistics_rhythm_afternoon
import resources.translations.statistics_rhythm_eyebrow
import resources.translations.statistics_rhythm_evening
import resources.translations.statistics_rhythm_headline
import resources.translations.statistics_rhythm_morning
import resources.translations.statistics_rhythm_night
import resources.translations.statistics_rhythm_not_enough
import resources.translations.statistics_rhythm_subline
import resources.translations.statistics_rhythm_time_description
import resources.translations.statistics_rhythm_time_description_top
import resources.translations.statistics_rhythm_time_of_day
import resources.translations.statistics_rhythm_weekday_description
import resources.translations.statistics_rhythm_weekday_description_top

private val WEEKDAY_ROW_HEIGHT = 22.dp
private val WEEKDAY_BAR_HEIGHT = 12.dp
private val WEEKDAY_LABEL_WIDTH = 34.dp
private val WEEKDAY_VALUE_WIDTH = 52.dp
private val TIME_BAR_WIDTH = 36.dp
private val TIME_BAR_MAX_HEIGHT = 56.dp
private val STUB_HEIGHT = 4.dp
private const val PERCENT = 100

/** "When you read": average time per weekday and the split across parts of the day. */
@Composable
internal fun ReadingRhythmCard(
    rhythm: ReadingRhythm,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val type = Ember.type
    val topWeekday = rhythm.topWeekday
    val topTimeOfDay = rhythm.topTimeOfDay

    EmberCard(modifier = modifier) {
        Text(
            text = stringResource(StringRes.statistics_rhythm_eyebrow).uppercase(),
            style = type.eyebrow.copy(fontSize = if (Ember.style.isEink) 13.sp else 11.sp),
            color = colors.accentText,
        )

        if (!rhythm.hasEnoughData || topWeekday == null || topTimeOfDay == null) {
            Text(
                text = stringResource(StringRes.statistics_rhythm_not_enough),
                style = type.meta.copy(fontSize = 14.sp),
                color = colors.ink2,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@EmberCard
        }

        Text(
            text = stringResource(
                StringRes.statistics_rhythm_headline,
                "${topWeekday.fullName()}s",
                stringResource(topTimeOfDay.labelRes()).lowercase(),
            ),
            style = type.cardTitle,
            color = colors.ink,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(StringRes.statistics_rhythm_subline),
            style = type.meta,
            color = colors.ink2,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
        )

        val maxAverage = rhythm.weekdayAverages.maxOf { entry -> entry.averageMs }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rhythm.weekdayAverages.forEach { entry ->
                val isTop = entry.dayOfWeek == topWeekday
                val duration = formatReadingTime(entry.averageMs)
                val description = stringResource(
                    if (isTop) {
                        StringRes.statistics_rhythm_weekday_description_top
                    } else {
                        StringRes.statistics_rhythm_weekday_description
                    },
                    entry.dayOfWeek.fullName(),
                    duration,
                )
                WeekdayRow(
                    label = entry.dayOfWeek.shortName(),
                    value = duration,
                    fraction = if (maxAverage > 0L) entry.averageMs.toFloat() / maxAverage else 0f,
                    isTop = isTop,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = description
                    },
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(top = 18.dp, bottom = 14.dp),
            thickness = Ember.style.border,
            color = colors.line,
        )

        Text(
            text = stringResource(StringRes.statistics_rhythm_time_of_day),
            style = type.label,
            color = colors.ink,
        )
        TimeOfDayColumns(
            rhythm = rhythm,
            topTimeOfDay = topTimeOfDay,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun WeekdayRow(
    label: String,
    value: String,
    fraction: Float,
    isTop: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WEEKDAY_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = Ember.type.meta.copy(fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal),
            color = if (isTop) colors.ink else colors.ink2,
            modifier = Modifier.width(WEEKDAY_LABEL_WIDTH),
            maxLines = 1,
        )
        Box(modifier = Modifier.weight(1f)) {
            RhythmBar(
                isTop = isTop,
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(WEEKDAY_BAR_HEIGHT),
                shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
            )
        }
        Text(
            text = value,
            style = Ember.type.meta.copy(fontWeight = if (isTop) FontWeight.Bold else FontWeight.Normal),
            color = if (isTop) colors.ink else colors.ink2,
            modifier = Modifier.width(WEEKDAY_VALUE_WIDTH),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun TimeOfDayColumns(
    rhythm: ReadingRhythm,
    topTimeOfDay: TimeOfDay,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val total = rhythm.timeOfDayMs.values.sum()
    val maxMs = rhythm.timeOfDayMs.values.maxOrNull() ?: 0L

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TIME_BAR_MAX_HEIGHT),
            verticalAlignment = Alignment.Bottom,
        ) {
            TimeOfDay.entries.sortedBy { part -> part.displayOrder() }.forEach { part ->
                val ms = rhythm.timeOfDayMs[part] ?: 0L
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    RhythmBar(
                        isTop = part == topTimeOfDay,
                        modifier = Modifier
                            .width(TIME_BAR_WIDTH)
                            .height(barHeight(ms, maxMs)),
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                    )
                }
            }
        }
        HorizontalDivider(thickness = Ember.style.border, color = colors.line)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            TimeOfDay.entries.sortedBy { part -> part.displayOrder() }.forEach { part ->
                val percent = if (total > 0L) {
                    ((rhythm.timeOfDayMs[part] ?: 0L) * PERCENT / total).toInt()
                } else {
                    0
                }
                val name = stringResource(part.labelRes())
                val description = stringResource(
                    if (part == topTimeOfDay) {
                        StringRes.statistics_rhythm_time_description_top
                    } else {
                        StringRes.statistics_rhythm_time_description
                    },
                    name,
                    percent,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "$percent%",
                        style = Ember.type.label,
                        color = colors.ink,
                    )
                    Text(
                        text = name,
                        style = Ember.type.meta.copy(fontSize = 12.sp),
                        color = colors.ink2,
                        maxLines = 1,
                    )
                    Text(
                        text = "${part.startHour}–${part.endHour}",
                        style = Ember.type.meta.copy(fontSize = 12.sp),
                        color = colors.ink2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Top bar: solid accent. Others: muted accent, or a plain outline in E-ink. */
@Composable
private fun RhythmBar(
    isTop: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val fill = when {
        isTop -> colors.accent
        else -> colors.mutedAccent
    }
    val outline = if (style.isEink && !isTop) Modifier.border(style.border, colors.line, shape) else Modifier

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .then(outline),
    )
}

private fun barHeight(ms: Long, maxMs: Long): Dp =
    if (ms <= 0L || maxMs <= 0L) {
        STUB_HEIGHT
    } else {
        (TIME_BAR_MAX_HEIGHT * (ms.toFloat() / maxMs.toFloat())).coerceAtLeast(STUB_HEIGHT)
    }

private fun TimeOfDay.labelRes(): StringResource = when (this) {
    TimeOfDay.MORNING -> StringRes.statistics_rhythm_morning
    TimeOfDay.AFTERNOON -> StringRes.statistics_rhythm_afternoon
    TimeOfDay.EVENING -> StringRes.statistics_rhythm_evening
    TimeOfDay.NIGHT -> StringRes.statistics_rhythm_night
}

/** Morning, afternoon, evening, then night. */
private fun TimeOfDay.displayOrder(): Int = when (this) {
    TimeOfDay.MORNING -> 0
    TimeOfDay.AFTERNOON -> 1
    TimeOfDay.EVENING -> 2
    TimeOfDay.NIGHT -> 3
}
