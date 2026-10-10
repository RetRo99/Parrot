package com.retro99.reader.ui.reader

import resources.translations.saved_listening_bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.reader.ui.tts.TtsPreparationProgress
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.general_close
import resources.translations.reader_audio_about_left
import resources.translations.reader_audio_back_10
import resources.translations.reader_audio_change
import resources.translations.reader_audio_decrease
import resources.translations.reader_audio_forward_10
import resources.translations.reader_audio_increase
import resources.translations.reader_audio_left_in_chapter
import resources.translations.reader_audio_next_sentence
import resources.translations.reader_audio_no_narration
import resources.translations.reader_audio_only
import resources.translations.reader_audio_only_description
import resources.translations.reader_audio_pitch
import resources.translations.reader_audio_pitch_normal
import resources.translations.reader_tts_preparing
import resources.translations.reader_audio_previous_sentence
import resources.translations.reader_toc_next_chapter
import resources.translations.reader_toc_previous_chapter
import resources.translations.reader_audio_rate
import resources.translations.reader_audio_sentence
import resources.translations.reader_audio_sentence_of
import resources.translations.reader_audio_sleep_end_of_chapter
import resources.translations.reader_audio_sleep_minutes
import resources.translations.reader_audio_sleep_off
import resources.translations.reader_audio_sleep_remaining
import resources.translations.reader_audio_sleep_timer
import resources.translations.reader_audio_speed
import resources.translations.reader_audio_status_downloaded
import resources.translations.reader_audio_status_downloading
import resources.translations.reader_audio_status_downloading_unknown
import resources.translations.reader_audio_status_failed
import resources.translations.reader_audio_status_not_downloaded
import resources.translations.reader_audio_status_on_phone
import resources.translations.reader_audio_status_update
import resources.translations.reader_audio_stop
import resources.translations.reader_audio_voice_natural
import resources.translations.reader_audio_voice_system
import resources.translations.reader_overlay_device_voice
import resources.translations.reader_overlay_listening
import resources.translations.reader_overlay_narration
import resources.translations.reader_overlay_pause
import resources.translations.reader_overlay_play
import kotlin.math.abs
import kotlin.math.roundToInt

/** Average spoken time of one sentence at 1x; only used for "about N min left" estimates. */
internal const val ESTIMATED_SENTENCE_MS = 7_000L
private val SPEEDS = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)
private const val TTS_STEP = 0.1f
private const val MIN_TTS_VALUE = 0.5f
private const val MAX_TTS_VALUE = 2f
private const val SLEEP_SHORT_MINUTES = 15
private const val SLEEP_LONG_MINUTES = 30
private const val MS_PER_MINUTE = 60_000L

/** What the audio sheet shows. Narration and device voice each use their own subset. */
internal data class AudioSheetUi(
    val isNarration: Boolean,
    val chapterLabel: String,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val positionMs: Long,
    val totalMs: Long?,
    /** Null while read-aloud has no position to show (TTS-F24). */
    val sentencePosition: TtsSentencePosition?,
    val speed: Float,
    val rate: Float,
    val pitch: Float,
    val voice: TtsVoice?,
    val isVoicePreparing: Boolean,
    val preparationProgress: TtsPreparationProgress?,
    val voiceDownloadFailed: Boolean,
    val sleepRemainingMs: Long?,
    val isAudioOnly: Boolean,
    val isEink: Boolean,
    /** Null hides the prepared-chapter row: narration, no read-aloud or no chapter. */
    val preparedChapter: PreparedChapterRowState? = null,
    /** Name of the voice prepared audio was made for, when that voice is still installed. */
    val preparedChapterVoiceLabel: String? = null,
    /** What preparing the chapter on screen is about to cost, when it can be estimated. */
    val preparedChapterEstimate: PreparedChapterEstimate? = null,
    /** What Parrot Cloud adds to that row: one line of status and at most one button. */
    val preparedChapterCloud: PreparedChapterCloudInputs = PreparedChapterCloudInputs(),
) {
    /** Estimated time to the end of the chapter; real for narration, sentence-based otherwise. */
    val remainingInChapterMs: Long
        get() = if (isNarration) {
            ((totalMs ?: 0L) - positionMs).coerceAtLeast(0L)
        } else {
            val position = sentencePosition
            val remainingSentences = if (position == null) {
                0
            } else {
                (position.count - position.number).coerceAtLeast(0)
            }
            (remainingSentences * ESTIMATED_SENTENCE_MS / rate.coerceAtLeast(MIN_TTS_VALUE)).toLong()
        }
}

internal data class AudioSheetActions(
    val onDismiss: () -> Unit,
    val onStop: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPreviousChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSpeed: (Float) -> Unit,
    val onRate: (Float) -> Unit,
    val onPitch: (Float) -> Unit,
    val onChangeVoice: () -> Unit,
    val onStartSleepTimer: (Long) -> Unit,
    val onCancelSleepTimer: () -> Unit,
    val onAudioOnly: () -> Unit,
    val onSelectSource: (Boolean) -> Unit,
    /** Bookmarks the sentence being read. */
    val onBookmark: () -> Unit = {},
    val onPrepareChapter: () -> Unit = {},
    val onCancelChapterPreparation: () -> Unit = {},
    val onDeletePreparedChapter: () -> Unit = {},
    val onDownloadPreparedChapter: () -> Unit = {},
    val onManageCloudStorage: () -> Unit = {},
)

@Composable
internal fun ReaderAudioSheet(
    ui: AudioSheetUi,
    hasNarration: Boolean,
    canSwitchSource: Boolean,
    actions: AudioSheetActions,
) {
    val colors = Ember.colors
    EmberBottomSheet(onDismiss = actions.onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.94f)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(StringRes.reader_overlay_listening),
                            style = Ember.type.screenTitle.copy(fontSize = 24.sp, lineHeight = 32.sp),
                            color = colors.ink,
                        )
                        Text(
                            ui.chapterLabel,
                            color = colors.ink2,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = actions.onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, stringResource(StringRes.general_close), tint = colors.ink)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (canSwitchSource) {
                    SourceSwitch(ui.isNarration, ui.isEink, actions.onSelectSource)
                    Spacer(Modifier.height(16.dp))
                }
                if (ui.isNarration) {
                    NarrationBody(ui, actions)
                } else {
                    DeviceVoiceBody(ui, hasNarration, actions)
                }
                Spacer(Modifier.height(8.dp))
                BookmarkSentenceRow(ui.isEink, actions.onBookmark)
                Spacer(Modifier.height(8.dp))
                SleepTimerSection(ui, actions)
                if (ui.isNarration) {
                    Spacer(Modifier.height(12.dp))
                    AudioOnlyRow(ui.isAudioOnly, ui.isEink, actions.onAudioOnly)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = actions.onStop)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(StringRes.reader_audio_stop),
                        color = colors.accentText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Narration | Device voice segmented control; the callback receives true for narration. */
@Composable
private fun SourceSwitch(isNarration: Boolean, isEink: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = Ember.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (isEink) colors.surface else colors.bg)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.line else colors.chipBorder, CircleShape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        listOf(
            true to stringResource(StringRes.reader_overlay_narration),
            false to stringResource(StringRes.reader_overlay_device_voice),
        ).forEach { (narration, text) ->
            val selected = isNarration == narration
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (selected) selectedFill(isEink) else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(narration) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (narration) Icons.Default.Headphones else Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = if (selected) selectedContent(isEink) else colors.ink2,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text,
                    color = if (selected) selectedContent(isEink) else colors.ink2,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun NarrationBody(ui: AudioSheetUi, actions: AudioSheetActions) {
    val colors = Ember.colors
    val total = ui.totalMs ?: 0L
    val progress = if (total > 0L) (ui.positionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
    Slider(
        value = progress,
        onValueChange = { value -> if (total > 0L) actions.onSeek((value * total).toLong()) },
        modifier = Modifier.fillMaxWidth().height(32.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatAudioTime(ui.positionMs), color = colors.ink2, fontSize = 13.sp)
        Text(
            stringResource(
                StringRes.reader_audio_left_in_chapter,
                formatAudioTime(ui.remainingInChapterMs),
            ),
            color = colors.ink2,
            fontSize = 13.sp,
        )
    }
    Spacer(Modifier.height(12.dp))
    Transport(
        ui = ui,
        onPlayPause = actions.onPlayPause,
        onPreviousChapter = actions.onPreviousChapter,
        onNextChapter = actions.onNextChapter,
        start = {
            IconButton(onClick = actions.onSkipBack, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Default.Replay10,
                    stringResource(StringRes.reader_audio_back_10),
                    tint = colors.ink,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        end = {
            IconButton(onClick = actions.onSkipForward, modifier = Modifier.size(56.dp)) {
                Icon(
                    Icons.Default.Forward10,
                    stringResource(StringRes.reader_audio_forward_10),
                    tint = colors.ink,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
    )
    Spacer(Modifier.height(16.dp))
    SectionLabel(stringResource(StringRes.reader_audio_speed))
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (ui.isEink) colors.surface else colors.bg)
            .border(
                if (ui.isEink) 2.dp else 1.dp,
                if (ui.isEink) colors.line else colors.chipBorder,
                CircleShape,
            )
            .padding(3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SPEEDS.forEach { value ->
            val selected = abs(ui.speed - value) < 0.01f
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (selected) selectedFill(ui.isEink) else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton) { actions.onSpeed(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${formatSpeed(value)}×",
                    color = if (selected) selectedContent(ui.isEink) else colors.ink2,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DeviceVoiceBody(ui: AudioSheetUi, hasNarration: Boolean, actions: AudioSheetActions) {
    val colors = Ember.colors
    if (!hasNarration) {
        Text(
            stringResource(StringRes.reader_audio_no_narration),
            color = colors.ink2,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(12.dp))
    }
    val position = ui.sentencePosition
    val progress = if (position != null) position.number.toFloat() / position.count else 0f
    ReadOnlyProgress(progress, ui.isEink)
    Spacer(Modifier.height(6.dp))
    // No line at all until there is a position to put in it (TTS-F24).
    if (position != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(StringRes.reader_audio_sentence_of, position.number, position.count),
                color = colors.ink2,
                fontSize = 13.sp,
            )
            Text(
                stringResource(
                    StringRes.reader_audio_about_left,
                    (ui.remainingInChapterMs / MS_PER_MINUTE).toInt().coerceAtLeast(1),
                ),
                color = colors.ink2,
                fontSize = 13.sp,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Transport(
        ui = ui,
        onPlayPause = actions.onPlayPause,
        onPreviousChapter = actions.onPreviousChapter,
        onNextChapter = actions.onNextChapter,
        start = {
            LabelledSkip(
                icon = { Icon(Icons.Default.KeyboardDoubleArrowLeft, null, tint = colors.ink, modifier = Modifier.size(28.dp)) },
                label = stringResource(StringRes.reader_audio_sentence),
                description = stringResource(StringRes.reader_audio_previous_sentence),
                onClick = actions.onSkipBack,
            )
        },
        end = {
            LabelledSkip(
                icon = { Icon(Icons.Default.KeyboardDoubleArrowRight, null, tint = colors.ink, modifier = Modifier.size(28.dp)) },
                label = stringResource(StringRes.reader_audio_sentence),
                description = stringResource(StringRes.reader_audio_next_sentence),
                onClick = actions.onSkipForward,
            )
        },
    )
    Spacer(Modifier.height(16.dp))
    VoiceCard(ui, actions.onChangeVoice)
    ui.preparedChapter?.let { preparedChapter ->
        Spacer(Modifier.height(12.dp))
        PreparedChapterCard(
            state = preparedChapter,
            voiceLabel = ui.preparedChapterVoiceLabel,
            estimate = ui.preparedChapterEstimate,
            isEink = ui.isEink,
            actions = PreparedChapterActions(
                onPrepare = actions.onPrepareChapter,
                onCancel = actions.onCancelChapterPreparation,
                onDelete = actions.onDeletePreparedChapter,
                onOpenVoices = actions.onChangeVoice,
                onDownload = actions.onDownloadPreparedChapter,
                onManageStorage = actions.onManageCloudStorage,
            ),
            cloud = ui.preparedChapterCloud,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stepper(
            label = stringResource(StringRes.reader_audio_rate),
            value = "${formatSpeed(ui.rate)}×",
            isEink = ui.isEink,
            canDecrease = ui.rate > MIN_TTS_VALUE + 0.001f,
            canIncrease = ui.rate < MAX_TTS_VALUE - 0.001f,
            onDecrease = { actions.onRate(stepped(ui.rate, -TTS_STEP)) },
            onIncrease = { actions.onRate(stepped(ui.rate, TTS_STEP)) },
            modifier = Modifier.weight(1f),
        )
        Stepper(
            label = stringResource(StringRes.reader_audio_pitch),
            value = if (abs(ui.pitch - 1f) < 0.01f) {
                stringResource(StringRes.reader_audio_pitch_normal)
            } else {
                "${formatSpeed(ui.pitch)}×"
            },
            isEink = ui.isEink,
            canDecrease = ui.pitch > MIN_TTS_VALUE + 0.001f,
            canIncrease = ui.pitch < MAX_TTS_VALUE - 0.001f,
            onDecrease = { actions.onPitch(stepped(ui.pitch, -TTS_STEP)) },
            onIncrease = { actions.onPitch(stepped(ui.pitch, TTS_STEP)) },
            modifier = Modifier.weight(1f),
        )
    }
}

private fun stepped(value: Float, delta: Float): Float =
    ((value + delta) * 10f).roundToInt().div(10f).coerceIn(MIN_TTS_VALUE, MAX_TTS_VALUE)

@Composable
private fun Transport(
    ui: AudioSheetUi,
    onPlayPause: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    val colors = Ember.colors
    val label = stringResource(
        when {
            ui.isLoading -> StringRes.reader_tts_preparing
            ui.isPlaying -> StringRes.reader_overlay_pause
            else -> StringRes.reader_overlay_play
        },
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChapterSkipButton(
            icon = Icons.Default.SkipPrevious,
            description = stringResource(StringRes.reader_toc_previous_chapter),
            onClick = onPreviousChapter,
        )
        start()
        IconButton(
            onClick = onPlayPause,
            enabled = !ui.isLoading,
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(if (ui.isEink) colors.ink else colors.accent),
        ) {
            if (ui.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp).semantics { contentDescription = label },
                    color = if (ui.isEink) colors.surface else colors.onAccent,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    if (ui.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = label,
                    tint = if (ui.isEink) colors.surface else colors.onAccent,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        end()
        ChapterSkipButton(
            icon = Icons.Default.SkipNext,
            description = stringResource(StringRes.reader_toc_next_chapter),
            onClick = onNextChapter,
        )
    }
}

@Composable
private fun ChapterSkipButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, description, tint = Ember.colors.ink, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun LabelledSkip(
    icon: @Composable () -> Unit,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    Column(
        Modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(label, color = colors.ink, fontSize = 13.sp)
    }
}

@Composable
private fun VoiceCard(ui: AudioSheetUi, onChange: () -> Unit) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(20.dp)
    val voice = ui.voice
    val status = when {
        ui.voiceDownloadFailed -> stringResource(StringRes.reader_audio_status_failed)
        ui.isVoicePreparing -> {
            val percent = (ui.preparationProgress as? TtsPreparationProgress.Downloading)?.percentage
            if (percent != null) {
                stringResource(StringRes.reader_audio_status_downloading, percent)
            } else {
                stringResource(StringRes.reader_audio_status_downloading_unknown)
            }
        }
        voice == null || !voice.isNeural -> stringResource(StringRes.reader_audio_status_on_phone)
        voice.updateAvailable -> stringResource(StringRes.reader_audio_status_update)
        voice.isDownloaded -> stringResource(StringRes.reader_audio_status_downloaded)
        else -> stringResource(StringRes.reader_audio_status_not_downloaded)
    }
    val name = voice?.name?.substringBefore('(')?.trim()
        ?: stringResource(StringRes.reader_audio_voice_system)
    val title = if (voice?.isNeural == true) {
        "$name · ${stringResource(StringRes.reader_audio_voice_natural)}"
    } else {
        name
    }
    val detail = listOfNotNull(voice?.locale, status).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (ui.isEink) colors.surface else colors.bg)
            .border(if (ui.isEink) 2.dp else 1.dp, if (ui.isEink) colors.line else colors.chipBorder, shape)
            .clickable(role = Role.Button, onClick = onChange)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (ui.isEink) colors.ink else colors.navActive),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                tint = if (ui.isEink) colors.surface else colors.accentText,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                color = colors.ink2,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(StringRes.reader_audio_change),
            color = colors.accentText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    isEink: Boolean,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.line else colors.chipBorder, shape)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(value, color = colors.ink2, fontSize = 14.sp, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", stringResource(StringRes.reader_audio_decrease), canDecrease, isEink, onDecrease, Modifier.weight(1f))
            StepButton("+", stringResource(StringRes.reader_audio_increase), canIncrease, isEink, onIncrease, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StepButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    isEink: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .border(if (isEink) 2.dp else 1.dp, if (isEink) colors.line else colors.chipBorder, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = if (enabled) colors.ink else colors.ink2.copy(alpha = 0.4f),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

private enum class SleepChip { OFF, SHORT, LONG, END_OF_CHAPTER }

@Composable
private fun SleepTimerSection(ui: AudioSheetUi, actions: AudioSheetActions) {
    val colors = Ember.colors
    var selected by remember { mutableStateOf(if (ui.sleepRemainingMs == null) SleepChip.OFF else null) }
    LaunchedEffect(ui.sleepRemainingMs) {
        if (ui.sleepRemainingMs == null) selected = SleepChip.OFF
    }
    SectionLabel(stringResource(StringRes.reader_audio_sleep_timer))
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val chips = listOf(
            SleepChip.OFF to stringResource(StringRes.reader_audio_sleep_off),
            SleepChip.SHORT to stringResource(StringRes.reader_audio_sleep_minutes, SLEEP_SHORT_MINUTES),
            SleepChip.LONG to stringResource(StringRes.reader_audio_sleep_minutes, SLEEP_LONG_MINUTES),
            SleepChip.END_OF_CHAPTER to stringResource(StringRes.reader_audio_sleep_end_of_chapter),
        )
        chips.forEach { (chip, text) ->
            val isSelected = selected == chip
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) selectedFill(ui.isEink) else Color.Transparent)
                    .border(
                        if (ui.isEink) 2.dp else 1.dp,
                        if (isSelected) selectedFill(ui.isEink) else if (ui.isEink) colors.line else colors.chipBorder,
                        CircleShape,
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        selected = chip
                        when (chip) {
                            SleepChip.OFF -> actions.onCancelSleepTimer()
                            SleepChip.SHORT -> actions.onStartSleepTimer(SLEEP_SHORT_MINUTES * MS_PER_MINUTE)
                            SleepChip.LONG -> actions.onStartSleepTimer(SLEEP_LONG_MINUTES * MS_PER_MINUTE)
                            SleepChip.END_OF_CHAPTER ->
                                actions.onStartSleepTimer(ui.remainingInChapterMs.coerceAtLeast(MS_PER_MINUTE))
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = if (isSelected) selectedContent(ui.isEink) else colors.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
    ui.sleepRemainingMs?.let { remaining ->
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(StringRes.reader_audio_sleep_remaining, formatSleepTimerLabel(remaining)),
            color = colors.ink2,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun AudioOnlyRow(checked: Boolean, isEink: Boolean, onToggle: () -> Unit) {
    val colors = Ember.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(StringRes.reader_audio_only),
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(StringRes.reader_audio_only_description),
                color = colors.ink2,
                fontSize = 14.sp,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun BookmarkSentenceRow(isEink: Boolean, onClick: () -> Unit) {
    val colors = Ember.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Outlined.BookmarkAdd,
            contentDescription = null,
            tint = if (isEink) colors.ink else colors.accentText,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(StringRes.saved_listening_bookmark),
            color = colors.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Ember.colors.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.6.sp,
    )
}

@Composable
private fun ReadOnlyProgress(progress: Float, isEink: Boolean) {
    val colors = Ember.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (isEink) 8.dp else 4.dp)
            .clip(shape)
            .background(colors.track)
            .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(if (isEink) 8.dp else 4.dp)
                .background(if (isEink) colors.ink else colors.accent),
        )
    }
}

@Composable
private fun selectedFill(isEink: Boolean): Color = if (isEink) Ember.colors.ink else Ember.colors.accent

@Composable
private fun selectedContent(isEink: Boolean): Color = if (isEink) Ember.colors.surface else Ember.colors.onAccent
