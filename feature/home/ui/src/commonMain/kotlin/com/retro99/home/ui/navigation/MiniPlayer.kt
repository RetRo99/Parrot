package com.retro99.home.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.CoilImage
import com.retro99.reader.ui.playback.NowPlayingInfo
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.mini_player_now_playing
import resources.translations.mini_player_paused
import resources.translations.mini_player_pause
import resources.translations.mini_player_play
import resources.translations.mini_player_stop

/**
 * Mini-player component that shows currently playing audio.
 * Appears above the bottom navigation when audio is playing in the background.
 */
@Composable
fun MiniPlayer(
    nowPlayingInfo: NowPlayingInfo,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onPlayerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlayerClick),
        shadowElevation = 8.dp,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Cover art
            CoilImage(
                data = nowPlayingInfo.coverUrl,
                cacheKey = nowPlayingInfo.bookUuid,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
                contentDescription = nowPlayingInfo.bookTitle,
            )

            // Title
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = nowPlayingInfo.bookTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isPlaying) stringResource(StringRes.mini_player_now_playing) else stringResource(StringRes.mini_player_paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Play/Pause button
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(StringRes.mini_player_pause) else stringResource(StringRes.mini_player_play),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            // Stop/Close button
            IconButton(
                onClick = onStopClick,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(StringRes.mini_player_stop),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Animated container for MiniPlayer that slides in/out based on visibility.
 */
@Composable
fun AnimatedMiniPlayer(
    visible: Boolean,
    nowPlayingInfo: NowPlayingInfo?,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onPlayerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && nowPlayingInfo != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        nowPlayingInfo?.let { info ->
            MiniPlayer(
                nowPlayingInfo = info,
                isPlaying = isPlaying,
                onPlayPauseClick = onPlayPauseClick,
                onStopClick = onStopClick,
                onPlayerClick = onPlayerClick,
            )
        }
    }
}

