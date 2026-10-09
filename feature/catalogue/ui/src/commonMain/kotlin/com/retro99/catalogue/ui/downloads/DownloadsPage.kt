package com.retro99.catalogue.ui.downloads

import com.github.michaelbull.result.fold
import com.retro99.base.nowMillis
import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.browse.*
import com.retro99.server.api.OpdsAccountDetails
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DownloadsState(
    val rows: List<DownloadRow> = emptyList(),
    val signInSourceId: String? = null,
    val signInCatalogue: String = "",
    val signIn: CatalogueSignInState? = null,
    val openBookId: String? = null,
    val closed: Boolean = false,
)

/** Screen-owned queue projection. Leaving never cancels an unfinished request. */
class DownloadsPage(
    private val queue: CatalogueAcquisitionManager,
    private val gateway: CatalogueBrowseGateway,
    profiles: Flow<String?>,
    private val scope: CoroutineScope,
    private val now: () -> Long = ::nowMillis,
) {
    private val mutable = MutableStateFlow(DownloadsState())
    val state = mutable.asStateFlow()
    private var profile: String? = null
    private var active = true
    private var left = false
    private var expiry: Job? = null
    private var accountJob: Job? = null
    private var sourceJob: Job? = null
    private val jobs = mutableListOf<Job>()

    init {
        jobs += scope.launch { profiles.collect { next ->
            if (!active) return@collect
            if (next == null || (profile != null && next != profile)) {
                active = false; accountJob?.cancel(); sourceJob?.cancel(); expiry?.cancel()
                mutable.value = DownloadsState(closed = true)
            } else profile = next
        } }
        jobs += scope.launch {
            queue.purgeExpired()
            queue.observeAcquisitions().collect { rows ->
                if (!active) return@collect
                mutable.value = mutable.value.copy(rows = downloadsRows(rows))
                expiry?.cancel()
                val deadline = rows.filter { it.state == AcquisitionState.Done }.mapNotNull { it.completedAt }
                    .minOrNull()?.plus(CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS)
                if (deadline != null) expiry = scope.launch { delay((deadline - now()).coerceAtLeast(1)); if (active) queue.purgeExpired() }
            }
        }
    }

    fun action(requestId: String) {
        if (!active) return
        val row = state.value.rows.firstOrNull { it.acquisition.requestId == requestId } ?: return
        when (row.action) {
            DownloadAction.Open -> {
                val book = row.acquisition.libraryBookId ?: return
                leave()
                mutable.value = mutable.value.copy(openBookId = book)
            }
            DownloadAction.SignIn -> {
                dismissSignIn()
                mutable.value = mutable.value.copy(signInSourceId = row.acquisition.sourceId, signInCatalogue = row.acquisition.catalogueName, signIn = CatalogueSignInState())
                sourceJob = scope.launch {
                    gateway.observeSource(row.acquisition.sourceId).collect { source ->
                        if (source == null || source.profileId != profile) dismissSignIn()
                    }
                }
            }
            null -> Unit
            else -> jobs += scope.launch {
                when (row.action) {
                    DownloadAction.Cancel -> queue.cancel(requestId)
                    DownloadAction.Retry -> queue.retry(requestId)
                    DownloadAction.Dismiss -> queue.dismiss(requestId)
                    DownloadAction.StartAgain -> queue.startAgain(requestId)
                    else -> Unit
                }
            }
        }
    }

    fun signIn(username: String, password: String) {
        val sourceId = state.value.signInSourceId ?: return
        if (!active || state.value.signIn?.working == true || username.trim().isEmpty()) return
        mutable.value = mutable.value.copy(signIn = CatalogueSignInState(working = true))
        accountJob = scope.launch {
            val account = OpdsAccountDetails(username.trim(), password)
            try {
                val result = gateway.checkAccount(sourceId, null, account)
                currentCoroutineContext().ensureActive()
                if (!active || state.value.signInSourceId != sourceId) return@launch
                result.fold(success = {
                    gateway.saveAccount(sourceId, account)
                    currentCoroutineContext().ensureActive()
                    if (!active) return@fold
                    queue.signedIn(sourceId)
                    dismissSignIn()
                }, failure = { mutable.value = mutable.value.copy(signIn = CatalogueSignInState(wrongDetails = it.toLoadProblem() == CatalogueLoadProblem.SignInNeeded)) })
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (active) mutable.value = mutable.value.copy(signIn = CatalogueSignInState()) }
        }
    }

    fun dismissSignIn() {
        accountJob?.cancel(); sourceJob?.cancel()
        mutable.value = mutable.value.copy(signInSourceId = null, signIn = null)
    }
    fun navigationHandled() { mutable.value = mutable.value.copy(openBookId = null) }
    fun enter() { left = false }

    fun leave() {
        if (!active || left || profile == null) return
        left = true
        // Enter the queue's profile-fenced database operation before the owning VM is cleared.
        scope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) { queue.purgeFinished() }
    }
    fun cancel() { active = false; expiry?.cancel(); accountJob?.cancel(); sourceJob?.cancel(); jobs.forEach { it.cancel() } }
}
