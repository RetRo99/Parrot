package com.retro99.reader.ui.recap

import androidx.lifecycle.viewModelScope
import com.retro99.base.ui.BaseIntent
import com.retro99.base.ui.BaseViewModel
import com.retro99.reader.domain.recap.RecapBannerDismissals
import com.retro99.reader.domain.recap.RecapRepository
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.SessionRecap
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
) {
    override fun toString(): String =
        "ReaderRecapBanner(sessionId=$sessionId, chars=${summary.length}, latest=$isLatestSession)"
}

data class ReaderRecapViewState(
    val banner: ReaderRecapBanner? = null,
    val isExpanded: Boolean = false,
)

sealed interface ReaderRecapIntent : BaseIntent {
    data object ToggleExpanded : ReaderRecapIntent
    data object Dismiss : ReaderRecapIntent
}

/**
 * Only SUCCEEDED recaps are offered; pending and failed ones stay out of
 * the reader. [history] is newest first and holds ended sessions only.
 */
fun readerRecapBanner(history: List<SessionRecap>, cloudRecapsEnabled: Boolean): ReaderRecapBanner? {
    if (!cloudRecapsEnabled) return null
    val index = history.indexOfFirst { recap ->
        recap.status == RecapStatus.SUCCEEDED && !recap.summary.isNullOrBlank()
    }
    if (index < 0) return null
    val recap = history[index]
    return ReaderRecapBanner(recap.sessionId, recap.summary.orEmpty(), isLatestSession = index == 0)
}

/** Reads stored recaps only. Dismissing hides the banner and nothing else. */
@OptIn(ExperimentalCoroutinesApi::class)
@KoinViewModel
class ReaderRecapViewModel(
    @InjectedParam private val bookUuid: String,
    @Provided private val recapRepository: RecapRepository,
    @Provided private val recapSettings: RecapSettings,
    @Provided private val dismissals: RecapBannerDismissals,
) : BaseViewModel<ReaderRecapViewState, ReaderRecapIntent>(ReaderRecapViewState()) {

    private val dismissedNow = MutableStateFlow(emptySet<String>())

    init {
        combine(
            recapRepository.observeHistory(bookUuid),
            recapSettings.observeCloudRecapsEnabled(),
            dismissedNow,
        ) { history, enabled, dismissed ->
            readerRecapBanner(history, enabled)?.takeIf { it.sessionId !in dismissed }
        }
            .mapLatest { banner -> banner?.takeIf { !dismissals.isDismissed(it.sessionId) } }
            .distinctUntilChanged()
            // A missing banner is harmless; never break the reader for it.
            .catch { emit(null) }
            .onEach { banner ->
                updateState { state ->
                    val sameSession = state.banner?.sessionId == banner?.sessionId
                    state.copy(banner = banner, isExpanded = state.isExpanded && sameSession)
                }
            }
            .launchIn(viewModelScope)
    }

    override fun onIntent(intent: ReaderRecapIntent) {
        when (intent) {
            ReaderRecapIntent.ToggleExpanded -> updateState { it.copy(isExpanded = !it.isExpanded) }
            ReaderRecapIntent.Dismiss -> dismiss()
        }
    }

    private fun dismiss() {
        val sessionId = viewState.value.banner?.sessionId ?: return
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
