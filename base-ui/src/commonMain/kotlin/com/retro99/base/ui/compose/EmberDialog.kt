package com.retro99.base.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Role of a dialog button. Decides its color; never the layout. */
enum class EmberDialogActionStyle {
    /** Cancel and other neutral choices — `ink`. */
    Neutral,

    /** The main choice of the dialog — `accentText`. */
    Main,

    /** Destructive confirmations — `err`. The label is unchanged by this component. */
    Destructive,
}

/** One text button in an [EmberDialog] action row. */
@Immutable
data class EmberDialogAction(
    val label: String,
    val style: EmberDialogActionStyle = EmberDialogActionStyle.Neutral,
    val enabled: Boolean = true,
    /** Shows a small spinner in place of the label while an operation runs. */
    val showProgress: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Ember dialog shell and surface in one: surface fill, 24dp radius, 22dp padding and
 * 24dp side margins; Fraunces 22sp title; body 14sp `ink2` at 21sp line height (key facts
 * in bold `ink`, see [emberDialogKeyFact]); right-aligned 48dp text buttons at 15sp Bold.
 *
 * Two or three short actions sit in one row (neutral left, the main choice right); three or
 * more actions — or labels too wide for the row — stack vertically, the main choice first
 * and destructive choices last. Wire [onDismissRequest] to the safe option: Back and taps
 * outside the dialog call it. E-ink: 2dp outline, no scrim.
 */
@Composable
fun EmberDialog(
    onDismissRequest: () -> Unit,
    title: String,
    actions: List<EmberDialogAction>,
    modifier: Modifier = Modifier,
    body: AnnotatedString? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    EmberDialogShell(onDismissRequest) {
        EmberDialogSurface(modifier) {
            Text(
                text = title,
                style = Ember.type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
                color = Ember.colors.ink,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (body != null) EmberDialogBody(body)
                content()
            }
            Spacer(modifier = Modifier.height(20.dp))
            EmberDialogActions(actions)
        }
    }
}

/**
 * Dialog body line: 14sp `ink2` at 21sp line height. Bold key facts inside a body go in
 * an [AnnotatedString] with [emberDialogKeyFact]; use this overload for plain text.
 */
@Composable
fun EmberDialogBody(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 21.sp),
        color = Ember.colors.ink2,
    )
}

/** Plain-text [EmberDialogBody]. */
@Composable
fun EmberDialogBody(text: String, modifier: Modifier = Modifier) {
    EmberDialogBody(AnnotatedString(text), modifier)
}

/** Span style for a key fact inside a dialog body: bold `ink` inside the `ink2` text. */
@Composable
fun emberDialogKeyFact(): SpanStyle = SpanStyle(color = Ember.colors.ink, fontWeight = FontWeight.Bold)

/**
 * Custom dialog host. E-ink uses a Popup (no dimmed scrim, focusable so Back and
 * tap-outside dismiss it); other modes use a regular [Dialog]. Both leave 24dp side
 * margins to the surface.
 */
@Composable
private fun EmberDialogShell(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val framed: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
    if (Ember.style.isEink) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(focusable = true),
            content = framed,
        )
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false),
            content = framed,
        )
    }
}

@Composable
private fun EmberDialogSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Ember.colors.surface,
        border = if (Ember.style.isEink) BorderStroke(2.dp, Ember.colors.line) else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            content = content,
        )
    }
}

@Composable
private fun EmberDialogActions(actions: List<EmberDialogAction>) {
    if (actions.isEmpty()) return
    val labelStyle = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val labelsWidth = actions.sumOf { action ->
            measurer.measure(AnnotatedString(action.label), labelStyle).size.width
        }
        val chrome = with(density) {
            (32.dp * actions.size + 12.dp * (actions.size - 1)).toPx()
        }
        val rowWidth = with(density) { (labelsWidth + chrome).toDp() }
        val stacked = actions.size >= 3 || rowWidth > maxWidth
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                stackOrder(actions).forEach { action -> DialogActionButton(action) }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowOrder(actions).forEachIndexed { index, action ->
                    if (index > 0) Spacer(modifier = Modifier.width(12.dp))
                    DialogActionButton(action)
                }
            }
        }
    }
}

/** Row order: neutrals first, the main choice and destructive choices to the right. */
private fun rowOrder(actions: List<EmberDialogAction>): List<EmberDialogAction> =
    actions.filter { it.style == EmberDialogActionStyle.Neutral } +
        actions.filter { it.style == EmberDialogActionStyle.Main } +
        actions.filter { it.style == EmberDialogActionStyle.Destructive }

/** Stacked order: the main choice first, destructive choices at the bottom. */
private fun stackOrder(actions: List<EmberDialogAction>): List<EmberDialogAction> =
    actions.filter { it.style == EmberDialogActionStyle.Main } +
        actions.filter { it.style == EmberDialogActionStyle.Neutral } +
        actions.filter { it.style == EmberDialogActionStyle.Destructive }

@Composable
private fun DialogActionButton(action: EmberDialogAction) {
    val contentColor = when (action.style) {
        EmberDialogActionStyle.Neutral -> Ember.colors.ink
        EmberDialogActionStyle.Main -> Ember.colors.accentText
        EmberDialogActionStyle.Destructive -> Ember.colors.destructive
    }
    TextButton(
        onClick = action.onClick,
        enabled = action.enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = contentColor,
            disabledContentColor = Ember.colors.ink2,
        ),
    ) {
        if (action.showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.height(18.dp).width(18.dp),
                strokeWidth = 2.dp,
                color = contentColor,
            )
        } else {
            Text(
                text = action.label,
                style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }
    }
}
