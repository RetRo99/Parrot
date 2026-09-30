package com.retro99.login.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.literataFamily
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.welcome_any_device
import resources.translations.welcome_eink_ready
import resources.translations.welcome_works_offline

private const val WELCOME_ART_HEIGHT = 450f
private const val DEMO_TEXT =
    "The lamp was still burning when she came back. She sat down, opened the book again, and found the line exactly where she had left it. Outside, the rain kept time."
private const val HIGHLIGHTED_SENTENCE =
    "She sat down, opened the book again, and found the line exactly where she had left it."

@Composable
internal fun WelcomeHeroPanel(
    height: androidx.compose.ui.unit.Dp,
    chapterLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val isEink = style.isEink
    val panelShape = RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(panelShape)
            .background(colors.welcomeHero)
            .then(if (isEink) Modifier.border(2.dp, colors.line, panelShape) else Modifier),
    ) {
        val illustrationScale = minOf(
            height.value / WELCOME_ART_HEIGHT,
            (height.value - 68f) / 364f,
        ).coerceIn(0.4f, 1f)

        // The page, book covers and player are decorative. The feature chips below
        // are kept separate so assistive technology encounters them after the title.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { },
        ) {
            DemoBookCover(
                color = colors.welcomeCoverGreen,
                rotation = -10f,
                x = -112.dp * illustrationScale,
                y = 134.dp * illustrationScale,
                scale = illustrationScale,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            DemoBookCover(
                color = colors.welcomeCoverRed,
                rotation = 10f,
                x = 112.dp * illustrationScale,
                y = 134.dp * illustrationScale,
                scale = illustrationScale,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            DemoPage(
                chapterLabel = chapterLabel,
                scale = illustrationScale,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            DemoPlayerPill(
                scale = illustrationScale,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        WelcomeFeatureChips(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun DemoBookCover(
    color: androidx.compose.ui.graphics.Color,
    rotation: Float,
    x: androidx.compose.ui.unit.Dp,
    y: androidx.compose.ui.unit.Dp,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val isEink = Ember.style.isEink
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = modifier
            .offset(x = x, y = y)
            .size(width = 176.dp, height = 200.dp)
            .graphicsLayer {
                rotationZ = rotation
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin.Center
            }
            .clip(shape)
            .background(color)
            .then(if (isEink) Modifier.border(2.dp, Ember.colors.line, shape) else Modifier),
    )
}

@Composable
private fun DemoPage(
    chapterLabel: String,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val pageShape = RoundedCornerShape(12.dp)
    val highlightStart = DEMO_TEXT.indexOf(HIGHLIGHTED_SENTENCE)
    val highlightEnd = highlightStart + HIGHLIGHTED_SENTENCE.length
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Column(
        modifier = modifier
            .offset(y = 90.dp * scale)
            .width(274.dp)
            .height(250.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .clip(pageShape)
            .background(colors.welcomePage)
            .then(if (isEink) Modifier.border(2.dp, colors.line, pageShape) else Modifier)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = chapterLabel,
            fontFamily = literataFamily(),
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            color = colors.welcomePageInk.copy(alpha = if (isEink) 1f else 0.6f),
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = DEMO_TEXT,
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val layout = textLayout ?: return@drawBehind
                    val boxesByLine = (highlightStart until highlightEnd)
                        .mapNotNull { index ->
                            val bounds = layout.getBoundingBox(index)
                            if (bounds.width <= 0f) null else layout.getLineForOffset(index) to bounds
                        }
                        .groupBy({ it.first }, { it.second })

                    boxesByLine.values.forEach { boxes ->
                        val left = boxes.minOf { it.left }
                        val right = boxes.maxOf { it.right }
                        val top = boxes.minOf { it.top }
                        val bottom = boxes.maxOf { it.bottom }
                        if (isEink) {
                            val underlineY = bottom + 1.dp.toPx()
                            drawLine(
                                color = colors.line,
                                start = Offset(left, underlineY),
                                end = Offset(right, underlineY),
                                strokeWidth = 2.dp.toPx(),
                            )
                        } else {
                            val horizontalPad = 2.dp.toPx()
                            val verticalPad = 1.dp.toPx()
                            drawRoundRect(
                                color = colors.welcomeHighlight,
                                topLeft = Offset(left - horizontalPad, top - verticalPad),
                                size = Size(
                                    right - left + horizontalPad * 2,
                                    bottom - top + verticalPad * 2,
                                ),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        }
                    }
                },
            onTextLayout = { textLayout = it },
            fontFamily = literataFamily(),
            fontSize = 14.5.sp,
            lineHeight = 23.2.sp,
            color = colors.welcomePageInk,
            textAlign = TextAlign.Start,
            maxLines = 7,
        )
    }
}

@Composable
private fun DemoPlayerPill(
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val shape = CircleShape
    Row(
        modifier = modifier
            .offset(y = 316.dp * scale)
            .width(200.dp)
            .height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
            .shadow(if (isEink) 0.dp else Ember.style.coverElevation, shape)
            .clip(shape)
            .background(colors.surface)
            .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Pause,
                contentDescription = null,
                tint = colors.onAccent,
                modifier = Modifier.size(16.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val barHeights = listOf(12, 18, 10, 24, 30, 21, 34, 18, 28, 14, 23, 12, 18, 10)
            barHeights.forEachIndexed { index, barHeight ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(barHeight.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < 6) colors.accent else colors.ink2.copy(alpha = 0.48f),
                        ),
                )
            }
        }
        Text(
            text = "4:12",
            style = Ember.type.label.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
            color = colors.ink2,
            maxLines = 1,
        )
    }
}

@Composable
private fun WelcomeFeatureChips(modifier: Modifier = Modifier) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val offline = stringResource(StringRes.welcome_works_offline)
    val anyDevice = stringResource(StringRes.welcome_any_device)
    val einkReady = stringResource(StringRes.welcome_eink_ready)

    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$offline · $anyDevice · $einkReady"
            traversalIndex = 2f
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeatureChip(
            label = offline,
            icon = Icons.Filled.Download,
            modifier = Modifier.weight(1f),
            isEink = isEink,
        )
        FeatureChip(
            label = anyDevice,
            icon = Icons.Filled.Sync,
            modifier = Modifier.weight(1f),
            isEink = isEink,
        )
        FeatureChip(
            label = einkReady,
            icon = Icons.Filled.TabletAndroid,
            modifier = Modifier.weight(1f),
            isEink = isEink,
        )
    }
}

@Composable
private fun FeatureChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isEink: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val shape = CircleShape
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(shape)
            .background(if (isEink) colors.surface else colors.welcomeChip)
            .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accentText,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = colors.accentText,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}
