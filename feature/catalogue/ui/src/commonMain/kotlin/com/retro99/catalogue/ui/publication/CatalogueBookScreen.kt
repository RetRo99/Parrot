package com.retro99.catalogue.ui.publication

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.nowMillis
import com.retro99.base.languageDisplayName
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberProgress
import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueDescriptionFormat
import com.retro99.catalogue.domain.sanitizeCatalogueDescription
import com.retro99.catalogue.ui.browse.*
import com.retro99.catalogue.ui.cover.CatalogueCover
import com.retro99.catalogue.ui.description.CatalogueDescriptionText
import com.retro99.server.api.CatalogueDescription
import com.retro99.server.api.CatalogueImageModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.*

class CatalogueBookActions(
    val onBack: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onFiles: () -> Unit = {},
    val onCloseFiles: () -> Unit = {},
    val onChooseFile: (Int) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRead: () -> Unit = {},
    val onFailureAction: () -> Unit = {},
    val onProvider: (String) -> Unit = {},
    val onSignIn: (String, String) -> Unit = { _, _ -> },
    val onDismissSignIn: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onCatalogueSettings: () -> Unit = {},
)

/** Pure presentation, shared by the live route and the isolated board fixtures. */
@Composable
fun CatalogueBookContentScreen(
    sourceId: String,
    state: CatalogueBookState,
    actions: CatalogueBookActions = CatalogueBookActions(),
    modifier: Modifier = Modifier,
    nowEpochMillis: Long = nowMillis(),
    isIos: Boolean = CatalogueDownloadPlatform.isIos,
) {
    state.loadContent?.let { content ->
        CatalogueBrowseContentScreen(
            CatalogueBrowseState(catalogueName = state.catalogueName, title = state.book?.displayTitle(), content = content, signIn = state.signIn),
            CatalogueBrowseActions(onBack = actions.onBack, onRetry = actions.onRetryLoad, onCatalogueSettings = actions.onCatalogueSettings,
                onSignIn = actions.onSignIn, onDismissSignIn = actions.onDismissSignIn), modifier = modifier,
        )
        return
    }
    Column(modifier.fillMaxSize().background(Ember.colors.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(StringRes.general_back), tint = Ember.colors.ink)
            }
        }
        val book = state.book
        if (book == null) {
            Text(stringResource(StringRes.catalogue_opening, state.catalogueName), style = Ember.type.meta, color = Ember.colors.ink2, modifier = Modifier.padding(20.dp))
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CatalogueCover(book.images.firstOrNull()?.let { CatalogueImageModel(sourceId, it.href) }, book.displayTitle(), Modifier.size(112.dp, 164.dp))
                    Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(book.displayTitle(), style = Ember.type.screenTitle.copy(fontSize = 23.sp, lineHeight = 27.sp), color = Ember.colors.ink, modifier = Modifier.semantics { heading() })
                        book.displayAuthor()?.let { Text(it, style = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 21.sp), color = Ember.colors.ink2) }
                        val metadata = listOfNotNull(book.languages.takeIf { it.isNotEmpty() }?.map { languageDisplayName(it) }?.joinToString(", "), book.year?.takeIf(String::isNotBlank)).joinToString(" · ")
                        if (metadata.isNotEmpty()) Text(metadata, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 19.sp), color = Ember.colors.ink2)
                        val subjects = book.subjects.mapNotNull { it.display() }.joinToString(", ")
                        if (subjects.isNotEmpty()) Text(subjects, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 19.sp), color = Ember.colors.ink2)
                    }
                }
                BookAction(state, actions, nowEpochMillis, isIos)
                val description = book.content
                val body = description?.body.display() ?: book.summary.display()
                if (!body.isNullOrBlank()) {
                    val format = when (description?.format) {
                        CatalogueDescription.Format.Html -> CatalogueDescriptionFormat.Html
                        CatalogueDescription.Format.Xhtml -> CatalogueDescriptionFormat.Xhtml
                        else -> CatalogueDescriptionFormat.Text
                    }
                    CatalogueDescriptionText(remember(body, format) { sanitizeCatalogueDescription(body, format) }, Modifier.padding(top = 16.dp), textStyle = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 23.25.sp))
                }
                HorizontalDivider(Modifier.padding(top = 18.dp, bottom = 10.dp), color = Ember.colors.line, thickness = Ember.style.border)
                MetaLine(stringResource(StringRes.catalogue_from), state.catalogueName)
                book.rights.display()?.takeIf(String::isNotBlank)?.let { rights ->
                    MetaLine(stringResource(StringRes.catalogue_rights), rights)
                    Text(stringResource(StringRes.catalogue_rights_law_note), style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Ember.colors.ink2, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    state.signIn?.let {
        SignInSheet(state.catalogueName, it, CatalogueBrowseActions(onSignIn = actions.onSignIn, onDismissSignIn = actions.onDismissSignIn), com.retro99.catalogue.ui.add.catalogueDeviceName())
    }
    if (state.chooseFile) CatalogueFileSheet(state, actions)
}

@Composable
private fun MetaLine(label: String, value: String) {
    Text(androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = Ember.colors.ink)); append(label); pop(); append(" "); append(value)
    }, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Ember.colors.ink2, modifier = Modifier.padding(top = 3.dp))
}

@Composable
private fun BookAction(state: CatalogueBookState, actions: CatalogueBookActions, now: Long, ios: Boolean) {
    when (val action = state.action) {
        is BookMainAction.Download -> {
            val size = action.file.size?.let(::catalogueMegabytes)
            val label = stringResource(if (size == null) StringRes.catalogue_book_download else StringRes.catalogue_book_download_size, size.orEmpty())
            val downloadLabel = stringResource(if (size == null) StringRes.catalogue_a11y_book_download else StringRes.catalogue_a11y_book_download_size, state.book?.displayTitle().orEmpty(), size.orEmpty())
            BookButton(label, actions.onDownload, icon = true, modifier = Modifier.semantics { contentDescription = downloadLabel })
            val otherLabel = stringResource(StringRes.catalogue_a11y_other_files, state.files.size - 1)
            if (state.files.size > 1) TextButton(onClick = actions.onFiles, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).semantics { contentDescription = otherLabel }) {
                Text(stringResource(StringRes.catalogue_other_files, state.files.size - 1), style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = Ember.colors.accentText)
            }
        }
        is BookMainAction.Waiting -> ActionCard {
            CardTitle(stringResource(StringRes.catalogue_state_waiting))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    if (action.otherDownloads > 0) CardBody(stringResource(if (action.otherDownloads >= 2) StringRes.catalogue_waiting_two_others else StringRes.catalogue_waiting_one_other))
                }
                CancelButton(actions.onCancel)
            }
        }
        is BookMainAction.Downloading -> ActionCard {
            val progress = action.total?.let { (action.bytes.toFloat() / it).coerceIn(0f, 1f) }
            val announcement = if (progress == null) stringResource(StringRes.catalogue_a11y_downloading, state.book?.displayTitle().orEmpty())
                else stringResource(StringRes.catalogue_announce_progress, state.book?.displayTitle().orEmpty(), ((progress * 100).toInt() / 25 * 25).coerceAtMost(75))
            Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { CardTitle(stringResource(StringRes.catalogue_state_downloading)) }
                CardBody(if (action.total == null) stringResource(StringRes.catalogue_so_far, catalogueMegabytes(action.bytes)) else stringResource(StringRes.catalogue_progress_of_size, catalogueMegabytes(action.bytes).removeSuffix(" MB"), catalogueMegabytes(action.total)))
            }
            if (progress != null) EmberProgress(progress = progress, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), height = Ember.style.detailProgressHeight)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(if (ios) StringRes.catalogue_download_keep_open_ios else StringRes.catalogue_download_keeps_going),
                    style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = if (ios) FontWeight.Bold else FontWeight.Normal),
                    color = if (ios) Ember.colors.ink else Ember.colors.ink2, modifier = Modifier.weight(1f))
                CancelButton(actions.onCancel)
            }
        }
        BookMainAction.Adding -> ActionCard {
            CardTitle(stringResource(StringRes.catalogue_state_adding))
            CardBody(stringResource(StringRes.catalogue_adding_detail), Modifier.padding(top = 8.dp))
        }
        is BookMainAction.Done -> {
            Text(if (action.completedAt == null) stringResource(StringRes.catalogue_in_library) else stringResource(StringRes.catalogue_in_library_downloaded, timeAgoText(catalogueTimeAgo(action.completedAt, now))),
                style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold), color = Ember.colors.success, modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            val readLabel = stringResource(StringRes.catalogue_a11y_read, state.book?.displayTitle().orEmpty())
            BookButton(stringResource(StringRes.catalogue_read_now), actions.onRead, modifier = Modifier.semantics { contentDescription = readLabel })
        }
        is BookMainAction.Failed -> ActionCard {
            val row = action.acquisition
            val unknown = stringResource(StringRes.catalogue_file_size_unknown)
            val reason = row.state.failureReason
            val text = when (reason) {
                AcquisitionFailureReason.Connection -> stringResource(StringRes.catalogue_failure_connection)
                AcquisitionFailureReason.TooLarge -> stringResource(StringRes.catalogue_failure_too_large, row.expectedSizeBytes?.let(::catalogueMegabytes) ?: unknown, catalogueMegabytes(CatalogueAcquisitionLimits.MAX_FILE_BYTES))
                AcquisitionFailureReason.Storage -> stringResource(StringRes.catalogue_failure_storage, com.retro99.catalogue.ui.add.catalogueDeviceName(), row.neededBytes?.let(::catalogueMegabytes) ?: unknown)
                AcquisitionFailureReason.Invalid -> stringResource(StringRes.catalogue_failure_invalid)
                AcquisitionFailureReason.Protected -> stringResource(StringRes.catalogue_failure_protected)
                AcquisitionFailureReason.Refused -> stringResource(StringRes.catalogue_failure_refused, state.catalogueName)
                AcquisitionFailureReason.SignIn -> stringResource(StringRes.catalogue_failure_sign_in, state.catalogueName)
                null -> stringResource(StringRes.catalogue_failure_interrupted)
            }
            Text(text, style = Ember.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), color = Ember.colors.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val label = stringResource(when {
                row.state == AcquisitionState.Interrupted -> StringRes.catalogue_start_again
                reason == AcquisitionFailureReason.SignIn -> StringRes.catalogue_sign_in
                reason in listOf(AcquisitionFailureReason.TooLarge, AcquisitionFailureReason.Invalid, AcquisitionFailureReason.Protected) -> StringRes.catalogue_dismiss
                else -> StringRes.catalogue_retry
            })
            BookButton(label, actions.onFailureAction, modifier = Modifier.padding(top = 10.dp))
        }
        is BookMainAction.Blocked -> ActionCard(note = true) {
            val blocked = action.blocked
            val provider = blocked.provider ?: state.catalogueName
            CardTitle(stringResource(when (blocked.reason) {
                com.retro99.server.api.CatalogueUnavailableReason.SampleOnly -> StringRes.catalogue_blocked_sample_title
                com.retro99.server.api.CatalogueUnavailableReason.Protected, com.retro99.server.api.CatalogueUnavailableReason.UnsupportedFormat -> StringRes.catalogue_cant_open_in_parrot
                else -> StringRes.catalogue_blocked_title
            }))
            val format = when (blocked.format?.lowercase()) {
                "application/pdf" -> stringResource(StringRes.catalogue_format_pdf)
                "application/x-mobipocket-ebook" -> stringResource(StringRes.catalogue_format_mobi)
                "application/vnd.amazon.ebook", "application/x-azw3" -> stringResource(StringRes.catalogue_format_azw3)
                else -> blocked.format?.takeIf { it.startsWith("audio/") }?.let { stringResource(StringRes.catalogue_format_audiobook) } ?: blocked.format
            }
            val body = when (blocked.reason) {
                com.retro99.server.api.CatalogueUnavailableReason.Sold -> stringResource(StringRes.catalogue_blocked_sold, provider)
                com.retro99.server.api.CatalogueUnavailableReason.Subscription -> stringResource(StringRes.catalogue_blocked_subscription, provider)
                com.retro99.server.api.CatalogueUnavailableReason.Borrow -> stringResource(StringRes.catalogue_blocked_borrow, provider)
                com.retro99.server.api.CatalogueUnavailableReason.SampleOnly -> stringResource(StringRes.catalogue_blocked_sample_body)
                com.retro99.server.api.CatalogueUnavailableReason.Protected -> stringResource(StringRes.catalogue_blocked_protected)
                com.retro99.server.api.CatalogueUnavailableReason.UnsupportedFormat -> if (format == null) stringResource(StringRes.catalogue_blocked_format_unknown) else stringResource(StringRes.catalogue_blocked_format, format)
            }
            CardBody(body, Modifier.padding(top = 8.dp))
            blocked.providerLink?.let { link ->
                val label = stringResource(StringRes.catalogue_a11y_open_provider_page, provider)
                BookButton(stringResource(StringRes.catalogue_open_provider_page), { actions.onProvider(link) }, modifier = Modifier.padding(top = 14.dp).semantics { contentDescription = label })
            }
        }
        null -> Unit
    }
}

@Composable
private fun ActionCard(note: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().background(if (note) Ember.colors.surfaceSelected else Ember.colors.surface, shape)
        .border(if (Ember.style.isEink) 2.dp else 1.dp, if (note && !Ember.style.isEink) Ember.colors.navActive else Ember.colors.line, shape)
        .padding(16.dp), content = content)
}
@Composable private fun CardTitle(text: String) = Text(text, style = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold), color = Ember.colors.ink, modifier = Modifier.semantics { heading() })
@Composable private fun CardBody(text: String, modifier: Modifier = Modifier) = Text(text, style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp), color = Ember.colors.ink2, modifier = modifier)
@Composable private fun CancelButton(onClick: () -> Unit) {
    val label = stringResource(StringRes.catalogue_a11y_cancel_download)
    OutlinedButton(onClick = onClick, shape = CircleShape, border = BorderStroke(if (Ember.style.isEink) 2.dp else 1.dp, Ember.colors.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ember.colors.ink), modifier = Modifier.heightIn(min = 42.dp).semantics { contentDescription = label }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Text(stringResource(StringRes.catalogue_cancel), style = Ember.type.meta.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
internal fun BookButton(label: String, onClick: () -> Unit, icon: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ember.colors.accent, contentColor = Ember.colors.onAccent), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        if (icon) { Icon(Icons.Outlined.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(label, style = Ember.type.meta.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold))
    }
}

expect object CatalogueDownloadPlatform { val isIos: Boolean }
