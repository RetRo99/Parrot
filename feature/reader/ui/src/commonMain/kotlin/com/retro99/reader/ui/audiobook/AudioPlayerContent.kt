package com.retro99.reader.ui.audiobook

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.ui.compose.backdropColorScheme
import com.retro99.base.ui.compose.rememberDominantColorState
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.audiobook_all_tracks
import resources.translations.audiobook_forward_10
import resources.translations.audiobook_next_track
import resources.translations.audiobook_previous_track
import resources.translations.audiobook_rewind_10
import resources.translations.books_view_list
import resources.translations.general_close
import resources.translations.mini_player_pause
import resources.translations.mini_player_play
import resources.translations.reader_tts_speed
import resources.translations.settings_audio_progress_bar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    state: AudioPlayerState,
    callbacks: AudioPlayerCallbacks,
    titleFallback: String = "Audio Player",
    onBack: () -> Unit = callbacks.onClose,
) {
    if (state.error != null) {
        AudioPlayerErrorView(
            message = state.error,
            onClose = callbacks.onClose,
        )
        return
    }

    val dominantColorState = rememberDominantColorState(
        url = state.bookCoverUrl,
        cacheKey = state.bookUuid,
        defaultColor = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
            MaterialTheme.colorScheme.surface
        } else {
            Color(0xFF1A1A1A)
        },
    )
    val dominantColor by dominantColorState
    val hasBackdrop = state.bookCoverUrl != null
    val outerScheme = MaterialTheme.colorScheme
    val contentScheme = remember(dominantColor, hasBackdrop, outerScheme) {
        if (hasBackdrop) backdropColorScheme(dominantColor) else outerScheme
    }

    MaterialTheme(colorScheme = contentScheme) {
        Box(modifier = Modifier.fillMaxSize()) {
            state.bookCoverUrl?.let { url ->
                CoilImage(
                    data = url,
                    cacheKey = state.bookUuid,
                    modifier = Modifier.fillMaxSize().blur(60.dp),
                    contentScale = ContentScale.FillBounds,
                    contentDescription = null,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                )
            }

            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = state.bookTitle.ifEmpty { titleFallback },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(StringRes.general_close),
                                )
                            }
                        },
                        actions = {
                            if (!state.isLoading) {
                                PlaybackSpeedButton(
                                    currentSpeed = state.playbackSpeed,
                                    onSpeedSelected = callbacks.onSpeedChange,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                    )
                },
            ) { padding ->
                if (state.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    AudioPlayerContent(
                        state = state,
                        callbacks = callbacks,
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerContent(
    state: AudioPlayerState,
    callbacks: AudioPlayerCallbacks,
    modifier: Modifier = Modifier,
) {
    var showTrackSheet by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val collapseRangePx = with(density) { 200.dp.toPx() }
    val expandThreshold = with(density) { 50.dp.toPx() }
    var toolbarOffsetPx by remember { mutableFloatStateOf(0f) }
    var pendingExpandPx by remember { mutableFloatStateOf(0f) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    val collapseFraction = (-toolbarOffsetPx / collapseRangePx).coerceIn(0f, 1f)
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                flingJob?.cancel()
                flingJob = null

                val delta = available.y
                if (delta < 0f) {
                    pendingExpandPx = 0f
                    val newOffset = toolbarOffsetPx + delta
                    val clamped = newOffset.coerceIn(-collapseRangePx, 0f)
                    val consumed = clamped - toolbarOffsetPx
                    toolbarOffsetPx = clamped
                    return Offset(0f, consumed)
                } else if (delta > 0f && toolbarOffsetPx < 0f) {
                    pendingExpandPx += delta
                    if (pendingExpandPx >= expandThreshold) {
                        val newOffset = toolbarOffsetPx + delta
                        toolbarOffsetPx = newOffset.coerceIn(-collapseRangePx, 0f)
                    }
                    return Offset.Zero
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val target = if (-toolbarOffsetPx > collapseRangePx / 2f) {
                    -collapseRangePx
                } else {
                    0f
                }
                if (abs(toolbarOffsetPx - target) > 1f) {
                    flingJob = coroutineScope.launch {
                        Animatable(toolbarOffsetPx).animateTo(
                            targetValue = target,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        ) {
                            toolbarOffsetPx = value
                        }
                        flingJob = null
                    }
                }
                return Velocity.Zero
            }
        }
    }

    val coverEntrance = remember {
        androidx.compose.animation.core.Animatable(0.9f)
    }
    LaunchedEffect(Unit) {
        coverEntrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    val pulseTransition = rememberInfiniteTransition(label = "coverPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val coverScale = coverEntrance.value * if (state.isPlaying) pulseScale else 1f

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(lerp(16.dp, 8.dp, collapseFraction)))

            val coverSize = lerp(220.dp, 80.dp, collapseFraction)
            CoilImage(
                data = state.bookCoverUrl,
                cacheKey = state.bookCoverUrl,
                modifier = Modifier
                    .size(coverSize)
                    .scale(coverScale)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
                contentDescription = state.bookTitle,
            )

            Spacer(modifier = Modifier.height(lerp(20.dp, 12.dp, collapseFraction)))

            val titleAlpha = (1f - collapseFraction * 1.5f).coerceIn(0f, 1f)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = titleAlpha }
                    .clipToBounds()
                    .height(with(density) { lerp(80.dp, 0.dp, collapseFraction).toPx().toDp() }),
            ) {
                Text(
                    text = state.bookTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(6.dp))

                val trackTitle = state.trackTitles.getOrNull(state.currentTrackIndex)
                val displayTrackTitle = trackTitle?.ifBlank {
                    "Track ${state.currentTrackIndex + 1}"
                }
                if (displayTrackTitle != null) {
                    AnimatedContent(
                        targetState = displayTrackTitle,
                        transitionSpec = {
                            (slideInVertically { fullHeight -> fullHeight } + fadeIn(tween(200))) togetherWith
                                    (slideOutVertically { fullHeight -> -fullHeight } + fadeOut(tween(200)))
                        },
                        label = "trackTitleSwap",
                    ) { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            AudioPlayerSeekBar(
                currentPositionMs = state.currentPositionMs,
                totalDurationMs = state.totalDurationMs,
                onSeek = callbacks.onSeek,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val atFirstTrack = state.currentTrackIndex <= 0
                val atLastTrack = state.currentTrackIndex >= state.trackCount - 1
                IconButton(
                    onClick = callbacks.onPreviousTrack,
                    enabled = !atFirstTrack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(StringRes.audiobook_previous_track),
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = callbacks.onSkipBackward,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = stringResource(StringRes.audiobook_rewind_10),
                        modifier = Modifier.size(36.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { callbacks.onPlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp),
                        )
                    } else {
                        AnimatedContent(
                            targetState = if (state.isPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            transitionSpec = {
                                (scaleIn(initialScale = 0.4f, animationSpec = tween(150)) + fadeIn(
                                    tween(150)
                                )) togetherWith
                                        (scaleOut(
                                            targetScale = 0.4f,
                                            animationSpec = tween(150)
                                        ) + fadeOut(tween(150)))
                            },
                            label = "playPauseIcon",
                        ) { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = if (state.isPlaying) {
                                    stringResource(StringRes.mini_player_pause)
                                } else {
                                    stringResource(StringRes.mini_player_play)
                                },
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = callbacks.onSkipForward,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = stringResource(StringRes.audiobook_forward_10),
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(
                    onClick = callbacks.onNextTrack,
                    enabled = !atLastTrack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(StringRes.audiobook_next_track),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            val headerAlpha = (1f - collapseFraction * 1.5f).coerceIn(0f, 1f)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = headerAlpha }
                    .clipToBounds()
                    .height(with(density) { lerp(56.dp, 0.dp, collapseFraction).toPx().toDp() }),
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showTrackSheet = true }
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = stringResource(StringRes.books_view_list),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val currentTrackTitle = state.trackTitles.getOrNull(state.currentTrackIndex)
                    val trackHeaderText = if (currentTrackTitle.isNullOrBlank()) {
                        "Track ${state.currentTrackIndex + 1} of ${state.trackCount}"
                    } else {
                        "Track ${state.currentTrackIndex + 1} of ${state.trackCount} · $currentTrackTitle"
                    }
                    Text(
                        text = trackHeaderText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(StringRes.audiobook_all_tracks),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            TrackListSection(
                state = state,
                onTrackSelected = callbacks.onSelectTrack,
                listState = listState,
                nestedScrollConnection = nestedScrollConnection,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showTrackSheet) {
        TrackListBottomSheet(
            state = state,
            onTrackSelected = { index ->
                callbacks.onSelectTrack(index)
                showTrackSheet = false
            },
            onDismiss = { showTrackSheet = false },
        )
    }
}

@Composable
private fun PlaybackSpeedButton(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    TextButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Filled.Speed,
            contentDescription = stringResource(StringRes.reader_tts_speed),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${currentSpeed}x",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            speeds.forEach { speed ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${speed}x",
                            fontWeight = if (speed == currentSpeed) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            color = if (speed == currentSpeed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    onClick = {
                        onSpeedSelected(speed)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioPlayerSeekBar(
    currentPositionMs: Long,
    totalDurationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }

    val displayPosition = if (isDragging) dragPositionMs else currentPositionMs
    val sliderValue = if (totalDurationMs > 0) {
        (displayPosition.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val seekBarDescription = stringResource(StringRes.settings_audio_progress_bar)

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderValue,
            onValueChange = { newValue ->
                isDragging = true
                dragPositionMs = (newValue * totalDurationMs).toLong()
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragPositionMs)
            },
            modifier = Modifier.semantics { contentDescription = seekBarDescription },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(displayPosition),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackListSection(
    state: AudioPlayerState,
    onTrackSelected: (Int) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    nestedScrollConnection: NestedScrollConnection,
    modifier: Modifier = Modifier,
) {
    if (state.trackTitles.isEmpty()) return

    LaunchedEffect(state.currentTrackIndex) {
        if (state.currentTrackIndex >= 0) {
            listState.animateScrollToItem(state.currentTrackIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(nestedScrollConnection),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(
            items = state.trackTitles,
            key = { index, _ -> index },
        ) { index, title ->
            TrackItemRow(
                index = index,
                title = title,
                isCurrentTrack = index == state.currentTrackIndex,
                isPlaying = index == state.currentTrackIndex && state.isPlaying,
                onClick = { onTrackSelected(index) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackListBottomSheet(
    state: AudioPlayerState,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    LaunchedEffect(state.currentTrackIndex) {
        if (state.currentTrackIndex >= 0) {
            listState.animateScrollToItem(state.currentTrackIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Tracks (${state.trackCount})",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(
                    items = state.trackTitles,
                    key = { index, _ -> index },
                ) { index, title ->
                    TrackItemRow(
                        index = index,
                        title = title,
                        isCurrentTrack = index == state.currentTrackIndex,
                        isPlaying = index == state.currentTrackIndex && state.isPlaying,
                        onClick = { onTrackSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackItemRow(
    index: Int,
    title: String,
    isCurrentTrack: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isCurrentTrack) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }

    val textColor = if (isCurrentTrack) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCurrentTrack && isPlaying) {
            EqualizerIndicator(
                modifier = Modifier.size(width = 20.dp, height = 20.dp),
                barColor = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrentTrack) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title.ifBlank { "Track ${index + 1}" },
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AudioPlayerErrorView(message: String, onClose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onClose) {
                Text(stringResource(StringRes.general_close))
            }
        }
    }
}

@Composable
private fun EqualizerIndicator(
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val barCount = 3
    val durationsMs = listOf(500, 700, 600)
    val delayOffsetsMs = listOf(0, 150, 300)
    val initialValues = listOf(0.3f, 1f, 0.5f)
    val targetValues = listOf(1f, 0.3f, 1f)

    val barHeights = (0 until barCount).map { index ->
        transition.animateFloat(
            initialValue = initialValues[index],
            targetValue = targetValues[index],
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = durationsMs[index],
                    delayMillis = delayOffsetsMs[index],
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar$index",
        ).value
    }

    Canvas(modifier = modifier) {
        val barSpacingPx = 2.dp.toPx()
        val totalSpacing = barSpacingPx * (barCount - 1)
        val barWidth = (size.width - totalSpacing) / barCount
        barHeights.forEachIndexed { index, heightFraction ->
            val barHeight = size.height * heightFraction
            val x = index * (barWidth + barSpacingPx)
            val y = (size.height - barHeight) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    x = barWidth / 2f,
                    y = barWidth / 2f,
                ),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "${hours}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes}:${seconds.toString().padStart(2, '0')}"
    }
}
