package com.retro99.home.ui.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import resources.translations.continue_reading_resume
import com.retro99.base.ui.compose.EmberProgress
import com.retro99.base.ui.compose.EmberCover
import com.retro99.base.ui.compose.Ember
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.CoilImage
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.books.ui.model.BookProgressInfoUiModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.app_settings_clear_current_book
import resources.translations.app_settings_clear_current_book_description
import resources.translations.continue_reading_clear
import resources.translations.continue_reading_title
import resources.translations.general_cancel

/**
 * Size of the floating bubble in dp.
 */
private val BUBBLE_SIZE = 56.dp

/**
 * A circular floating bubble that shows the currently reading book cover.
 * Designed to be used with [DraggableFloatingBubble] for drag-to-pin functionality.
 * Tapping it navigates directly to the reader.
 */
@Composable
fun ContinueReadingBubble(
    currentlyReading: CurrentlyReadingUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(BUBBLE_SIZE)
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
            )
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            if (currentlyReading.coverUrl != null) {
                // Show book cover filling the bubble
                CoilImage(
                    data = currentlyReading.coverUrl,
                    cacheKey = "continue_reading_bubble_${currentlyReading.bookUuid}",
                    contentDescription = currentlyReading.bookTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(BUBBLE_SIZE)
                        .clip(CircleShape),
                )
            } else {
                // Fallback to book icon
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = currentlyReading.bookTitle,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Ember "Continue reading" hero card: cover, title, author, progress and a Resume button.
 * A long press clears the shortcut, after the same confirmation Settings uses.
 *
 * @param progressInfo The book's progress as the library list holds it. The card shows that
 * same value, so it can never disagree with the book's row; the snapshot taken while reading
 * ([CurrentlyReadingUiModel.totalProgression]) is only a fallback for a book the library
 * hasn't loaded yet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContinueReadingShelf(
    currentlyReading: CurrentlyReadingUiModel,
    author: String?,
    progressInfo: BookProgressInfoUiModel?,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val type = Ember.type
    val cardShape = RoundedCornerShape(18.dp)
    val cardBorder = if (style.progressOutlined) {
        Modifier.border(2.dp, colors.line, cardShape)
    } else {
        Modifier
    }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val progression = progressInfo?.displayProgression ?: currentlyReading.totalProgression ?: 0.0
    val progressPercent = progressInfo?.progressPercent ?: currentlyReading.progressPercent

    Box(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .then(cardBorder)
            .clip(cardShape)
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showClearConfirmation = true },
                )
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EmberCover(
                data = currentlyReading.coverUrl,
                cacheKey = "continue_reading_shelf_${currentlyReading.bookUuid}",
                contentDescription = currentlyReading.bookTitle,
                elevation = style.coverElevation,
                modifier = Modifier.size(width = 96.dp, height = 144.dp),
            )

            Column(modifier = Modifier.weight(1f).height(144.dp)) {
                Text(
                    text = stringResource(StringRes.continue_reading_title).uppercase(),
                    style = type.eyebrow,
                    color = colors.accentText,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = currentlyReading.bookTitle,
                    style = type.cardTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!author.isNullOrBlank()) {
                    Text(
                        text = author,
                        style = type.author,
                        color = colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmberProgress(
                        progress = progression.toFloat(),
                        height = style.progressHeight,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$progressPercent%",
                        style = type.label,
                        color = colors.ink2,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onClick,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.onAccent,
                    ),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
                    modifier = Modifier.height(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(StringRes.continue_reading_resume),
                        style = type.label,
                    )
                }
            }
        }
    }

    if (showClearConfirmation) {
        EmberDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = stringResource(StringRes.app_settings_clear_current_book),
            body = AnnotatedString(stringResource(StringRes.app_settings_clear_current_book_description)),
            actions = listOf(
                EmberDialogAction(
                    label = stringResource(StringRes.general_cancel),
                    style = EmberDialogActionStyle.Neutral,
                    onClick = { showClearConfirmation = false },
                ),
                EmberDialogAction(
                    label = stringResource(StringRes.continue_reading_clear),
                    style = EmberDialogActionStyle.Destructive,
                    onClick = {
                        showClearConfirmation = false
                        onClear()
                    },
                ),
            ),
        )
    }
}

