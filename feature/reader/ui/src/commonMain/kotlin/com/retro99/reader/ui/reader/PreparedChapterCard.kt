package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_cancel
import resources.translations.reader_tts_delete
import resources.translations.reader_tts_prepared_chapter_delete_message
import resources.translations.reader_tts_prepared_chapter_delete_title
import resources.translations.reader_tts_prepared_chapter_title

/** What the row's buttons can ask for. */
internal data class PreparedChapterActions(
    val onPrepare: () -> Unit,
    val onCancel: () -> Unit,
    val onDelete: () -> Unit,
    val onOpenVoices: () -> Unit,
)

/**
 * The prepared-audio row of the chapter on screen, below the voice card. Same card shape,
 * progress bar and buttons as [VoicePackCard]; what it says and offers is decided by
 * [preparedChapterRowUi], so every state is covered by a test.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PreparedChapterCard(
    state: PreparedChapterRowState,
    voiceLabel: String?,
    isEink: Boolean,
    actions: PreparedChapterActions,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(20.dp)
    val ui = preparedChapterRowUi(state, voiceLabel)
    val title = stringResource(StringRes.reader_tts_prepared_chapter_title)
    val status = stringResource(ui.status, *ui.statusArgs.toTypedArray())
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.ink else colors.line, shape)
            .padding(16.dp)
            .semantics { contentDescription = "$title. $status" },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = Ember.type.cardTitle.copy(fontSize = 17.sp), color = colors.ink)
        if (ui.isFailure) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isEink) colors.surface else colors.error.copy(alpha = 0.14f))
                    .border(if (isEink) 2.dp else 1.dp, colors.error, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(status, color = if (isEink) colors.ink else colors.error, fontSize = 14.sp)
            }
        } else {
            ui.progress?.let { fraction -> PackProgressBar(fraction = fraction, isEink = isEink) }
            Text(
                status,
                color = if (state is PreparedChapterRowState.Ready) colors.accentText else colors.ink2,
                fontSize = 14.sp,
                fontWeight = if (state is PreparedChapterRowState.Ready) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (ui.actions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ui.actions.forEach { action ->
                    val label = stringResource(action.label)
                    when (action) {
                        PreparedChapterAction.DELETE -> OutlineButton(
                            text = "$label…",
                            color = colors.destructive,
                            isEink = isEink,
                            onClick = { confirmingDelete = true },
                        )
                        PreparedChapterAction.CANCEL -> OutlineButton(
                            text = label,
                            color = colors.ink,
                            isEink = isEink,
                            onClick = actions.onCancel,
                        )
                        PreparedChapterAction.OPEN_VOICES ->
                            PillButton(label, isEink, actions.onOpenVoices)
                        PreparedChapterAction.PREPARE,
                        PreparedChapterAction.PREPARE_AGAIN,
                        PreparedChapterAction.CONTINUE,
                        PreparedChapterAction.RETRY,
                        -> PillButton(label, isEink, actions.onPrepare)
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        EmberDialog(
            onDismissRequest = { confirmingDelete = false },
            title = stringResource(StringRes.reader_tts_prepared_chapter_delete_title),
            actions = listOf(
                EmberDialogAction(
                    label = stringResource(StringRes.reader_tts_delete),
                    style = EmberDialogActionStyle.Destructive,
                    onClick = {
                        confirmingDelete = false
                        actions.onDelete()
                    },
                ),
                EmberDialogAction(
                    label = stringResource(StringRes.general_cancel),
                    style = EmberDialogActionStyle.Neutral,
                    onClick = { confirmingDelete = false },
                ),
            ),
            content = {
                Text(
                    stringResource(StringRes.reader_tts_prepared_chapter_delete_message),
                    color = Ember.colors.ink2,
                    fontSize = 14.sp,
                )
            },
        )
    }
}
