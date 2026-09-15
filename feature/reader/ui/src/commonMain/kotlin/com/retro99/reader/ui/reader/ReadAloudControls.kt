package com.retro99.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.IntentDispatcher
import com.retro99.reader.ui.tts.TtsVoice
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.reader_tts_preparing
import resources.translations.reader_tts_system
import resources.translations.reader_tts_voice

private const val DISMISS_THRESHOLD_DP = 80

@Composable
internal fun ReadAloudControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long?,
    playbackSpeed: Float,
    sleepTimerRemainingMs: Long?,
    showAudioProgressBar: Boolean?,
    areControlsVisible: Boolean,
    voices: List<TtsVoice>,
    selectedVoiceId: String?,
    isVoicePreparing: Boolean,
    showAudioOnlyAction: Boolean,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    onOpenVoiceSettings: () -> Unit,
    onInteraction: () -> Unit = {},
    onSwipeDown: () -> Unit = {},
    onControlsDialogVisibilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val interactingDispatcher = IntentDispatcher<ReaderIntent> { intent ->
        onInteraction()
        intentDispatcher(intent)
    }

    var isExpanded by remember { mutableStateOf(false) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { DISMISS_THRESHOLD_DP.dp.toPx() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .offset { IntOffset(0, offsetY.toInt().coerceAtLeast(0)) }
            .draggable(
                state = rememberDraggableState { delta ->
                    if (!isExpanded) {
                        offsetY = (offsetY + delta).coerceAtLeast(0f)
                    } else {
                        offsetY = (offsetY + delta).coerceAtMost(0f)
                    }
                },
                orientation = Orientation.Vertical,
                onDragStarted = { onInteraction() },
                onDragStopped = { velocity ->
                    if (!isExpanded) {
                        if (offsetY > dismissThresholdPx || velocity > 500f) {
                            onSwipeDown()
                        } else if (offsetY < -dismissThresholdPx / 2 || velocity < -500f) {
                            isExpanded = true
                            offsetY = 0f
                        } else {
                            offsetY = 0f
                        }
                    } else {
                        if (-offsetY > dismissThresholdPx || velocity < -500f) {
                            isExpanded = false
                            offsetY = 0f
                        } else {
                            if (offsetY > dismissThresholdPx / 2 || velocity > 500f) {
                                isExpanded = false
                                offsetY = 0f
                            } else {
                                offsetY = 0f
                            }
                        }
                    }
                },
            ),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            PlaybackControlsRow(
                isPlaying = isPlaying,
                isLoading = isLoading,
                isExpanded = isExpanded,
                intentDispatcher = interactingDispatcher,
                onToggleExpand = {
                    isExpanded = !isExpanded
                    onInteraction()
                },
            )

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(300)) + shrinkVertically(tween(300)),
            ) {
                Column {
                    SecondaryControlsRow(
                        playbackSpeed = playbackSpeed,
                        voices = voices,
                        selectedVoiceId = selectedVoiceId,
                        isVoicePreparing = isVoicePreparing,
                        showAudioOnlyAction = showAudioOnlyAction,
                        sleepTimerRemainingMs = sleepTimerRemainingMs,
                        currentPositionMs = currentPositionMs,
                        totalDurationMs = totalDurationMs,
                        intentDispatcher = interactingDispatcher,
                        onOpenVoiceSettings = onOpenVoiceSettings,
                        onControlsDialogVisibilityChanged = onControlsDialogVisibilityChanged,
                    )

                    if (showAudioProgressBar != false && areControlsVisible) {
                        SeekBar(
                            currentPositionMs = currentPositionMs,
                            totalDurationMs = totalDurationMs,
                            onInteraction = onInteraction,
                        ) { positionMs -> interactingDispatcher(ReaderIntent.SeekTo(positionMs)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecondaryControlsRow(
    playbackSpeed: Float,
    voices: List<TtsVoice>,
    selectedVoiceId: String?,
    isVoicePreparing: Boolean,
    showAudioOnlyAction: Boolean,
    sleepTimerRemainingMs: Long?,
    currentPositionMs: Long,
    totalDurationMs: Long?,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    onOpenVoiceSettings: () -> Unit,
    onControlsDialogVisibilityChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PlaybackSpeedButton(playbackSpeed) { speed ->
            intentDispatcher(ReaderIntent.SetPlaybackSpeed(speed))
        }
        if (voices.isNotEmpty()) {
            VoiceButton(
                voices = voices,
                selectedVoiceId = selectedVoiceId,
                isPreparing = isVoicePreparing,
                onOpenVoiceSettings = onOpenVoiceSettings,
            )
        }
        SleepTimerButton(
            currentPositionMs = currentPositionMs,
            totalDurationMs = totalDurationMs,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            intentDispatcher = intentDispatcher,
            onTimerMenuVisibilityChanged = onControlsDialogVisibilityChanged,
        )
        if (showAudioOnlyAction) {
            IconButton(onClick = { intentDispatcher(ReaderIntent.ToggleAudioOnlyMode) }) {
                Icon(
                    imageVector = Icons.Outlined.Headphones,
                    contentDescription = "Audio only mode",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaybackControlsRow(
    isPlaying: Boolean,
    isLoading: Boolean,
    isExpanded: Boolean,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = { intentDispatcher(ReaderIntent.SkipBackward()) }) {
            Icon(Icons.Default.Replay10, "Skip backward", Modifier.size(28.dp))
        }
        IconButton(
            onClick = { intentDispatcher(ReaderIntent.TogglePlayback) },
            enabled = !isLoading,
            modifier = Modifier
                .size(48.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            if (isLoading) {
                val loadingDescription = stringResource(StringRes.reader_tts_preparing)
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(24.dp)
                        .semantics { contentDescription = loadingDescription },
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        IconButton(onClick = { intentDispatcher(ReaderIntent.SkipForward()) }) {
            Icon(Icons.Default.Forward10, "Skip forward", Modifier.size(28.dp))
        }

        IconButton(onClick = onToggleExpand) {
            val rotation by animateFloatAsState(
                targetValue = if (isExpanded) 180f else 0f,
                animationSpec = tween(300),
                label = "expandIconRotation",
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SeekBar(
    currentPositionMs: Long,
    totalDurationMs: Long?,
    onInteraction: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val duration = totalDurationMs ?: 0L
    val progress = if (duration > 0) currentPositionMs.toFloat() / duration else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress,
            onValueChange = {
                onInteraction()
                if (duration > 0) onSeek((it * duration).toLong())
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(formatDuration(currentPositionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VoiceButton(
    voices: List<TtsVoice>,
    selectedVoiceId: String?,
    isPreparing: Boolean,
    onOpenVoiceSettings: () -> Unit,
) {
    val selectedName = voices
        .firstOrNull { voice -> voice.id == selectedVoiceId }
        ?.name
        ?.substringBefore('(')
        ?.trim()
        .takeUnless { selectedVoiceName -> selectedVoiceName.isNullOrEmpty() }
        ?: stringResource(StringRes.reader_tts_system)
    TextButton(onClick = onOpenVoiceSettings) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(StringRes.reader_tts_voice),
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isPreparing) {
                stringResource(StringRes.reader_tts_preparing)
            } else {
                selectedName
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun PlaybackSpeedButton(currentSpeed: Float, onSpeedSelected: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    TextButton(onClick = { expanded = true }) {
        Text("${currentSpeed}x", style = MaterialTheme.typography.labelLarge)
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            speeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text("${speed}x") },
                    onClick = { onSpeedSelected(speed); expanded = false },
                )
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val paddedMinutes = minutes.toString().padStart(2, '0')
    val paddedSeconds = seconds.toString().padStart(2, '0')
    return if (hours > 0) {
        "$hours:$paddedMinutes:$paddedSeconds"
    } else {
        "$minutes:$paddedSeconds"
    }
}

@Composable
private fun SleepTimerButton(
    currentPositionMs: Long,
    totalDurationMs: Long?,
    sleepTimerRemainingMs: Long?,
    intentDispatcher: IntentDispatcher<ReaderIntent>,
    onTimerMenuVisibilityChanged: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomTimerDialog by remember { mutableStateOf(false) }
    val remainingToEndMs = totalDurationMs
        ?.minus(currentPositionMs)
        ?.takeIf { it > 0L }

    LaunchedEffect(expanded, showCustomTimerDialog) {
        onTimerMenuVisibilityChanged(expanded || showCustomTimerDialog)
    }

    DisposableEffect(Unit) {
        onDispose { onTimerMenuVisibilityChanged(false) }
    }

    TextButton(onClick = { expanded = true }) {
        Icon(
            imageVector = if (sleepTimerRemainingMs == null) Icons.Default.Timer else Icons.Default.TimerOff,
            contentDescription = "Sleep timer",
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = sleepTimerRemainingMs?.let { formatSleepTimerLabel(it) } ?: "Timer",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            SleepTimerPreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label) },
                    onClick = {
                        intentDispatcher(ReaderIntent.StartSleepTimer(preset.durationMs))
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Custom...") },
                onClick = {
                    expanded = false
                    showCustomTimerDialog = true
                },
            )
            if (remainingToEndMs != null) {
                DropdownMenuItem(
                    text = { Text("End of audio") },
                    onClick = {
                        intentDispatcher(ReaderIntent.StartSleepTimer(remainingToEndMs))
                        expanded = false
                    },
                )
            }
            if (sleepTimerRemainingMs != null) {
                DropdownMenuItem(
                    text = { Text("Cancel timer") },
                    onClick = {
                        intentDispatcher(ReaderIntent.CancelSleepTimer)
                        expanded = false
                    },
                )
            }
        }
    }

    if (showCustomTimerDialog) {
        SleepTimerDurationDialog(
            title = "Custom sleep timer",
            message = "Choose how long playback should continue before pausing.",
            confirmLabel = "Start",
            dismissLabel = "Cancel",
            initialMinutes = sleepTimerRemainingMs
                ?.let { ((it + 59_999L) / 60_000L).toInt().coerceAtLeast(1) }
                ?: 5,
            onConfirm = { minutes ->
                intentDispatcher(ReaderIntent.StartSleepTimer(minutes * 60_000L))
                showCustomTimerDialog = false
            },
            onDismiss = { showCustomTimerDialog = false },
        )
    }
}

private enum class SleepTimerPreset(
    val label: String,
    val durationMs: Long,
) {
    FiveMinutes("5 minutes", 5 * 60_000L),
    TenMinutes("10 minutes", 10 * 60_000L),
    FifteenMinutes("15 minutes", 15 * 60_000L),
    ThirtyMinutes("30 minutes", 30 * 60_000L),
    SixtyMinutes("60 minutes", 60 * 60_000L),
}
