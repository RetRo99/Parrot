package com.retro99.catalogue.ui.browse

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.fold
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.catalogue.domain.CatalogueEntryIdentity
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.downloads.ListDownloadState
import com.retro99.catalogue.ui.publication.bookFileGroups
import com.retro99.catalogue.ui.navigation.CatalogueBookPlace
import com.retro99.catalogue.ui.navigation.CataloguePlace
import com.retro99.server.api.CatalogueDocument
import com.retro99.server.api.CatalogueErrorKind
import com.retro99.server.api.CatalogueFacetGroup
import com.retro99.server.api.CatalogueFacetOption
import com.retro99.server.api.CatalogueFeedDocument
import com.retro99.server.api.CatalogueImageModel
import com.retro99.server.api.CatalogueLink
import com.retro99.server.api.CataloguePublication
import com.retro99.server.api.CataloguePublicationDocument
import com.retro99.server.api.CatalogueQuery
import com.retro99.server.api.CatalogueTarget
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.localNetworkHostLeaving
import com.retro99.server.api.publicationKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One page of a catalogue and everything the user can do on it: page through its books, search,
 * filter, open an entry. [start] null is the catalogue's first page.
 *
 * Every answer is checked against the list it was asked for before it is shown, and requests are
 * cancelled when their list is replaced: a search, filter, page or catalogue the user has
 * already left never changes the screen.
 *
 * Not thread safe: call it from the dispatcher of [scope].
 */
class CatalogueBrowser(
    private val sourceId: String,
    private val start: CataloguePlace?,
    private val gateway: CatalogueBrowseGateway,
    private val library: CatalogueLibraryLookup,
    private val scope: CoroutineScope,
    private val maxPages: Int = MAX_LOADED_PAGES,
    private val queue: CatalogueAcquisitionManager? = null,
    private val onCancelled: (String) -> Unit = {},
) {
    private sealed interface PageRequest {
        data object Root : PageRequest
        data class Target(val target: CatalogueTarget) : PageRequest
        data class Search(val query: String) : PageRequest
    }

    private class BookEntry(val key: String, val publication: CataloguePublication, val listing: CatalogueTarget, var inLibrary: Boolean, val linked: Boolean = false)
    private class FolderEntry(val key: String, val entry: CataloguePublication, val link: CatalogueLink)
    private class ShelfEntry(val key: String, val title: String, val seeAll: CatalogueLink?, val books: List<BookEntry>)

    /** What the first page of a list gives besides its books. */
    private class Header(
        val document: CatalogueFeedDocument,
        val shelves: List<ShelfEntry>,
        val folders: List<FolderEntry>,
        val sameBookCount: Int?,
    )

    private class Page(val number: Int, val request: PageRequest, val books: List<BookEntry>, val next: CatalogueLink?, val savedCopyAt: Long?)
    private enum class Load { Idle, Loading, Failed }

    private sealed interface Phase {
        data object FirstLoad : Phase
        data object Loaded : Phase
        data object Empty : Phase
        data class Problem(val problem: CatalogueLoadProblem) : Phase
    }

    /** A list of books: the page itself, the page under a filter, or the results of a search. */
    private class BookList(val id: Int, val first: PageRequest, val query: String?, val opening: Boolean) {
        /** Raised by every first load; an answer for an older one is dropped. */
        var generation = 0
        var phase: Phase = Phase.FirstLoad
        var header: Header? = null
        var pages: List<Page> = emptyList()

        /** The pages dropped from the front at the page limit, oldest first, to ask for again. */
        var earlier: List<PageRequest> = emptyList()
        var more = Load.Idle
        var earlierLoad = Load.Idle
        var afterSignIn = false

        /**
         * The addresses each page of this list was asked at and answered from, by page number.
         * A "next" link back to one of them, or to its own page, is not followed: the list ends.
         */
        val addresses = mutableMapOf<Int, Set<String>>()

        /** Next pages in a row that brought no books. Past the limit the next one waits for a tap. */
        var emptyRun = 0

        fun loopsBack(pageNumber: Int, next: CatalogueLink): Boolean {
            val address = next.resolvedHref ?: return false
            return addresses.any { (number, seen) -> number <= pageNumber && address in seen }
        }
        val jobs = mutableListOf<Job>()

        fun cancelRequests() {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }
    }

    private class FilterSheet(val groupIndex: Int, val searchText: String)
    private class LocalNetworkQuestion(val host: String, val open: () -> Unit, val dontOpen: () -> Unit)

    private var lists = 0
    private var base = BookList(lists++, start?.let { PageRequest.Target(it.target) } ?: PageRequest.Root, query = null, opening = start != null)
    private var search: BookList? = null
    private val current: BookList get() = search ?: base

    private var source: CatalogueBrowseSource? = null
    private var started = false
    private var closed = false
    private var cancelled = false
    private var autoLoad = true
    private var pageTitle: String? = start?.title
    private var searchText = ""
    private var filterSheet: FilterSheet? = null
    private var question: LocalNetworkQuestion? = null
    private val agreedHosts = mutableSetOf<String>().apply { start?.localNetworkHost?.let(::add) }
    private var signIn: CatalogueSignInState? = null
    private var navigation: CatalogueBrowseNavigation? = null
    private val sourceJob: Job
    private var queueJob: Job? = null
    private var acquisitions = emptyList<CatalogueAcquisition>()
    private val preparing = mutableMapOf<String, Job>()
    private var downloadNotice: String? = null

    private val _state = MutableStateFlow(CatalogueBrowseState(title = start?.title))
    val state: StateFlow<CatalogueBrowseState> = _state.asStateFlow()

    init {
        sourceJob = scope.launch { gateway.observeSource(sourceId).collect(::onSource) }
        queueJob = queue?.let { manager -> scope.launch { manager.observeAcquisitions().collect { rows ->
            if (!open) return@collect
            acquisitions = rows.filter { it.sourceId == sourceId }
            allEntries().forEach { entry -> if (acquisition(entry)?.state == AcquisitionState.Done) entry.inLibrary = true }
            publish()
            onReturn()
        } } }
    }

    // --- the catalogue ------------------------------------------------------------------

    private fun onSource(now: CatalogueBrowseSource?) {
        if (closed || cancelled) return
        val before = source
        // Another profile or another address: nothing that was loaded belongs on screen.
        if (now == null || before != null && (before.profileId != now.profileId || before.address != now.address)) return close()
        source = now
        publish()
        if (!started) {
            started = true
            loadFirst(base)
        }
    }

    /** The screen closes and shows nothing it had loaded. */
    private fun close() {
        closed = true
        stopRequests()
        base = BookList(lists++, base.first, query = null, opening = false)
        search = null
        searchText = ""
        filterSheet = null
        question = null
        signIn = null
        navigation = null
        publish()
    }

    /** The screen was left: every request stops and no answer is shown any more. */
    fun cancel() {
        cancelled = true
        sourceJob.cancel()
        queueJob?.cancel()
        stopRequests()
    }

    private fun stopRequests() {
        preparing.values.forEach { it.cancel() }
        preparing.clear()
        base.cancelRequests()
        search?.cancelRequests()
    }

    private val open: Boolean get() = !closed && !cancelled

    /** Still the list on screen (or under the search), and still the same first load of it. */
    private fun BookList.isCurrent(generation: Int) = open && (this === base || this === search) && this.generation == generation

    // --- loading ------------------------------------------------------------------------

    private suspend fun fetch(request: PageRequest): AppResult<CatalogueDocument> = try {
        val repository = gateway.repository(sourceId)
        when {
            repository == null -> Err(AppError.ApiError(400, CatalogueErrorKind.Unreachable.name))
            request is PageRequest.Target -> repository.getDocument(request.target)
            request is PageRequest.Search -> {
                val page = base.header?.document
                val offer = page?.let { repository.discoverSearch(it) }
                offer?.fold(
                    success = { found -> if (found == null) Err(AppError.ApiError(400, "UnsupportedSearch")) else repository.search(found, CatalogueQuery(request.query)) },
                    failure = { Err(it) },
                ) ?: Err(AppError.ApiError(400, "UnsupportedSearch"))
            }
            else -> repository.getRoot()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The session could not be made: the catalogue is going away, and the screen with it.
        Err(AppError.ApiError(400, CatalogueErrorKind.Unreachable.name))
    }

    private fun loadFirst(list: BookList) {
        list.cancelRequests()
        val generation = ++list.generation
        list.phase = Phase.FirstLoad
        list.header = null
        list.pages = emptyList()
        list.earlier = emptyList()
        list.more = Load.Idle
        list.earlierLoad = Load.Idle
        list.addresses.clear()
        list.emptyRun = 0
        publish()
        list.jobs += scope.launch {
            val result = fetch(list.first)
            if (!list.isCurrent(generation)) return@launch
            result.fold(
                success = { document -> showFirstPage(list, generation, document) },
                failure = { error -> showProblem(list, error.toLoadProblem()) },
            )
        }
    }

    private fun showProblem(list: BookList, problem: CatalogueLoadProblem) {
        list.phase = Phase.Problem(problem)
        if (problem == CatalogueLoadProblem.SignInNeeded) {
            signIn = CatalogueSignInState(wrongDetails = list.afterSignIn)
        } else if (list === current) {
            signIn = null
        }
        list.afterSignIn = false
        publish()
    }

    private suspend fun showFirstPage(list: BookList, generation: Int, document: CatalogueDocument) {
        // The transport followed a redirect to a device on the local network. Nothing of the
        // answer is shown until the user agrees.
        val redirectedTo = localHostOf(document)
        if (redirectedTo != null) {
            ask(
                host = redirectedTo,
                open = { scope.launch { if (list.isCurrent(generation)) showFirstPage(list, generation, document) }.also { list.jobs += it } },
                dontOpen = { if (list === base) close() else clearSearch() },
            )
            return
        }
        list.afterSignIn = false
        if (list === current) signIn = null
        val feed = when (document) {
            is CataloguePublicationDocument -> return replaceWithBook(document.context, listOf(document.publication))
            is CatalogueFeedDocument -> document
        }
        val opening = if (list.opening) decideCatalogueOpening(start?.fromEntryWithoutFiles == true, feed) else CatalogueOpening.Page
        if (opening == CatalogueOpening.OneBook) return replaceWithBook(feed.context, feed.publications)

        val linked = linkedBookEntries(source?.listEntriesAreBooks == true, feed, list.query != null)
        val inLibrary = inLibrary(feed.publications + linked + feed.groups.flatMap { it.publications })
        if (!list.isCurrent(generation)) return
        val shelves = feed.groups.withIndex().filter { it.value.publications.isNotEmpty() }.map { (index, group) ->
            ShelfEntry(
                key = "l${list.id}-s$index",
                title = group.title.display().orEmpty(),
                seeAll = group.links.firstOrNull { it.target != null },
                books = group.publications.mapIndexed { book, publication -> publication.entry("l${list.id}-s$index-$book", feed.context, inLibrary) },
            )
        }
        val folders = (feed.navigation.filterNot { it in linked } + feed.groups.flatMap { it.navigation }).mapIndexedNotNull { index, entry ->
            entry.pageLink()?.let { FolderEntry("l${list.id}-f$index", entry, it) }
        }
        list.header = Header(feed, shelves, folders, sameBookCount = feed.publications.size.takeIf { opening == CatalogueOpening.SameBookList })
        list.pages = listOf(page(list, FIRST_PAGE, list.first, feed, inLibrary))
        list.phase = if (shelves.isEmpty() && folders.isEmpty() && list.pages.all { it.books.isEmpty() }) Phase.Empty else Phase.Loaded
        if (list === base && start != null && pageTitle == null) pageTitle = feed.metadata.title.display()?.takeIf(String::isNotBlank)
        publish()
    }

    private fun replaceWithBook(listing: CatalogueTarget, publications: List<CataloguePublication>) {
        navigation = CatalogueBrowseNavigation.ReplaceWithBook(CatalogueBookPlace(listing, publications, start?.listingIdentity))
        publish()
    }

    private fun page(list: BookList, number: Int, request: PageRequest, feed: CatalogueFeedDocument, inLibrary: Set<String>, followed: CatalogueLink? = null): Page {
        list.addresses[number] = list.addresses[number].orEmpty() + setOfNotNull(followed?.resolvedHref, feed.responseUrl)
        return pageOf(list, number, request, feed, inLibrary, next = feed.pagination.next?.takeIf { it.target != null && !list.loopsBack(number, it) })
    }

    private fun pageOf(list: BookList, number: Int, request: PageRequest, feed: CatalogueFeedDocument, inLibrary: Set<String>, next: CatalogueLink?) = Page(
        number = number,
        request = request,
        books = (feed.publications + linkedBookEntries(source?.listEntriesAreBooks == true, feed, list.query != null)).mapIndexed { index, publication ->
            publication.entry("l${list.id}-p$number-$index", feed.context, inLibrary, linked = publication in feed.navigation)
        },
        next = next,
        savedCopyAt = feed.fetchStatus.savedCopyAt,
    )

    private fun CataloguePublication.entry(key: String, listing: CatalogueTarget, inLibrary: Set<String>, linked: Boolean = false) =
        BookEntry(key, this, listing, publicationKey in inLibrary, linked)

    /** One question for the whole page. A lookup that fails only means no row says "In your library". */
    private suspend fun inLibrary(publications: List<CataloguePublication>): Set<String> {
        if (publications.isEmpty()) return emptySet()
        return try {
            library.libraryBooksFor(sourceId, publications.map { CatalogueEntryIdentity(it.publicationKey) }).keys.mapTo(mutableSetOf()) { it.publicationKey }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Rechecks only library membership on return; catalogue data and scroll stay untouched. */
    fun onReturn() {
        if (!open) return
        listOfNotNull(base, search).forEach { list ->
            val generation = list.generation
            val entries = list.pages.flatMap { it.books } + list.header?.shelves.orEmpty().flatMap { it.books }
            list.jobs += scope.launch {
                val found = inLibrary(entries.map { it.publication })
                if (!list.isCurrent(generation)) return@launch
                entries.forEach { it.inLibrary = it.publication.publicationKey in found }
                publish()
            }
        }
    }

    // --- paging -------------------------------------------------------------------------

    /** Day and Night load near the end by themselves; E-ink only when "Load more" is tapped. */
    fun setAutoLoad(enabled: Boolean) {
        if (autoLoad == enabled) return
        autoLoad = enabled
        publish()
    }

    /** The list was scrolled near its end. Loads the next page only where pages load by themselves. */
    fun onNearEnd() {
        if (autoLoad && current.more == Load.Idle && current.emptyRun < MAX_EMPTY_PAGES_IN_A_ROW) loadNext(current, asked = false)
    }

    /** The list was scrolled near its start; brings back a page dropped at the page limit. */
    fun onNearStart() {
        if (autoLoad && current.earlierLoad == Load.Idle) loadPrevious(current)
    }

    /** "Load more", and "Try again" on a next page that failed: the same next link is asked again. */
    fun loadMore() = loadNext(current, asked = true)

    /** The same for the pages before the first one that is still loaded. */
    fun loadEarlier() = loadPrevious(current)

    private fun loadNext(list: BookList, asked: Boolean) {
        val last = list.pages.lastOrNull() ?: return
        val link = last.next ?: return
        val target = link.target ?: return
        if (!open || list.phase != Phase.Loaded || list.more == Load.Loading) return
        val host = unconfirmedLocalHost(link)
        if (host != null) {
            // Never by scrolling: the question is asked when the user taps.
            if (asked) ask(host, open = { loadNext(list, asked = true) }, dontOpen = {})
            return
        }
        val generation = list.generation
        list.more = Load.Loading
        publish()
        list.jobs += scope.launch {
            val feed = fetchPage(PageRequest.Target(target))
            val inLibrary = feed?.let { inLibrary(it.publications + linkedBookEntries(source?.listEntriesAreBooks == true, it, list.query != null)) }.orEmpty()
            if (!list.isCurrent(generation)) return@launch
            if (feed == null || list.pages.lastOrNull() !== last) {
                list.more = Load.Failed
            } else {
                list.more = Load.Idle
                val added = page(list, last.number + 1, PageRequest.Target(target), feed, inLibrary, followed = link)
                list.emptyRun = if (added.books.isEmpty()) list.emptyRun + 1 else 0
                list.pages = list.pages + added
                while (list.pages.size > maxPages) {
                    list.earlier = list.earlier + list.pages.first().request
                    list.pages = list.pages.drop(1)
                }
            }
            publish()
        }
    }

    private fun loadPrevious(list: BookList) {
        val request = list.earlier.lastOrNull() ?: return
        val first = list.pages.firstOrNull() ?: return
        if (!open || list.phase != Phase.Loaded || list.earlierLoad == Load.Loading) return
        val generation = list.generation
        list.earlierLoad = Load.Loading
        publish()
        list.jobs += scope.launch {
            val feed = fetchPage(request)
            val inLibrary = feed?.let { inLibrary(it.publications + linkedBookEntries(source?.listEntriesAreBooks == true, it, list.query != null)) }.orEmpty()
            if (!list.isCurrent(generation)) return@launch
            if (feed == null || list.pages.firstOrNull() !== first) {
                list.earlierLoad = Load.Failed
            } else {
                list.earlierLoad = Load.Idle
                list.earlier = list.earlier.dropLast(1)
                list.pages = listOf(page(list, first.number - 1, request, feed, inLibrary)) + list.pages
                if (list.pages.size > maxPages) {
                    // The far end goes; it is reached again through the next link of the page before it.
                    list.pages = list.pages.take(maxPages)
                    list.addresses.keys.removeAll { number -> number > list.pages.last().number }
                    list.more = Load.Idle
                }
            }
            publish()
        }
    }

    /** A further page of a list. Whatever goes wrong, the list shows one inline row for it. */
    private suspend fun fetchPage(request: PageRequest): CatalogueFeedDocument? = fetch(request).fold(
        success = { document -> (document as? CatalogueFeedDocument)?.takeIf { localHostOf(it) == null } },
        failure = { null },
    )

    // --- the message screens ------------------------------------------------------------

    /** "Try again" on a page that did not open: the same page is asked for again. */
    fun retry() {
        if (open && current.phase is Phase.Problem) loadFirst(current)
    }

    // --- search -------------------------------------------------------------------------

    fun onSearchTextChange(text: String) {
        if (!open) return
        searchText = text
        publish()
    }

    fun submitSearch() {
        val query = searchText.trim()
        if (!open || query.isEmpty() || base.header?.document?.search == null) return
        search?.cancelRequests()
        filterSheet = null
        val results = BookList(lists++, PageRequest.Search(query), query, opening = false)
        search = results
        loadFirst(results)
    }

    /** Leaves the search: the page is there again as it was, without asking for it. */
    fun clearSearch() {
        if (!open) return
        search?.cancelRequests()
        search = null
        searchText = ""
        filterSheet = null
        if (base.phase != Phase.Problem(CatalogueLoadProblem.SignInNeeded)) signIn = null
        publish()
    }

    // --- filters ------------------------------------------------------------------------

    private fun BookList.facets(): List<CatalogueFacetGroup> = header?.document?.facets.orEmpty().filter { it.options.isNotEmpty() || it.allOption != null }

    /** The catalogue's "all" option first, then its options in its order. */
    private fun CatalogueFacetGroup.ordered(): List<CatalogueFacetOption> = listOfNotNull(allOption) + options.filter { it != allOption }

    fun openFilter(groupIndex: Int) {
        if (!open || groupIndex !in current.facets().indices) return
        filterSheet = FilterSheet(groupIndex, "")
        publish()
    }

    fun onFilterSearchChange(text: String) {
        val sheet = filterSheet ?: return
        filterSheet = FilterSheet(sheet.groupIndex, text)
        publish()
    }

    fun closeFilter() {
        filterSheet = null
        publish()
    }

    /** Applies the option at once and closes the sheet. [optionIndex] counts all options, not the ones a search left. */
    fun chooseFilter(optionIndex: Int) {
        val sheet = filterSheet ?: return
        val list = current
        val option = list.facets().getOrNull(sheet.groupIndex)?.ordered()?.getOrNull(optionIndex)
        filterSheet = null
        publish()
        val target = option?.link?.target
        if (!open || option == null || option.active || target == null) return
        follow(option.link) {
            list.cancelRequests()
            val filtered = BookList(lists++, PageRequest.Target(target), list.query, opening = false)
            if (list === search) search = filtered else base = filtered
            loadFirst(filtered)
        }
    }

    // --- opening an entry ---------------------------------------------------------------

    fun openBook(key: String) {
        val list = current
        val entry = (list.pages.flatMap { it.books } + list.header?.shelves.orEmpty().flatMap { it.books }).firstOrNull { it.key == key } ?: return
        if (!open) return
        if (entry.linked) {
            openPage(entry.publication.pageLink() ?: return, entry.publication.displayTitle(), fromEntryWithoutFiles = true, listingIdentity = entry.publication.publicationKey)
            return
        }
        navigation = CatalogueBrowseNavigation.OpenBook(CatalogueBookPlace(entry.listing, listOf(entry.publication)))
        publish()
    }

    private fun allEntries() = listOfNotNull(base, search).flatMap { list -> list.pages.flatMap { it.books } + list.header?.shelves.orEmpty().flatMap { it.books } }
    fun visiblePublicationKeys(rowKeys: Set<String>) = allEntries().filter { it.key in rowKeys }.map { it.publication.publicationKey }
    private fun acquisition(entry: BookEntry) = acquisitions.lastOrNull { it.publicationKey == entry.publication.publicationKey || it.detailIdentity == entry.publication.publicationKey }

    /** Only the tapped row's details are fetched. A queue owns the download after request(). */
    fun downloadBook(key: String) {
        val manager = queue ?: return
        val entry = allEntries().firstOrNull { it.key == key } ?: return
        if (!open || entry.inLibrary || key in preparing || acquisition(entry)?.let { it.state.isRunning || it.state == AcquisitionState.Waiting || it.state == AcquisitionState.Done } == true) return
        val list = current
        val generation = list.generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val repository = gateway.repository(sourceId) ?: return@launch
                val full = entry.publication.links.firstOrNull { link -> link.target != null && "alternate" in link.relations &&
                    (link.mediaType?.subtype == "opds-publication+json" || link.mediaType?.parameters?.get("type") == "entry") }
                val detail = if (entry.linked) entry.publication.pageLink() else full
                val host = detail?.let(::unconfirmedLocalHost)
                if (host != null) { openBook(key); return@launch }
                val result = repository.getDocument(detail?.target ?: entry.listing)
                currentCoroutineContext().ensureActive()
                if (!list.isCurrent(generation)) return@launch
                result.fold(success = { document ->
                    if (localHostOf(document) != null) { openBook(key); return@fold }
                    val available = when (document) {
                        is CataloguePublicationDocument -> listOf(document.publication)
                        is CatalogueFeedDocument -> document.publications + document.groups.flatMap { it.publications }
                    }
                    val publications = if (entry.linked || document is CataloguePublicationDocument) available else available.filter { it.publicationKey == entry.publication.publicationKey }
                    val file = bookFileGroups(publications).flatMap { it.files }.firstOrNull { it.best }
                    val locator = file?.let { (repository as? com.retro99.server.api.CatalogueAcquisitionRepository)?.locate(document, it.publication, it.choice) }
                    val failedRequest = acquisition(entry)?.let { it.state is AcquisitionState.Failed || it.state == AcquisitionState.Interrupted } == true
                    if (publications.size != 1 || file == null || locator == null || failedRequest) {
                        if (publications.isNotEmpty()) navigation = CatalogueBrowseNavigation.OpenBook(CatalogueBookPlace(document.context, publications, entry.publication.publicationKey.takeIf { entry.linked }))
                        else openBook(key)
                        return@fold
                    }
                    val outcome = manager.request(CatalogueAcquisitionRequest(sourceId, locator.publicationKey, locator.representationKey,
                        entry.publication.publicationKey.takeIf { it != locator.publicationKey }, locator.documentUrl,
                        file.publication.displayTitle(), file.publication.displayAuthor(), file.publication.images.firstOrNull()?.href,
                        source?.name.orEmpty(), file.size, file.publication.rights.display(), file.publication.updated))
                    currentCoroutineContext().ensureActive()
                    if (!list.isCurrent(generation)) return@fold
                    when (outcome) {
                        is CatalogueRequestOutcome.Queued -> downloadNotice = entry.publication.displayTitle()
                        is CatalogueRequestOutcome.InLibrary -> entry.inLibrary = true
                    }
                }, failure = { openBook(key) })
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (list.isCurrent(generation)) openBook(key) }
            finally { if (preparing[key] == currentCoroutineContext()[Job]) preparing.remove(key); if (open) publish() }
        }
        preparing[key] = job
        list.jobs += job
        publish()
        job.start()
    }

    fun cancelDownload(key: String) {
        if (!open) return
        preparing.remove(key)?.let { job -> job.cancel(); allEntries().firstOrNull { it.key == key }?.let { onCancelled(it.publication.displayTitle()) }; publish(); return }
        val entry = allEntries().firstOrNull { it.key == key } ?: return
        val row = acquisition(entry) ?: return
        if (row.state == AcquisitionState.Waiting || row.state == AcquisitionState.Downloading) scope.launch { if (queue?.cancel(row.requestId) == true) onCancelled(row.title) }
    }
    fun noticeHandled() { downloadNotice = null; publish() }

    fun openFolder(key: String) {
        val folder = current.header?.folders?.firstOrNull { it.key == key } ?: return
        openPage(folder.link, folder.entry.displayTitle(), fromEntryWithoutFiles = true)
    }

    fun openSeeAll(shelfKey: String) {
        val shelf = current.header?.shelves?.firstOrNull { it.key == shelfKey } ?: return
        openPage(shelf.seeAll ?: return, shelf.title, fromEntryWithoutFiles = false)
    }

    private fun openPage(link: CatalogueLink, title: String, fromEntryWithoutFiles: Boolean, listingIdentity: String? = null) {
        val target = link.target ?: return
        if (!open) return
        val host = source?.let { localNetworkHostLeaving(it.address, link.resolvedHref.orEmpty()) }
        follow(link) {
            navigation = CatalogueBrowseNavigation.OpenPage(CataloguePlace(target, title.takeIf(String::isNotBlank), fromEntryWithoutFiles, host, listingIdentity))
            publish()
        }
    }

    fun navigationHandled() {
        navigation = null
        publish()
    }

    // --- links to the local network -----------------------------------------------------

    private fun unconfirmedLocalHost(link: CatalogueLink): String? =
        source?.let { localNetworkHostLeaving(it.address, link.resolvedHref.orEmpty()) }?.takeIf { it !in agreedHosts }

    /** The local device an answer came from after a redirect, unless the user already agreed to open it. */
    private fun localHostOf(document: CatalogueDocument): String? {
        if (!document.fetchStatus.crossOriginPrivateNetwork) return null
        val host = source?.let { localNetworkHostLeaving(it.address, document.responseUrl) }.orEmpty()
        return host.takeIf { it !in agreedHosts }
    }

    /** Follows [link] at once, or after the user agreed when it leaves for the local network. */
    private fun follow(link: CatalogueLink, action: () -> Unit) {
        val host = unconfirmedLocalHost(link)
        if (host == null) action() else ask(host, open = action, dontOpen = {})
    }

    private fun ask(host: String, open: () -> Unit, dontOpen: () -> Unit) {
        question = LocalNetworkQuestion(host, open, dontOpen)
        publish()
    }

    /** "Open". */
    fun confirmLocalNetwork() {
        val asked = question ?: return
        question = null
        agreedHosts += asked.host
        publish()
        if (open) asked.open()
    }

    /** "Don't open", the primary action. */
    fun dismissLocalNetwork() {
        val asked = question ?: return
        question = null
        publish()
        if (open) asked.dontOpen()
    }

    // --- sign-in ------------------------------------------------------------------------

    /** Verifies the page with temporary details, then saves them. An empty password is allowed. */
    fun signIn(username: String, password: String) {
        val list = current
        val name = username.trim()
        if (!open || signIn == null || signIn?.working == true || name.isEmpty()) return
        signIn = CatalogueSignInState(working = true)
        publish()
        list.cancelRequests()
        val generation = ++list.generation
        list.jobs += scope.launch {
            val account = OpdsAccountDetails(name, password)
            val answer = try {
                val request = list.first
                val document = base.header?.document
                if (request is PageRequest.Search && document != null) gateway.checkSearchAccount(sourceId, document, CatalogueQuery(request.query), account)
                else gateway.checkAccount(sourceId, (request as? PageRequest.Target)?.target, account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Err(AppError.ApiError(400, CatalogueErrorKind.Unreachable.name))
            }
            if (!list.isCurrent(generation)) return@launch
            answer.fold(success = { document ->
                gateway.saveAccount(sourceId, account)
                if (!list.isCurrent(generation)) return@fold
                showFirstPage(list, generation, document)
            }, failure = { error ->
                list.afterSignIn = true
                showProblem(list, error.toLoadProblem())
            })
        }
    }

    /** The sheet was closed without signing in: there is nothing to show, so the screen closes. */
    fun dismissSignIn() {
        if (signIn == null) return
        close()
    }

    // --- what the screen draws ----------------------------------------------------------

    private fun publish() {
        val list = current
        _state.value = CatalogueBrowseState(
            catalogueName = source?.name.orEmpty(),
            title = pageTitle,
            searchAvailable = base.header?.document?.search != null,
            searchText = searchText,
            searchQuery = search?.query,
            listId = list.id,
            content = content(list),
            filterSheet = filterSheet?.let { sheet(list, it) },
            localNetworkHost = question?.host,
            signIn = signIn,
            navigation = navigation,
            closed = closed,
            downloadNotice = downloadNotice,
        )
    }

    private fun content(list: BookList): CatalogueBrowseContent = when (val phase = list.phase) {
        Phase.FirstLoad -> CatalogueBrowseContent.FirstLoad
        Phase.Empty -> list.query?.let { CatalogueBrowseContent.NoResults(it) } ?: CatalogueBrowseContent.EmptyFolder
        is Phase.Problem -> when (val problem = phase.problem) {
            CatalogueLoadProblem.SignInNeeded -> CatalogueBrowseContent.SignInNeeded
            CatalogueLoadProblem.Offline -> CatalogueBrowseContent.OfflineNone
            CatalogueLoadProblem.RateLimited -> CatalogueBrowseContent.RateLimited
            is CatalogueLoadProblem.Failed -> CatalogueBrowseContent.Failed(problem.reason)
        }
        Phase.Loaded -> {
            val header = list.header
            val books = list.pages.flatMap { it.books }
            // In the list of one book's entries every row tells itself apart; elsewhere only siblings do.
            val siblings = if (header?.sameBookCount != null) null else siblingKeys(books.map { it.publication })
            val next = list.pages.lastOrNull()?.next
            CatalogueBrowseContent.Loaded(
                chips = list.facets().mapIndexed { index, group ->
                    val value = group.options.firstOrNull { it.active } ?: group.allOption
                    CatalogueFilterChip(index, group.name.display(), value?.title.display() ?: group.name.display().orEmpty())
                },
                shelves = header?.shelves.orEmpty().map { shelf ->
                    val shelfSiblings = siblingKeys(shelf.books.map { it.publication })
                    CatalogueShelf(shelf.key, shelf.title, shelf.seeAll != null, shelf.books.map { it.row(shelfSiblings) })
                },
                folders = header?.folders.orEmpty().map { folder ->
                    CatalogueFolderRow(folder.key, folder.entry.displayTitle(), (folder.entry.summary.display() ?: folder.entry.content?.takeIf { it.format == com.retro99.server.api.CatalogueDescription.Format.Text }?.body.display())?.takeIf(String::isNotBlank))
                },
                books = books.map { it.row(siblings) },
                sameBookCount = header?.sameBookCount,
                earlier = paging(list.earlierLoad, there = list.earlier.isNotEmpty(), needsTap = false),
                more = paging(list.more, there = next != null, needsTap = next != null && (unconfirmedLocalHost(next) != null || list.emptyRun >= MAX_EMPTY_PAGES_IN_A_ROW)),
                savedCopyAt = list.pages.mapNotNull { it.savedCopyAt }.minOrNull(),
            )
        }
    }

    private fun paging(load: Load, there: Boolean, needsTap: Boolean): CataloguePaging = when {
        !there -> CataloguePaging.None
        load == Load.Loading -> CataloguePaging.Loading
        load == Load.Failed -> CataloguePaging.Failed
        autoLoad && !needsTap -> CataloguePaging.Auto
        else -> CataloguePaging.Button
    }

    /** @param siblings the same-book keys that get a telling line; null gives every row one */
    private fun BookEntry.row(siblings: Set<String>?) = CatalogueBookRow(
        key = key,
        title = publication.displayTitle(),
        author = if (linked) publication.content?.body.display() ?: publication.summary.display() else publication.displayAuthor(),
        cover = if (linked) null else publication.images.firstOrNull()?.let { CatalogueImageModel(sourceId, it.href) },
        inLibrary = inLibrary,
        telling = publication.tellingLine().takeIf { siblings == null || publication.sameBookKey() in siblings },
        download = when {
            acquisition(this)?.state == AcquisitionState.Done || inLibrary -> ListDownloadState.InLibrary
            acquisition(this)?.state == AcquisitionState.Waiting -> ListDownloadState.Waiting
            acquisition(this)?.state == AcquisitionState.Downloading -> acquisition(this)!!.let { ListDownloadState.Downloading(it.bytesSoFar, it.expectedSizeBytes?.takeIf { size -> size > 0 }) }
            acquisition(this)?.state == AcquisitionState.Checking || acquisition(this)?.state == AcquisitionState.Adding -> ListDownloadState.Adding
            key in preparing -> ListDownloadState.GettingReady
            else -> ListDownloadState.Available
        },
    )

    private fun sheet(list: BookList, sheet: FilterSheet): CatalogueFilterSheet? {
        val group = list.facets().getOrNull(sheet.groupIndex) ?: return null
        val options = group.ordered()
        val searchable = options.size > FILTER_SEARCH_ABOVE
        val wanted = sheet.searchText.trim().takeIf { searchable }.orEmpty()
        return CatalogueFilterSheet(
            groupIndex = sheet.groupIndex,
            group = group.name.display(),
            optionCount = options.size,
            searchable = searchable,
            searchText = sheet.searchText,
            options = options.mapIndexed { index, option -> CatalogueFilterOption(index, option.title.display().orEmpty(), option.count, option.active) }
                .filter { it.title.contains(wanted, ignoreCase = true) },
        )
    }

    companion object {
        /** Plan §4: at most 20 parsed pages are kept for one list. */
        const val MAX_LOADED_PAGES = 20

        /** A filter with more options than this gets a search field. */
        const val FILTER_SEARCH_ABOVE = 12
        private const val FIRST_PAGE = 0

        /**
         * A catalogue can answer every "next" link with one more page that has no books. After
         * this many in a row the list stops loading by itself and offers "Load more".
         */
        const val MAX_EMPTY_PAGES_IN_A_ROW = 3
    }
}
