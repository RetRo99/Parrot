package com.retro99.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_jump_go
import resources.translations.reader_jump_location
import resources.translations.reader_jump_minus
import resources.translations.reader_jump_plus
import resources.translations.reader_jump_title
import kotlin.math.roundToInt

private const val MAX_TICKS = 40

/**
 * "Jump to position" card for the Contents sheet. Nothing moves until Go is pressed.
 *
 * On e-ink the slider is replaced by ±1% / ±10% steppers to avoid continuous redraws.
 */
@Composable
internal fun JumpToPositionCard(
    currentProgress: Double,
    chapterTicks: List<Double>,
    fallbackChapterNumber: Int,
    isEink: Boolean,
    onGo: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(20.dp)
    var target by remember(currentProgress) {
        mutableFloatStateOf(currentProgress.toFloat().coerceIn(0f, 1f))
    }
    val percent = (target * 100).roundToInt()
    val chapterNumber = if (chapterTicks.isEmpty()) {
        fallbackChapterNumber
    } else {
        chapterTicks.indexOfLast { start -> start <= target + 1e-6 }.coerceAtLeast(0) + 1
    }
    val step = { deltaPercent: Int ->
        target = (target + deltaPercent / 100f).coerceIn(0f, 1f)
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .clip(shape)
            .background(if (isEink) colors.surface else colors.bg)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.line else colors.chipBorder, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(StringRes.reader_jump_title),
                modifier = Modifier.weight(1f),
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(StringRes.reader_jump_location, chapterNumber, percent),
                color = colors.ink2,
                fontSize = 14.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (isEink) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.track)
                    .border(1.dp, colors.line, RoundedCornerShape(2.dp)),
            ) {
                Box(Modifier.fillMaxWidth(target).height(3.dp).background(colors.accent))
            }
        } else {
            PositionSlider(
                value = target,
                ticks = chapterTicks,
                onValueChange = { target = it },
                description = stringResource(StringRes.reader_jump_title),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEink) {
                JumpButton(stringResource(StringRes.reader_jump_minus, 10), isEink) { step(-10) }
            }
            JumpButton(stringResource(StringRes.reader_jump_minus, 1), isEink) { step(-1) }
            JumpButton(stringResource(StringRes.reader_jump_plus, 1), isEink) { step(1) }
            if (isEink) {
                JumpButton(stringResource(StringRes.reader_jump_plus, 10), isEink) { step(10) }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (isEink) colors.ink else colors.accent)
                    .clickable(role = Role.Button) { onGo(target.toDouble()) }
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(StringRes.reader_jump_go),
                    color = if (isEink) colors.surface else colors.onAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun JumpButton(text: String, isEink: Boolean, onClick: () -> Unit) {
    val colors = Ember.colors
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.line else colors.chipBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Book-wide slider with a tick at the start of each chapter. */
@Composable
private fun PositionSlider(
    value: Float,
    ticks: List<Double>,
    onValueChange: (Float) -> Unit,
    description: String,
) {
    val colors = Ember.colors
    var widthPx by remember { mutableFloatStateOf(1f) }
    val thumbRadius = 11.dp
    val drawTicks = ticks.size in 2..MAX_TICKS
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics { contentDescription = description }
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onValueChange(fractionAt(offset.x, widthPx, thumbRadius.toPx())) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onValueChange(fractionAt(change.position.x, widthPx, thumbRadius.toPx()))
                }
            },
    ) {
        val inset = thumbRadius.toPx()
        val usable = (size.width - 2 * inset).coerceAtLeast(1f)
        val centerY = size.height / 2f
        val trackPx = 4.dp.toPx()
        drawRoundRect(
            color = colors.track,
            topLeft = Offset(inset, centerY - trackPx / 2f),
            size = Size(usable, trackPx),
            cornerRadius = CornerRadius(trackPx / 2f),
        )
        val thumbX = inset + usable * value
        drawRoundRect(
            color = colors.accent,
            topLeft = Offset(inset, centerY - trackPx / 2f),
            size = Size((thumbX - inset).coerceAtLeast(0f), trackPx),
            cornerRadius = CornerRadius(trackPx / 2f),
        )
        if (drawTicks) {
            ticks.drop(1).forEach { start ->
                val x = inset + usable * start.toFloat().coerceIn(0f, 1f)
                drawLine(
                    color = colors.mutedAccent,
                    start = Offset(x, centerY - 6.dp.toPx()),
                    end = Offset(x, centerY + 6.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        drawCircle(colors.surface, thumbRadius.toPx() + 2.dp.toPx(), Offset(thumbX, centerY))
        drawCircle(colors.accent, thumbRadius.toPx(), Offset(thumbX, centerY))
    }
}

private fun fractionAt(x: Float, widthPx: Float, insetPx: Float): Float =
    ((x - insetPx) / (widthPx - 2 * insetPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
