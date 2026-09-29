package com.retro99.reader.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.reader.domain.model.ProgressBarPosition
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.ui.model.ChapterInfo
import com.retro99.reader.ui.model.ChapterReadingTimeInfo
import com.retro99.reader.ui.model.PositionUiModel
import com.retro99.reader.ui.model.toUiModel

private const val SAMPLE_TIME = "16:44"
private const val SAMPLE_CHAPTER = "Chapter 3"

/**
 * Non-interactive preview of how the reader settings look: a sample page with the reader's own
 * progress bar. The bar is the same composable the reader uses, fed sample values, so it follows
 * the progress settings (visibility, position, chapter progress, progress line, time) exactly.
 *
 * @param settings The settings to preview; everything updates whenever they change.
 * @param showReadAloudHighlight Marks a sentence with the read-aloud highlight style and color.
 */
@Composable
fun ReaderSettingsPreview(
    settings: ReaderSettingsDomainModel,
    showReadAloudHighlight: Boolean,
    modifier: Modifier = Modifier,
) {
    val uiSettings = settings.toUiModel()
    val position = PositionUiModel(
        createdAt = null,
        href = "chapter.xhtml",
        type = "application/xhtml+xml",
        title = SAMPLE_CHAPTER,
        progression = 0.35,
        position = 12,
        totalProgression = 0.18,
        chapterIndex = 2,
        totalChapters = 12,
    )
    val chapterInfo = ChapterInfo(currentPage = 12, totalPages = 34, totalWords = 4200)
    val readingTime = ChapterReadingTimeInfo(remainingMinutes = 9, remainingWords = 2700, totalWords = 4200)
    val currentTime = if (settings.showCurrentTime) SAMPLE_TIME else ""

    Column(modifier = modifier) {
        if (settings.progressBarPosition == ProgressBarPosition.TOP) {
            AnimatedProgressBar(
                settings = uiSettings,
                areControlsVisible = true,
                position = ProgressBarPosition.TOP,
                lastKnownPosition = position,
                chapterReadingTimeInfo = readingTime,
                chapterInfo = chapterInfo,
                currentTime = currentTime,
            )
        }
        ReaderSettingsPreviewPage(
            settings = settings,
            showReadAloudHighlight = showReadAloudHighlight,
            modifier = Modifier.weight(1f),
        )
        if (settings.progressBarPosition == ProgressBarPosition.BOTTOM) {
            AnimatedProgressBar(
                settings = uiSettings,
                areControlsVisible = true,
                position = ProgressBarPosition.BOTTOM,
                lastKnownPosition = position,
                chapterReadingTimeInfo = readingTime,
                chapterInfo = chapterInfo,
                currentTime = currentTime,
            )
        }
    }
}

/** The platform's rendering of the sample page, without any progress bar. */
@Composable
internal expect fun ReaderSettingsPreviewPage(
    settings: ReaderSettingsDomainModel,
    showReadAloudHighlight: Boolean,
    modifier: Modifier = Modifier,
)
