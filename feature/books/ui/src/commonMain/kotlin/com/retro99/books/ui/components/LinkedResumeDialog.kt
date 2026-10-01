package com.retro99.books.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.books.domain.model.BookHome
import com.retro99.books.ui.links.label
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.sync.domain.ObservedTime
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.resume_linked_body
import resources.translations.resume_linked_continue
import resources.translations.resume_linked_listened_body
import resources.translations.resume_linked_source_audiobook
import resources.translations.resume_linked_source_ebook
import resources.translations.resume_linked_source_readaloud
import resources.translations.resume_linked_stay
import resources.translations.resume_linked_this_place
import resources.translations.resume_linked_title_approximate
import resources.translations.resume_linked_title_exact

enum class LinkedCopyFormat { Readaloud, Audiobook, Ebook }

/** What the resume prompt shows (§1.3, step 3). */
data class LinkedResumeUiModel(
    val chapterTitle: String?,
    val percent: Int,
    val isApproximate: Boolean,
    val sourceHome: BookHome,
    val sourceFormat: LinkedCopyFormat,
    val isListening: Boolean,
    val observedAtMillis: Long?,
)

fun LinkedResumeOffer.toUiModel() = LinkedResumeUiModel(
    chapterTitle = translated.position.locatorTitle,
    percent = ((translated.position.totalProgression ?: 0.0).coerceIn(0.0, 1.0) * 100).toInt(),
    isApproximate = translated.confidence == TranslationConfidence.Approximate,
    sourceHome = source.home,
    sourceFormat = when {
        isListening -> LinkedCopyFormat.Audiobook
        source.hasReadaloud -> LinkedCopyFormat.Readaloud
        else -> LinkedCopyFormat.Ebook
    },
    isListening = isListening,
    observedAtMillis = ObservedTime.toEpochMillis(observedAt),
)

/**
 * "Continue from …?" when another linked copy was read more recently. Replaces the same-copy
 * conflict dialog for this opening, so there's never more than one prompt.
 */
@Composable
fun LinkedResumeDialog(
    model: LinkedResumeUiModel,
    onContinue: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
    compareAll: (@Composable () -> Unit)? = null,
) {
    val title = if (model.isApproximate) {
        stringResource(StringRes.resume_linked_title_approximate, model.percent)
    } else {
        stringResource(
            StringRes.resume_linked_title_exact,
            model.chapterTitle ?: stringResource(StringRes.resume_linked_this_place),
        )
    }
    val sourceName = model.sourceHome.label()
    val sourceFormat = when (model.sourceFormat) {
        LinkedCopyFormat.Readaloud -> StringRes.resume_linked_source_readaloud
        LinkedCopyFormat.Audiobook -> StringRes.resume_linked_source_audiobook
        LinkedCopyFormat.Ebook -> StringRes.resume_linked_source_ebook
    }
    val source = stringResource(sourceFormat, sourceName)
    val time = model.observedAtMillis?.let { millis -> relativeTimeText(millis) }.orEmpty()
    val body = if (model.isListening) {
        stringResource(StringRes.resume_linked_listened_body, source, time)
    } else {
        stringResource(StringRes.resume_linked_body, source, time)
    }
    AlertDialog(
        // Dismissing without an answer would leave the open undecided.
        onDismissRequest = {},
        modifier = modifier,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        text = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Row {
                compareAll?.invoke()
                TextButton(onClick = onStay) {
                    Text(stringResource(StringRes.resume_linked_stay))
                }
                TextButton(onClick = onContinue) {
                    Text(stringResource(StringRes.resume_linked_continue))
                }
            }
        },
    )
}
