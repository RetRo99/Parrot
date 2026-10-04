package com.retro99.reader.ui.recap

import androidx.lifecycle.viewModelScope
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.FeatureUsageAnalyticsEvent
import com.retro99.analytics.api.UsageAction
import com.retro99.analytics.api.UsageFeature
import com.retro99.analytics.api.ProductAnalyticsEvent
import com.retro99.analytics.api.logFeatureUsage
import com.retro99.base.ui.BaseIntent
import com.retro99.base.ui.BaseViewModel
import com.retro99.reader.domain.recap.RecapBannerDismissals
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap
import com.retro99.reader.domain.recap.RecapPresentation
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided

/** The recap the reader offers when a book is reopened. */
data class ReaderRecapBanner(
    val sessionId: String,
    val summary: String,
    /** False when newer sessions exist but have no recap (yet). */
    val isLatestSession: Boolean,
    val recap: SessionRecap? = null,
    val isWriting: Boolean = false,
) {
    override fun toString(): String =
        "ReaderRecapBanner(sessionId=$sessionId, chars=${summary.length}, latest=$isLatestSession)"
}

data class ReaderRecapViewState(
    val banner: ReaderRecapBanner? = null,
    val sheetOpen: Boolean = false,
    val presentation: RecapPresentation? = null,
    val seen: Boolean = false,
)

sealed interface ReaderRecapIntent : BaseIntent {
    data object Visible : ReaderRecapIntent
    data object Dismiss : ReaderRecapIntent
    data object Open : ReaderRecapIntent
    data class OnEntry(val audioActive: Boolean) : ReaderRecapIntent
}

internal fun shouldAutoOpenRecap(presentation: RecapPresentation, endedAt: Long, openedAt: Long,
    seen: Boolean, audioActive: Boolean, writing: Boolean): Boolean =
    !seen && !audioActive && !writing && (presentation == RecapPresentation.EVERY_TIME ||
        presentation == RecapPresentation.AFTER_BREAK && openedAt - endedAt > 3_600_000)

/**
 * Ready and genuinely in-flight recaps have a manual entry. Failed/empty
 * results do not. [history] is newest first and holds ended sessions only.
 */
fun readerRecapBanner(history: List<SessionRecap>, cloudRecapsEnabled: Boolean,
    currentProgression: Double? = null, requirePosition: Boolean = false): ReaderRecapBanner? {
    if (!cloudRecapsEnabled) return null
    val index = history.indexOfFirst { recap ->
        (recap.status == RecapStatus.SUCCEEDED && !recap.summary.isNullOrBlank() ||
            recap.status in setOf(RecapStatus.PENDING, RecapStatus.RUNNING, RecapStatus.CLOUD_QUEUED, RecapStatus.CLOUD_RUNNING)) &&
            (!requirePosition || (currentProgression != null && recap.endPosition.totalProgression?.let { end ->
                currentProgression >= end && currentProgression <= end + 0.02
            } == true))
    }
    if (index < 0) return null
    val recap = history[index]
    return ReaderRecapBanner(recap.sessionId, recap.summary.orEmpty(), isLatestSession = index == 0,
        recap = recap, isWriting = recap.status != RecapStatus.SUCCEEDED)
}

/** Reads stored recaps only. Closing marks it seen without hiding the pill. */
@OptIn(ExperimentalCoroutinesApi::class)
@KoinViewModel
class ReaderRecapViewModel(
    @InjectedParam private val bookUuid: String,
    @Provided private val recapRepository: RecapRepository,
    @Provided private val recapSettings: RecapSettings,
    @Provided private val dismissals: RecapBannerDismissals,
    @Provided private val analytics: Analytics,
) : BaseViewModel<ReaderRecapViewState, ReaderRecapIntent>(ReaderRecapViewState()) {

    private val dismissedNow = MutableStateFlow(emptySet<String>())
    private var exposedSessionId: String? = null
    private var entryChecked = false
    private var entryHistoryChecked = false
    private var entryProgression: Double? = null
    private val openedAt = Clock.System.now().toEpochMilliseconds()

    init {
        // Entry is a one-shot opportunity, not an invitation to open a sheet
        // later when the reader navigates back into a matching range.
        recapRepository.observeProgression(bookUuid).onEach { progression ->
            if (progression != null) {
                if (entryProgression == null) entryProgression = progression
                else if (entryProgression != progression) entryChecked = true
            }
        }.catch { entryChecked = true }.launchIn(viewModelScope)
        combine(
            recapRepository.observeHistory(bookUuid).onEach { history ->
                if (!entryHistoryChecked) {
                    entryHistoryChecked = true
                    if (history.firstOrNull()?.status != RecapStatus.SUCCEEDED) entryChecked = true
                }
            },
            combine(recapSettings.observeCloudRecapsEnabled(), recapSettings.observeFeatureAvailable()) { enabled, allowed -> enabled && allowed },
            dismissedNow,
            recapRepository.observeProgression(bookUuid),
        ) { history, enabled, _, progression ->
            readerRecapBanner(history, enabled, progression, requirePosition = true)
        }
            .mapLatest { banner -> banner to (banner?.let { it.sessionId in dismissedNow.value || dismissals.isDismissed(it.sessionId) } ?: false) }
            .distinctUntilChanged()
            // A missing banner is harmless; never break the reader for it.
            .catch { emit(null to false) }
            .onEach { (banner, seen) ->
                updateState { state ->
                    val sameSession = state.banner?.sessionId == banner?.sessionId
                    state.copy(banner = banner, seen = seen, sheetOpen = state.sheetOpen && sameSession)
                }
            }
            .launchIn(viewModelScope)
        recapSettings.observePresentation().onEach { preference ->
            updateState { it.copy(presentation = preference) }
        }.launchIn(viewModelScope)
    }

    override fun onIntent(intent: ReaderRecapIntent) {
        when (intent) {
            ReaderRecapIntent.Visible -> {
                val banner = viewState.value.banner ?: return
                if (exposedSessionId != banner.sessionId) {
                    exposedSessionId = banner.sessionId
                    analytics.logFeatureUsage(ProductAnalyticsEvent.FeatureExposed(UsageFeature.Recaps, "reader", true))
                    analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.RecapInteraction(UsageAction.Shown, banner.isLatestSession))
                }
            }
            ReaderRecapIntent.Dismiss -> dismiss()
            ReaderRecapIntent.Open -> if (viewState.value.banner != null) {
                entryChecked = true
                updateState { it.copy(sheetOpen = true) }
            }
            is ReaderRecapIntent.OnEntry -> {
                val state = viewState.value
                val banner = state.banner ?: return
                if (state.presentation == null) return
                if (entryChecked) return
                entryChecked = true
                val ended = banner.recap?.endedAt ?: return
                if (banner.isLatestSession && shouldAutoOpenRecap(state.presentation, ended, openedAt, state.seen, intent.audioActive, banner.isWriting)) {
                    updateState { it.copy(sheetOpen = true) }
                }
            }
        }
    }

    private fun dismiss() {
        entryChecked = true
        val banner = viewState.value.banner ?: return
        val sessionId = banner.sessionId
        updateState { it.copy(sheetOpen = false) }
        analytics.logFeatureUsage(FeatureUsageAnalyticsEvent.RecapInteraction(UsageAction.Dismissed, banner.isLatestSession))
        dismissedNow.update { it + sessionId }
        viewModelScope.launch {
            try {
                dismissals.dismiss(sessionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Stays hidden for this visit; it may be offered again later.
            }
        }
    }
}
