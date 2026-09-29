package com.retro99.statistics.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.statistics.domain.model.ReadingBucket
import com.retro99.statistics.domain.model.StatisticsOverview
import com.retro99.statistics.domain.model.StatisticsRange
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.statistics_bar_description
import resources.translations.statistics_chart_summary

private val MAX_BAR_HEIGHT = 92.dp
private val STUB_HEIGHT = 4.dp
private val BAR_CORNER = 4.dp
private const val MONTH_LABEL_STEP = 7

/**
 * Single-series bar chart of reading time: a bar per day for week and month, per month for
 * year. Tapping a bar shows a tooltip above it; the busiest bar is selected by default.
 */
@Composable
internal fun ReadingBarChart(
    overview: StatisticsOverview,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val buckets = overview.buckets
    val maxMs = buckets.maxOfOrNull { bucket -> bucket.durationMs } ?: 0L
    val effectiveIndex = selectedIndex
        ?: buckets.indices.maxByOrNull { index -> buckets[index].durationMs }?.takeIf { maxMs > 0L }
    val barWidth: Dp? = when (overview.range) {
        StatisticsRange.WEEK -> 28.dp
        StatisticsRange.YEAR -> 16.dp
        else -> null
    }
    val summary = stringResource(StringRes.statistics_chart_summary, formatReadingTime(overview.totalMs))

    Column(modifier = modifier.semantics { contentDescription = summary }) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
        ) {
            if (effectiveIndex != null) {
                val bucket = buckets[effectiveIndex]
                val slotWidth = maxWidth / buckets.size
                ChartTooltip(
                    text = "${bucket.tooltipLabel(overview.range)} · ${formatReadingTime(bucket.durationMs)}",
                    anchorCenter = slotWidth * effectiveIndex + slotWidth / 2,
                    halfBar = (barWidth ?: slotWidth) / 2,
                    index = effectiveIndex,
                    count = buckets.size,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MAX_BAR_HEIGHT),
            verticalAlignment = Alignment.Bottom,
        ) {
            buckets.forEachIndexed { index, bucket ->
                val description = stringResource(
                    StringRes.statistics_bar_description,
                    bucket.accessibleLabel(overview.range),
                    formatReadingTime(bucket.durationMs),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(role = Role.Button) { onSelect(index) }
                        .semantics { contentDescription = description },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    ChartBar(
                        bucket = bucket,
                        maxMs = maxMs,
                        isFuture = bucket.start > overview.today,
                        width = barWidth,
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = colors.line)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        ) {
            buckets.forEachIndexed { index, bucket ->
                val isToday = overview.today >= bucket.start && overview.today <= bucket.end
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = bucket.axisLabel(overview.range, index),
                        style = Ember.type.meta.copy(
                            fontSize = 13.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        ),
                        color = if (isToday) colors.ink else colors.ink2,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartBar(
    bucket: ReadingBucket,
    maxMs: Long,
    isFuture: Boolean,
    width: Dp?,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(topStart = BAR_CORNER, topEnd = BAR_CORNER)
    val widthModifier = if (width != null) Modifier.width(width) else Modifier
        .fillMaxWidth()
        .padding(horizontal = 1.5.dp)

    when {
        isFuture -> Box(
            modifier = widthModifier
                .height(STUB_HEIGHT)
                .drawBehind {
                    drawRoundRect(
                        color = colors.ink2,
                        cornerRadius = CornerRadius(BAR_CORNER.toPx()),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
                        ),
                    )
                },
        )

        bucket.durationMs <= 0L -> Box(
            modifier = widthModifier
                .height(STUB_HEIGHT)
                .clip(shape)
                .background(colors.track)
                .then(
                    if (style.isEink) Modifier.border(style.border, colors.line, shape) else Modifier,
                ),
        )

        else -> {
            val fraction = bucket.durationMs.toFloat() / maxMs.toFloat()
            Box(
                modifier = widthModifier
                    .height((MAX_BAR_HEIGHT * fraction).coerceAtLeast(STUB_HEIGHT))
                    .clip(shape)
                    .background(colors.accent),
            )
        }
    }
}

@Composable
private fun ChartTooltip(
    text: String,
    anchorCenter: Dp,
    halfBar: Dp,
    index: Int,
    count: Int,
) {
    val colors = Ember.colors
    val style = Ember.style
    val shape = RoundedCornerShape(8.dp)
    val background = if (style.isEink) colors.surface else colors.ink
    val content = if (style.isEink) colors.ink else colors.bg
    val outline = if (style.isEink) Modifier.border(2.dp, colors.line, shape) else Modifier

    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0))
            val center = anchorCenter.roundToPx()
            val half = halfBar.roundToPx()
            val x = when {
                index * 3 < count -> center - half
                (index + 1) * 3 > count * 2 -> center + half - placeable.width
                else -> center - placeable.width / 2
            }.coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
            layout(constraints.maxWidth, placeable.height) {
                placeable.placeRelative(x, 0)
            }
        },
    ) {
        Text(
            text = text,
            style = Ember.type.label.copy(fontSize = 12.sp),
            color = content,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .then(outline)
                .clip(shape)
                .background(background)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun ReadingBucket.tooltipLabel(range: StatisticsRange): String = when (range) {
    StatisticsRange.YEAR -> start.month.shortName()
    else -> start.weekdayLabel()
}

private fun ReadingBucket.accessibleLabel(range: StatisticsRange): String = when (range) {
    StatisticsRange.YEAR -> start.month.fullName()
    else -> "${start.dayOfWeek.fullName()}, ${start.shortLabel()}"
}

private fun ReadingBucket.axisLabel(range: StatisticsRange, index: Int): String = when (range) {
    StatisticsRange.WEEK -> start.dayOfWeek.initial()
    StatisticsRange.YEAR -> start.month.shortName().first().toString()
    else -> if (index % MONTH_LABEL_STEP == 0) start.dayOfMonth.toString() else ""
}
