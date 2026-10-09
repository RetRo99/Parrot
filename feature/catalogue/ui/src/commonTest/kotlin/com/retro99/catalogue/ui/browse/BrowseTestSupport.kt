package com.retro99.catalogue.ui.browse

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.catalogue.domain.CatalogueEntryIdentity
import com.retro99.catalogue.domain.CatalogueLibraryLookup
import com.retro99.server.api.CatalogueAcquisitionAction
import com.retro99.server.api.CatalogueContributor
import com.retro99.server.api.CatalogueDocument
import com.retro99.server.api.CatalogueErrorKind
import com.retro99.server.api.CatalogueFacetGroup
import com.retro99.server.api.CatalogueFacetOption
import com.retro99.server.api.CatalogueFeedDocument
import com.retro99.server.api.CatalogueFeedMetadata
import com.retro99.server.api.CatalogueFetchStatus
import com.retro99.server.api.CatalogueFileChoice
import com.retro99.server.api.CatalogueGroup
import com.retro99.server.api.CatalogueIdentity
import com.retro99.server.api.CatalogueImage
import com.retro99.server.api.CatalogueLink
import com.retro99.server.api.CatalogueMediaType
import com.retro99.server.api.CataloguePagination
import com.retro99.server.api.CataloguePublication
import com.retro99.server.api.CataloguePublicationDocument
import com.retro99.server.api.CatalogueQuery
import com.retro99.server.api.CatalogueSearch
import com.retro99.server.api.CatalogueSearchOffer
import com.retro99.server.api.CatalogueTarget
import com.retro99.server.api.CatalogueText
import com.retro99.server.api.OpdsAccountDetails
import com.retro99.server.api.ServerCatalogueRepository
import com.retro99.server.api.representationKeyOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

internal const val SOURCE = "source-1"
internal const val ORIGIN = "https://cat.example"

internal class FakeTarget(val name: String) : CatalogueTarget {
    override fun equals(other: Any?) = other is FakeTarget && other.name == name
    override fun hashCode() = name.hashCode()
    override fun toString() = "FakeTarget($name)"
}

private object FakeSearch : CatalogueSearch

internal fun text(value: String) = CatalogueText(mapOf("und" to value))
private val FEED_TYPE = CatalogueMediaType("application", "atom+xml", mapOf("profile" to "opds-catalog"))

internal fun link(name: String, href: String = "$ORIGIN/$name", title: String? = null, relations: List<String> = emptyList()) = CatalogueLink(
    rawHref = href, resolvedHref = href, isTemplate = false, effectiveBaseUri = ORIGIN, relations = relations, mediaType = FEED_TYPE,
    title = title?.let(::text), lengthBytes = null, price = null, indirectAcquisition = null, extras = emptyMap(), target = FakeTarget(name),
)

private fun file(name: String): CatalogueFileChoice {
    val link = link(name).copy(mediaType = CatalogueMediaType("application", "epub+zip", emptyMap()), target = null)
    return CatalogueFileChoice(link, CatalogueAcquisitionAction.Download(link), isOpenable = true, isDefault = true)
}

internal fun book(
    title: String,
    author: String? = "Ann Author",
    id: String = "id:$title",
    files: Int = 1,
    edition: String? = null,
    year: String? = null,
    cover: String? = null,
) = CataloguePublication(
    identity = CatalogueIdentity(id, CatalogueIdentity.Scope.Nominal), title = text(title), updated = null,
    authors = listOfNotNull(author).map { CatalogueContributor(text(it), null, null) }, otherContributors = emptyList(), languages = emptyList(),
    summary = null, content = null, rights = null, publisher = null, published = null, year = year, identifiers = emptyList(),
    images = listOfNotNull(cover).map { CatalogueImage(it, null, null, null) }, links = emptyList(), editionLabel = edition?.let(::text),
    seller = null, lender = null, acquisitionChoices = (1..files).map { file("$id-file$it") },
    acquisitionAction = CatalogueAcquisitionAction.Download(link("$id-file1")), acquisitionProviderName = null, unsupportedMediaType = null,
)

/** A listing entry without files that links to another page. */
internal fun folder(title: String, target: String = title, href: String = "$ORIGIN/$target", subtitle: String? = null) =
    book(title, author = null, id = "nav:$title", files = 0).copy(links = listOf(link(target, href)), summary = subtitle?.let(::text))

internal fun option(title: String, target: String = "facet-$title", active: Boolean = false, count: Long? = null) =
    CatalogueFacetOption(text(title), link(target), active, count)

internal fun feed(
    name: String = "root",
    title: String = "Feed $name",
    books: List<CataloguePublication> = emptyList(),
    folders: List<CataloguePublication> = emptyList(),
    groups: List<CatalogueGroup> = emptyList(),
    facets: List<CatalogueFacetGroup> = emptyList(),
    next: String? = null,
    first: String? = null,
    search: Boolean = false,
    savedCopyAt: Long? = null,
    privateNetwork: Boolean = false,
    responseUrl: String = "$ORIGIN/$name",
): CatalogueFeedDocument = CatalogueFeedDocument(
    metadata = CatalogueFeedMetadata(text(title), null, null, emptyList(), null, null, null),
    navigation = folders, publications = books, groups = groups, facets = facets,
    pagination = CataloguePagination(first?.let { link(it) }, next?.let { link(it) }, null, null),
    search = if (search) CatalogueSearchOffer(link("search-description"), CatalogueSearchOffer.Kind.Descriptor) else null,
    up = emptyList(), responseUrl = responseUrl, self = null, referencedBy = null, warnings = emptyList(),
    fetchStatus = CatalogueFetchStatus(checkedAt = 0, fromCache = savedCopyAt != null, crossOriginPrivateNetwork = privateNetwork, savedCopyAt = savedCopyAt),
    context = FakeTarget(name),
)

internal fun bookDocument(publication: CataloguePublication, name: String = "book") = CataloguePublicationDocument(
    publication, "$ORIGIN/$name", null, null, emptyList(), CatalogueFetchStatus(0, false, false), FakeTarget(name),
)

internal fun failure(kind: CatalogueErrorKind): AppResult<CatalogueDocument> = Err(AppError.ApiError(400, kind.name))

/**
 * Answers are given by the test, by request: "root", a target's name, or "search:<text>". A
 * request waits for its answer and takes it even after it was cancelled, so a late answer always
 * reaches the code under test.
 */
internal class FakeRepository : ServerCatalogueRepository, com.retro99.server.api.CatalogueAcquisitionRepository {
    override val serverId = SOURCE
    private class Call(val key: String, val answer: CompletableDeferred<AppResult<CatalogueDocument>> = CompletableDeferred())
    private val calls = mutableListOf<Call>()
    val requested: List<String> get() = calls.map { it.key }
    val waiting: List<String> get() = calls.filterNot { it.answer.isCompleted }.map { it.key }

    fun answer(key: String, document: CatalogueDocument) = answer(key, Ok(document))
    fun answer(key: String, result: AppResult<CatalogueDocument>) {
        val call = calls.firstOrNull { it.key == key && !it.answer.isCompleted } ?: error("Nothing is waiting for $key; waiting: $waiting")
        call.answer.complete(result)
    }

    private suspend fun ask(key: String): AppResult<CatalogueDocument> {
        val call = Call(key).also(calls::add)
        return withContext(NonCancellable) { call.answer.await() }
    }

    override suspend fun getRoot() = ask("root")
    override suspend fun getDocument(target: CatalogueTarget) = ask((target as FakeTarget).name)
    override suspend fun discoverSearch(document: CatalogueDocument): AppResult<CatalogueSearch?> =
        Ok(FakeSearch.takeIf { (document as? CatalogueFeedDocument)?.search != null })
    override suspend fun search(search: CatalogueSearch, query: CatalogueQuery) = ask("search:${query.text}")
    override fun locate(document: CatalogueDocument, publication: CataloguePublication, choice: CatalogueFileChoice) =
        publication.representationKeyOf(choice)?.let { com.retro99.server.api.CatalogueAcquisitionLocator(document.responseUrl, publication.identity.value, it) }
    override suspend fun download(locator: com.retro99.server.api.CatalogueAcquisitionLocator, sink: com.retro99.server.api.CatalogueFileSink): com.retro99.server.api.CatalogueDownloadOutcome = error("not used")
}

internal class FakeGateway(val repository: FakeRepository = FakeRepository()) : CatalogueBrowseGateway {
    val source = MutableStateFlow<CatalogueBrowseSource?>(CatalogueBrowseSource("profile-1", "Project Gutenberg", "$ORIGIN/opds"))
    val savedAccounts = mutableListOf<OpdsAccountDetails>()
    override fun observeSource(sourceId: String) = source
    override suspend fun repository(sourceId: String): ServerCatalogueRepository? = repository.takeIf { source.value != null }
    override suspend fun saveAccount(sourceId: String, details: OpdsAccountDetails) { savedAccounts += details }
    override suspend fun checkAccount(sourceId: String, target: CatalogueTarget?, details: OpdsAccountDetails) =
        if (target == null) repository.getRoot() else repository.getDocument(target)
    override suspend fun checkSearchAccount(sourceId: String, document: CatalogueDocument, query: CatalogueQuery, details: OpdsAccountDetails) =
        repository.search(FakeSearch, query)
}

internal class FakeLibrary(var inLibrary: Set<String> = emptySet(), private val fails: Boolean = false) : CatalogueLibraryLookup {
    val asked = mutableListOf<List<String>>()
    override suspend fun libraryBooksFor(sourceId: String, entries: Collection<CatalogueEntryIdentity>): Map<CatalogueEntryIdentity, String> {
        asked += entries.map { it.publicationKey }
        if (fails) error("database closed")
        return entries.filter { it.publicationKey in inLibrary }.associateWith { "library-book" }
    }
}
