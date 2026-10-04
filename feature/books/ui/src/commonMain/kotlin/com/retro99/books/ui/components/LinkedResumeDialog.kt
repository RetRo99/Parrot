package com.retro99.books.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import com.retro99.base.ui.compose.EmberDialog
import com.retro99.base.ui.compose.EmberDialogAction
import com.retro99.base.ui.compose.EmberDialogActionStyle
import com.retro99.base.ui.compose.relativeTimeText
import com.retro99.books.domain.model.BookHome
import com.retro99.books.ui.links.label
import com.retro99.reader.domain.linked.LinkedResumeOffer
import com.retro99.reader.domain.translate.TranslationConfidence
import com.retro99.sync.domain.ObservedTime
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.resume_linked_body
import resources.translations.resume_linked_compare
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
 * Three choices: they stack vertically with the main choice first and the safe option last.
 */
@Composable
fun LinkedResumeDialog(
    model: LinkedResumeUiModel,
    onContinue: () -> Unit,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
    onCompareAll: (() -> Unit)? = null,
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
    EmberDialog(
        onDismissRequest = onStay,
        title = title,
        actions = listOfNotNull(
            EmberDialogAction(
                label = stringResource(StringRes.resume_linked_continue),
                style = EmberDialogActionStyle.Main,
                onClick = onContinue,
            ),
            onCompareAll?.let { compare ->
                EmberDialogAction(
                    label = stringResource(StringRes.resume_linked_compare),
                    style = EmberDialogActionStyle.Neutral,
                    onClick = compare,
                )
            },
            EmberDialogAction(
                label = stringResource(StringRes.resume_linked_stay),
                style = EmberDialogActionStyle.Neutral,
                onClick = onStay,
            ),
        ),
        modifier = modifier,
        body = AnnotatedString(body),
    )
}
