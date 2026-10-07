package com.retro99.parrot.fixtures

import androidx.compose.runtime.*
import com.retro99.base.ui.IntentDispatcher
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.*
import com.retro99.books.ui.positions.*
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.positions.*
import com.retro99.reader.domain.translate.*
import com.retro99.reader.domain.usecase.ApplyResult
import com.retro99.reader.domain.write.CopyWriteResult
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.TextAnchor
import com.retro99.sync.domain.ProgressKind
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.days

/** No account, database, sync or network: production renderer with deterministic seeded state. */
@Composable
fun PositionsFixture(scenario: String) {
    var state by remember { mutableStateOf(seededPositions(scenario)) }
    PositionsScreenContent(state, IntentDispatcher { event ->
        when (event) {
            is PositionsIntent.OnRowClicked -> if (state.rows.any { it.candidateId == event.copyKey && it.position != null })
                state = state.copy(selectedKey = event.copyKey)
            PositionsIntent.OnUseThisPositionClicked -> state = state.copy(previews = fixturePreviews().filter { preview ->
                state.rows.any { it.copy.key == preview.target.key }
            },
                sheetSource = state.rows.first { it.candidateId == state.selectedKey },
                checkedKeys = fixturePreviews().filter { it.defaultChecked }.map { it.target.key.value }.toSet())
            is PositionsIntent.OnTargetToggled -> if (!state.isApplying && state.previews.orEmpty().any { it.enabled && it.target.key.value == event.copyKey })
                state = state.copy(checkedKeys = if (event.copyKey in state.checkedKeys) state.checkedKeys - event.copyKey else state.checkedKeys + event.copyKey)
            PositionsIntent.OnSheetDismissed -> if (!state.isApplying) state = state.copy(previews = null, sheetSource = null, checkedKeys = emptySet())
            PositionsIntent.OnRefresh, PositionsIntent.OnResume -> state = seededPositions("normal")
            PositionsIntent.OnApplyClicked -> {
                val results = state.previews.orEmpty().filter { it.target.key.value in state.checkedKeys }
                    .map { ApplyResult(it.target, CopyWriteResult.Written) }
                state = state.copy(previews = null, sheetSource = null, checkedKeys = emptySet(), notice = positionApplyNotice(results))
            }
            PositionsIntent.OnRetryApplyClicked -> state = state.copy(notice = PositionsNotice(1, emptyList()))
            PositionsIntent.OnNoticeDismissed -> state = state.copy(notice = null)
            PositionsIntent.OnFailureDetailsClicked -> state = state.copy(showFailureDetails = true)
            PositionsIntent.OnFailureDetailsDismissed -> state = state.copy(showFailureDetails = false)
            is PositionsIntent.OnOpenVersion -> state = state.copy(notice = PositionsNotice(0, emptyList()))
            PositionsIntent.OnBackClicked -> Unit
        }
    })
}

private fun version(id: String, home: BookHome, audio: Boolean = false, readalong: Boolean = false,
    server: String = id): LinkedCopy = LinkedCopy(
    CopyKey(when (home) {
        BookHome.ThisDevice, BookHome.ParrotCloud -> CopySource.Library
        BookHome.Storyteller -> CopySource.Storyteller
        BookHome.Audiobookshelf -> CopySource.Audiobookshelf
    }, id), server, id, "The Lantern Ferry", home, !audio, audio, readalong, true,
)

private fun place(copy: LinkedCopy, percent: Double = .62, chapter: Int? = 7, audioMs: Long? = null,
    recent: Boolean = true): PositionDomainModel = PositionDomainModel(
    copy.uuid, copy.serverId, null, null, null, if (audioMs == null && chapter != null) "chapter.xhtml" else null,
    null, chapter?.let { "Chapter ${it + 1}" }, null, audioMs, chapter, null, null, null, percent, null,
    observedAt = (Clock.System.now() - if (recent) 5.minutes else 1.days).toString(), bookTimeMs = audioMs,
    deviceName = if (recent) "Samsung phone" else "Pixel Tablet",
)

private fun positionRow(copy: LinkedCopy, position: PositionDomainModel?, latest: Boolean = false,
    stale: Boolean = false, local: Boolean = true, conflict: Boolean = false, quote: Boolean = false): CopyPositionRow =
    CopyPositionRow(copy, position, position?.observedAt, position?.origin,
        if (local) PositionSource.ThisDevice else PositionSource.Server, latest, stale,
        if (quote) TextAnchor("“It was not mine", "to open,” he said at last.") else null,
        candidateId = copy.key.value + if (conflict) if (local) "|local" else "|server" else "",
        isConflict = conflict, isLocalCandidate = local)

fun seededPositions(scenario: String): PositionsViewState {
    val local = version("local", BookHome.ThisDevice)
    val st = version("story", BookHome.Storyteller, audio = true, server = "st-home")
    val abs = version("abs", BookHome.Audiobookshelf, audio = true)
    val cloud = version("cloud", BookHome.ParrotCloud)
    val empty = version("empty", BookHome.Audiobookshelf, audio = true, server = "abs")
    val second = version("story-work", BookHome.Storyteller, audio = true, server = "st-work")
    val normal = listOf(positionRow(local, place(local), latest = true, quote = true),
        positionRow(st, place(st, .43, 6, 15_128_000, false), local = false),
        positionRow(abs, place(abs, .12, null, recent = false).copy(origin = PositionOrigin.Remote), stale = true, local = false))
    val pair = listOf(positionRow(cloud, place(cloud), latest = true, conflict = true),
        positionRow(cloud, place(cloud, .48, 6, recent = false), local = false, conflict = true),
        positionRow(st, place(st, .43, 6, 15_128_000, false)), positionRow(empty, null))
    val rows = when (scenario) {
        "pair" -> pair
        "full" -> normal + pair.filter { it.copy.key != st.key }.map { it.copy(isLatest = false) } +
            positionRow(second, place(second, .25, 3, 6_000_000, false), local = false)
        else -> normal + positionRow(version("readalong", BookHome.ParrotCloud, readalong = true), null)
    }.let { rows ->
        if (scenario in listOf("apply-all", "apply-start"))
            rows + positionRow(version("collapse", BookHome.Storyteller, server = "st-work"), null)
        else rows
    }
    val selected = if (scenario == "pair") rows[2] else rows[0]
    val state = PositionsViewState(bookTitle = "The Lantern Ferry", deviceName = "This phone",
        serverNames = mapOf("st-home" to "Storyteller (home)", "st-work" to "Storyteller (work)", "abs" to "Audiobookshelf"),
        isLoading = scenario == "loading", loadError = scenario == "error", isUnlinked = scenario == "unlinked",
        rows = rows, selectedKey = selected.candidateId)
    val previews = fixturePreviews()
    return when (scenario) {
        "apply", "updating", "apply-all", "apply-no-match", "apply-not-supported", "apply-start" -> state.copy(previews = when (scenario) {
            "apply-all" -> previews
            "apply-start" -> previews.map { if (it.warning != null) it.copy(warning = ApplyWarning.CollapseToStart) else it }
            "apply-no-match" -> previews.filter { it.warning == null }.map {
                if (!it.enabled) it.copy(disabledReason = ApplyDisabledReason.NoTranslation(TranslationFailure.NoMatch)) else it
            }
            "apply-not-supported" -> previews.filter { it.warning == null }.map {
                if (!it.enabled) it.copy(disabledReason = ApplyDisabledReason.NotSupported) else it
            }
            else -> previews.filter { it.warning == null }
        },
            sheetSource = selected, checkedKeys = previews.filter { it.defaultChecked }.map { it.target.key.value }.toSet(),
            isApplying = scenario == "updating")
        "success" -> state.copy(notice = PositionsNotice(2, emptyList()))
        "partial" -> state.copy(notice = PositionsNotice(1, listOf(ApplyResult(abs, CopyWriteResult.Refused(CopyWriteResult.Reason.NotSupported)))))
        "none" -> state.copy(notice = PositionsNotice(0, listOf(ApplyResult(abs, CopyWriteResult.Refused(CopyWriteResult.Reason.NotSupported)))))
        "details" -> state.copy(showFailureDetails = true, notice = PositionsNotice(1,
            listOf(ApplyResult(abs, CopyWriteResult.Refused(CopyWriteResult.Reason.NotSupported)))))
        "no-selection" -> state.copy(selectedKey = null)
        else -> state
    }
}

private fun fixturePreviews(): List<ApplyPreview> {
    val st = version("story", BookHome.Storyteller, audio = true, server = "st-home")
    val abs = version("abs", BookHome.Audiobookshelf, audio = true)
    val cloud = version("readalong", BookHome.ParrotCloud, readalong = true)
    val collapse = version("collapse", BookHome.Storyteller, server = "st-work")
    fun translated(copy: LinkedCopy, percent: Double, confidence: TranslationConfidence, audio: Long? = null) =
        TranslatedPosition(copy.key, place(copy, percent, chapter = null, audioMs = audio),
            if (copy.hasAudiobook) ProgressKind.AUDIO else ProgressKind.EBOOK, confidence, TranslationStrategy.Proportional)
    return listOf(
        ApplyPreview(st, translated(st, .62, TranslationConfidence.High, 21_520_000), true, true, null, null),
        ApplyPreview(abs, translated(abs, .61, TranslationConfidence.Approximate), false, true, null, null),
        ApplyPreview(collapse, translated(collapse, 1.0, TranslationConfidence.High), false, true, ApplyWarning.CollapseToEnd, null),
        ApplyPreview(cloud, null, false, false, null, ApplyDisabledReason.NoTranslation(TranslationFailure.MissingFile(cloud.key))),
    )
}
