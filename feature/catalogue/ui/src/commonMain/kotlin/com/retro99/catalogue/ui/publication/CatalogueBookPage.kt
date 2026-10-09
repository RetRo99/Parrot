package com.retro99.catalogue.ui.publication

import com.github.michaelbull.result.fold
import com.github.michaelbull.result.Err
import com.retro99.base.result.AppError
import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.browse.*
import com.retro99.catalogue.ui.navigation.CatalogueBookPlace
import com.retro99.server.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Screen-owned state, never the owner of a durable download. All callbacks are profile fenced. */
class CatalogueBookPage(
    private val sourceId: String,
    private val place: CatalogueBookPlace?,
    private val gateway: CatalogueBrowseGateway,
    private val library: CatalogueLibraryLookup,
    private val queue: CatalogueAcquisitionManager,
    private val scope: CoroutineScope,
    private val onCancelled: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(CatalogueBookState())
    val state = _state.asStateFlow()
    private var source: CatalogueBrowseSource? = null
    private var publications = place?.publications.orEmpty()
    private var document: CatalogueDocument? = null
    private var repository: ServerCatalogueRepository? = null
    private var rows = emptyList<CatalogueAcquisition>()
    private var libraryBooks = emptyMap<CatalogueEntryIdentity, CatalogueLibraryBook>()
    private var active = true
    private var started = false
    private val jobs = mutableListOf<Job>()
    private var requestJob: Job? = null
    private var libraryJob: Job? = null
    private var loading = true
    private var target = place?.listing
    private val identities get() = ((publications + place?.publications.orEmpty()).map { CatalogueEntryIdentity(it.publicationKey) } + listOfNotNull(place?.listingIdentity?.let(::CatalogueEntryIdentity))).distinct()
    val announcementKeys get() = identities.map { it.publicationKey }
    private val acquisition get() = rows.lastOrNull { it.sourceId == sourceId && (it.publicationKey in identities.map { id -> id.publicationKey } || it.detailIdentity in identities.map { id -> id.publicationKey }) }

    init {
        if (place == null) _state.value = CatalogueBookState(navigation = BookNavigation.CatalogueRoot)
        else {
            jobs += scope.launch { gateway.observeSource(sourceId).collect { next ->
                if (!active) return@collect
                if (next == null || source?.let { it.profileId != next.profileId || it.address != next.address } == true) {
                    this@CatalogueBookPage.cancel(); _state.value = CatalogueBookState(closed = true); return@collect
                }
                source = next
                publish()
                if (!started) { started = true; load() }
            } }
            jobs += scope.launch { queue.observeAcquisitions().collect { next ->
                if (!active) return@collect
                rows = next
                if (acquisition?.state?.failureReason == AcquisitionFailureReason.SignIn && _state.value.signIn == null) {
                    _state.value = _state.value.copy(signIn = CatalogueSignInState())
                }
                publish()
                onReturn()
            } }
        }
    }

    private fun load() {
        requestJob?.cancel()
        loading = true
        _state.value = _state.value.copy(loadFailed = false, loadContent = null)
        publish()
        requestJob = scope.launch {
            val answer = try {
                repository = gateway.repository(sourceId)
                val session = repository
                if (session == null) Err(AppError.ApiError(400, CatalogueErrorKind.Unreachable.name)) else {
                    var loaded = session.getDocument(target!!)
                    // OPDS partial entries advertise a full standalone entry as an alternate.
                    if (target == place!!.listing && publications.size == 1) {
                        val full = publications.single().links.firstOrNull { link ->
                            link.target != null && "alternate" in link.relations &&
                                (link.mediaType?.subtype == "opds-publication+json" || link.mediaType?.parameters?.get("type") == "entry")
                        }?.target
                        if (loaded.isOk && full != null) { target = full; loaded = session.getDocument(full) }
                    }
                    loaded
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { Err(AppError.ApiError(400, CatalogueErrorKind.Unreachable.name)) }
            currentCoroutineContext().ensureActive()
            if (!active) return@launch
            loading = false
            answer.fold(success = { accept(it) }, failure = { error ->
                val problem = error.toLoadProblem()
                val content = when (problem) {
                    CatalogueLoadProblem.SignInNeeded -> CatalogueBrowseContent.SignInNeeded
                    CatalogueLoadProblem.Offline -> CatalogueBrowseContent.OfflineNone
                    CatalogueLoadProblem.RateLimited -> CatalogueBrowseContent.RateLimited
                    is CatalogueLoadProblem.Failed -> CatalogueBrowseContent.Failed(problem.reason)
                }
                _state.value = _state.value.copy(loadFailed = true, loadContent = content, signIn = if (problem == CatalogueLoadProblem.SignInNeeded) CatalogueSignInState() else null)
                publish()
            })
            refreshLibrary()
        }
    }

    private fun accept(loaded: CatalogueDocument) {
        document = loaded
        val available = when (loaded) {
            is CataloguePublicationDocument -> listOf(loaded.publication)
            is CatalogueFeedDocument -> loaded.publications + loaded.groups.flatMap { it.publications }
        }
        val matched = available.filter { it.publicationKey in place!!.publications.map { p -> p.publicationKey } }
        if (matched.isNotEmpty()) publications = matched
        else if (loaded is CataloguePublicationDocument && publications.size == 1) publications = available
        else { _state.value = _state.value.copy(loadFailed = true, loadContent = CatalogueBrowseContent.Failed(CataloguePageFailure.NotFound)); publish(); return }
        _state.value = _state.value.copy(loadFailed = false, loadContent = null)
        publish()
    }

    /** Called on every return to this route; lookups are not kept across reader/library visits. */
    fun onReturn() {
        if (active && place != null) {
            libraryJob?.cancel()
            libraryJob = scope.launch { refreshLibrary() }
        }
    }

    fun retryLoad() { if (active && place != null) load() }

    private suspend fun refreshLibrary() {
        val found = try { library.libraryDetailsFor(sourceId, identities) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyMap() }
        currentCoroutineContext().ensureActive()
        if (!active) return
        libraryBooks = found
        publish()
    }

    fun openFiles() { if (active && _state.value.files.size > 1) _state.value = _state.value.copy(chooseFile = true) }
    fun closeFiles() { _state.value = _state.value.copy(chooseFile = false) }
    fun chooseFile(ordinal: Int) {
        if (active && _state.value.files.any { it.ordinal == ordinal && it.openable }) { _state.value = _state.value.copy(selectedOrdinal = ordinal); publish() }
    }

    fun download() {
        if (!active || _state.value.action !is BookMainAction.Download || requestJob?.isActive == true) return
        val file = _state.value.selectedFile ?: return
        val doc = document ?: return
        val locator = (repository as? CatalogueAcquisitionRepository)?.locate(doc, file.publication, file.choice)
            ?: return
        _state.value = _state.value.copy(chooseFile = false)
        requestJob = scope.launch {
              val result = queue.request(CatalogueAcquisitionRequest(sourceId, locator.publicationKey, locator.representationKey, (place?.listingIdentity ?: place?.publications?.singleOrNull()?.publicationKey)?.takeIf { it != locator.publicationKey }, locator.documentUrl,
                file.publication.displayTitle(), file.publication.displayAuthor(), file.publication.images.firstOrNull()?.href, source?.name.orEmpty(), file.size,
                file.publication.rights.display(), file.publication.updated))
            if (!active) return@launch
            when (result) {
                is CatalogueRequestOutcome.Queued -> _state.value = _state.value.copy(downloadNotice = true)
                is CatalogueRequestOutcome.InLibrary -> { refreshLibrary() }
            }
            publish()
        }
    }

    fun cancelDownload() {
        val row = acquisition ?: return
        if (!active || row.state !in listOf(AcquisitionState.Waiting, AcquisitionState.Downloading)) return
        jobs += scope.launch { if (queue.cancel(row.requestId)) onCancelled(row.title) }
    }
    fun failureAction() {
        val row = acquisition ?: return
        if (!active) return
        if (row.state.failureReason == AcquisitionFailureReason.SignIn) {
            _state.value = _state.value.copy(signIn = CatalogueSignInState()); return
        }
        jobs += scope.launch {
            when {
                row.state == AcquisitionState.Interrupted -> queue.startAgain(row.requestId)
                row.state.failureReason in listOf(AcquisitionFailureReason.TooLarge, AcquisitionFailureReason.Invalid, AcquisitionFailureReason.Protected) -> queue.dismiss(row.requestId)
                row.state is AcquisitionState.Failed -> queue.retry(row.requestId)
            }
        }
    }

    fun signIn(username: String, password: String) {
        if (!active || _state.value.signIn == null || _state.value.signIn?.working == true || username.trim().isEmpty()) return
        _state.value = _state.value.copy(signIn = CatalogueSignInState(working = true))
        requestJob = scope.launch {
            val account = OpdsAccountDetails(username.trim(), password)
            val result = try { gateway.checkAccount(sourceId, target, account) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Err(AppError.ApiError(400, CatalogueErrorKind.Unreachable.name)) }
            currentCoroutineContext().ensureActive()
            if (!active) return@launch
            result.fold(success = {
                gateway.saveAccount(sourceId, account)
                if (!active) return@fold
                repository = gateway.repository(sourceId)
                _state.value = _state.value.copy(signIn = null)
                queue.signedIn(sourceId)
                accept(it)
                // The verified page belonged to an ephemeral session. Fetch it in the saved
                // session before locating a new file; the verifier's places cannot locate it.
                load()
            }, failure = { _state.value = _state.value.copy(signIn = CatalogueSignInState(wrongDetails = it.toLoadProblem() == CatalogueLoadProblem.SignInNeeded)) })
        }
    }
    fun dismissSignIn() { requestJob?.cancel(); _state.value = _state.value.copy(signIn = null) }
    fun noticeHandled() { _state.value = _state.value.copy(downloadNotice = false) }
    fun navigationHandled() { _state.value = _state.value.copy(navigation = null) }
    fun readNow() {
        val done = _state.value.action as? BookMainAction.Done ?: return
        _state.value = _state.value.copy(navigation = BookNavigation.Read(done.libraryBookId))
    }
    fun cancel() { active = false; requestJob?.cancel(); libraryJob?.cancel(); jobs.forEach { it.cancel() } }

    private fun publish() {
        if (!active) return
        val groups = bookFileGroups(publications)
        val selected = groups.flatMap { it.files }.firstOrNull { it.ordinal == _state.value.selectedOrdinal && it.openable }
            ?: groups.flatMap { it.files }.firstOrNull { it.best }
        val row = acquisition
        val local = libraryBooks.values.firstOrNull()
        val action = when {
            local != null -> BookMainAction.Done(local.libraryBookId, local.acquiredAt ?: row?.completedAt)
            row?.state == AcquisitionState.Done && row.libraryBookId != null -> BookMainAction.Done(row.libraryBookId!!, row.completedAt)
            row?.state == AcquisitionState.Waiting -> BookMainAction.Waiting(rows.count { it.requestId != row.requestId && it.state == AcquisitionState.Downloading })
            row?.state == AcquisitionState.Downloading -> BookMainAction.Downloading(row.bytesSoFar, row.expectedSizeBytes?.takeIf { it > 0 })
            row?.state == AcquisitionState.Checking || row?.state == AcquisitionState.Adding -> BookMainAction.Adding
            row?.state is AcquisitionState.Failed || row?.state == AcquisitionState.Interrupted -> BookMainAction.Failed(row)
            loading || _state.value.loadFailed -> null
            selected != null -> BookMainAction.Download(selected)
            else -> BookMainAction.Blocked(bookBlocked(publications)!!)
        }
        _state.value = _state.value.copy(catalogueName = source?.name.orEmpty(), book = publications.firstOrNull(), groups = groups, selectedOrdinal = selected?.ordinal, action = action)
    }
}
