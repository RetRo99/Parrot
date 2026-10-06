package com.retro99.books.ui.positions

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.retro99.base.ui.compose.Ember

internal fun boldParts(text: String, vararg parts: String): AnnotatedString = buildAnnotatedString {
    append(text)
    parts.filter { it.isNotBlank() }.forEach { part ->
        val start = text.indexOf(part)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + part.length)
    }
}

@Composable
internal fun PositionsText(
    text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 13.sp,
    bold: Boolean = false, title: Boolean = false, error: Boolean = false, maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Start,
) = PositionsText(AnnotatedString(text), modifier, fontSize, bold, title, error, maxLines, textAlign)

@Composable
internal fun PositionsText(
    text: AnnotatedString, modifier: Modifier = Modifier, fontSize: TextUnit = 13.sp,
    bold: Boolean = false, title: Boolean = false, error: Boolean = false, maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Start,
) {
    BasicText(text, modifier, style = (if (title) Ember.type.screenTitle else Ember.type.meta).copy(
        fontSize = if (title) 22.sp else fontSize,
        lineHeight = if (title) 28.sp else fontSize * 1.35f,
        fontWeight = if (bold || title || (error && Ember.style.isEink)) FontWeight.Bold else FontWeight.Normal,
        color = if (error) Ember.colors.error else if (bold || title) Ember.colors.ink else Ember.colors.ink2,
        textAlign = textAlign,
    ), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun PositionsButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, busy: Boolean = false) {
    Row(modifier.heightIn(min = 52.dp).clip(CircleShape)
        .background(Ember.colors.accent).alpha(if (enabled || busy) 1f else .45f)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (busy && !Ember.style.isEink) { PositionsBusy(); Spacer(Modifier.width(8.dp)) }
        BasicText(text, style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = Ember.colors.onAccent))
    }
}

@Composable
internal fun PositionsLink(text: String, onClick: () -> Unit) {
    BasicText(text, Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), style = Ember.type.meta.copy(fontSize = 13.sp,
        fontWeight = FontWeight.Bold, color = Ember.colors.accentText))
}

@Composable
internal fun PositionsCloseButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        BasicText("×", Modifier.clearAndSetSemantics {}, style = Ember.type.meta.copy(
            fontSize = 24.sp, color = Ember.colors.ink2))
    }
}

@Composable
internal fun PositionsBusy() {
    if (Ember.style.isEink) return
    val transition = rememberInfiniteTransition(label = "positions busy")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angle")
    val color = Ember.colors.ink2
    Canvas(Modifier.size(18.dp).padding(2.dp)) {
        drawArc(color, angle, 270f, false, style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun PositionsEmptyState(title: String, explanation: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PositionsText(title, title = true, textAlign = TextAlign.Center)
        if (explanation.isNotEmpty()) PositionsText(explanation, fontSize = 15.sp, textAlign = TextAlign.Center)
        PositionsButton(action, onClick)
    }
}

@Composable
internal fun PositionRadio(selected: Boolean) {
    Box(Modifier.size(22.dp).border(2.dp, if (selected) Ember.colors.accent else Ember.colors.chipBorder, CircleShape),
        contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(9.dp).background(Ember.colors.accent, CircleShape))
    }
}

@Composable
internal fun PositionCheckbox(checked: Boolean, enabled: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    val checkColor = Ember.colors.onAccent
    Box(Modifier.size(26.dp).background(if (checked) Ember.colors.accent else Color.Transparent, shape)
        .border(2.dp, if (checked) Ember.colors.accent else if (enabled) Ember.colors.ink2 else Ember.colors.chipBorder, shape),
        contentAlignment = Alignment.Center) {
        if (checked) Canvas(Modifier.size(16.dp)) {
            val stroke = 2.5.dp.toPx()
            drawLine(checkColor, Offset(size.width * .15f, size.height * .5f),
                Offset(size.width * .4f, size.height * .75f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(checkColor, Offset(size.width * .4f, size.height * .75f),
                Offset(size.width * .85f, size.height * .25f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
