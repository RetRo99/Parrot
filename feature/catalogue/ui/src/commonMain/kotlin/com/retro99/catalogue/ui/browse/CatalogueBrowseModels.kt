package com.retro99.catalogue.ui.browse

import com.retro99.base.CalendarDateLabel
import com.retro99.base.calendarDateLabel
import com.retro99.base.result.AppError
import com.retro99.catalogue.ui.navigation.CatalogueBookPlace
import com.retro99.catalogue.ui.navigation.CataloguePlace
import com.retro99.server.api.CatalogueErrorKind
import com.retro99.server.api.CatalogueFeedDocument
import com.retro99.server.api.CatalogueImageModel
import com.retro99.server.api.CatalogueLink
import com.retro99.server.api.CatalogueMediaType
import com.retro99.server.api.CataloguePublication
import com.retro99.server.api.CatalogueText
import kotlin.time.Instant

/** What the catalogue browser shows. Built by [CatalogueBrowser]; the screen only draws it. */
data class CatalogueBrowseState(
    val catalogueName: String = "",
    /** The page's own title; null on the catalogue's first page, where the catalogue's name is the title. */
    val title: String? = null,
    /** Only when the catalogue advertises search on this page. */
    val searchAvailable: Boolean = false,
    val searchText: String = "",
    /** The search that was sent. While set, the search field is the top bar. */
    val searchQuery: String? = null,
    /** Changes when the list is another one (a search, a filter): its scroll position starts again. */
    val listId: Int = 0,
    val content: CatalogueBrowseContent = CatalogueBrowseContent.FirstLoad,
    val filterSheet: CatalogueFilterSheet? = null,
    /** "Open a device on your network?" is asked for this host. */
    val localNetworkHost: String? = null,
    val signIn: CatalogueSignInState? = null,
    val navigation: CatalogueBrowseNavigation? = null,
    /** The catalogue was turned off or removed, or the profile changed: the screen closes. */
    val closed: Boolean = false,
    val downloadNotice: String? = null,
)

sealed interface CatalogueBrowseContent {
    data object FirstLoad : CatalogueBrowseContent

    /**
     * @param sameBookCount set when every entry is the same book and they could not be grouped
     *   into editions (opds-editionsList): "<Catalogue> lists this book <N> times."
     * @param savedCopyAt set when the catalogue could not be reached and this is the saved copy
     */
    data class Loaded(
        val chips: List<CatalogueFilterChip> = emptyList(),
        val shelves: List<CatalogueShelf> = emptyList(),
        val folders: List<CatalogueFolderRow> = emptyList(),
        val books: List<CatalogueBookRow> = emptyList(),
        val sameBookCount: Int? = null,
        val earlier: CataloguePaging = CataloguePaging.None,
        val more: CataloguePaging = CataloguePaging.None,
        val savedCopyAt: Long? = null,
    ) : CatalogueBrowseContent

    data object EmptyFolder : CatalogueBrowseContent
    data class NoResults(val query: String) : CatalogueBrowseContent
    data object OfflineNone : CatalogueBrowseContent
    data object RateLimited : CatalogueBrowseContent

    /** The page asks for an account. The sign-in sheet is open over it ([CatalogueBrowseState.signIn]). */
    data object SignInNeeded : CatalogueBrowseContent
    data class Failed(val reason: CataloguePageFailure) : CatalogueBrowseContent
}

/** One end of a book list. Never [None] while the catalogue still has a page there. */
enum class CataloguePaging {
    /** Nothing further in this direction. */
    None,

    /** There is a page; it loads by itself when the list is scrolled near it ("Loading more…"). */
    Auto,

    /** There is a page; it loads when the button is tapped ("Load more"). */
    Button,
    Loading,

    /** The page did not load. The books stay; "Try again" asks for the same page. */
    Failed,
}

/** The reasons of the "Couldn't open this page" screen (design prompt §11.5). */
enum class CataloguePageFailure(val canRetry: Boolean, val offersSettings: Boolean) {
    TimedOut(canRetry = true, offersSettings = false),
    CatalogueError(canRetry = true, offersSettings = false),
    NotAllowed(canRetry = false, offersSettings = false),
    NotFound(canRetry = false, offersSettings = false),
    TooLarge(canRetry = false, offersSettings = false),
    NotACatalogue(canRetry = false, offersSettings = true),
    Certificate(canRetry = false, offersSettings = true),
}

data class CatalogueBookRow(
    val key: String,
    val title: String,
    /** Null when the catalogue names no author ("Unknown author"). */
    val author: String?,
    val cover: CatalogueImageModel?,
    val inLibrary: Boolean,
    /** Only for a row that has a same-title sibling in the list. */
    val telling: CatalogueTellingLine?,
    val download: com.retro99.catalogue.ui.downloads.ListDownloadState = if (inLibrary) com.retro99.catalogue.ui.downloads.ListDownloadState.InLibrary else com.retro99.catalogue.ui.downloads.ListDownloadState.Available,
)

data class CatalogueFolderRow(val key: String, val title: String, val subtitle: String?)
data class CatalogueShelf(val key: String, val title: String, val hasSeeAll: Boolean, val books: List<CatalogueBookRow>)

/** @param group the facet group's name, null when the catalogue gives none */
data class CatalogueFilterChip(val index: Int, val group: String?, val value: String)

data class CatalogueFilterOption(val index: Int, val title: String, val count: Long?, val selected: Boolean)
data class CatalogueFilterSheet(
    val groupIndex: Int,
    val group: String?,
    val optionCount: Int,
    /** Offered above [CatalogueBrowser.FILTER_SEARCH_ABOVE] options. */
    val searchable: Boolean,
    val searchText: String,
    /** The options that match [searchText], in the catalogue's order, its "all" option first. */
    val options: List<CatalogueFilterOption>,
)

data class CatalogueSignInState(val working: Boolean = false, val wrongDetails: Boolean = false)

/** Where the screen goes next. The screen navigates and then calls [CatalogueBrowser.navigationHandled]. */
sealed interface CatalogueBrowseNavigation {
    data class OpenPage(val place: CataloguePlace) : CatalogueBrowseNavigation
    data class OpenBook(val book: CatalogueBookPlace) : CatalogueBrowseNavigation

    /** This page turned out to be one book: its page takes this screen's place. */
    data class ReplaceWithBook(val book: CatalogueBookPlace) : CatalogueBrowseNavigation
}

/**
 * What tells same-title rows apart (design prompt §9 A3). Shown as: the edition label; the year,
 * as "Published <year>" when there is no label; the file count; "No edition details" in front
 * when there is neither label nor year.
 */
data class CatalogueTellingLine(val editionLabel: String?, val year: String?, val fileCount: Int)

internal fun CatalogueText?.display(): String? =
    this?.translations?.let { texts -> texts["und"]?.takeIf(String::isNotBlank) ?: texts.values.firstOrNull(String::isNotBlank) }?.trim()

internal fun CataloguePublication.displayTitle(): String = title.display().orEmpty()
internal fun CataloguePublication.displayAuthor(): String? =
    authors.mapNotNull { it.name.display()?.takeIf(String::isNotBlank) }.takeIf { it.isNotEmpty() }?.joinToString(", ")

internal fun CataloguePublication.tellingLine(): CatalogueTellingLine = CatalogueTellingLine(
    editionLabel = editionLabel.display()?.takeIf(String::isNotBlank),
    year = (year ?: published?.take(4))?.trim()?.takeIf { it.length == 4 && it.all(Char::isDigit) },
    fileCount = acquisitionChoices.size,
)

/** Same title and same author, whatever the case and spacing: the rows a reader takes for one book. */
internal fun CataloguePublication.sameBookKey(): String =
    displayTitle().lowercase().split(' ', '\t', '\n').filter(String::isNotEmpty).joinToString(" ") + "\u0000" + displayAuthor().orEmpty().lowercase()

/** The keys ([sameBookKey]) that more than one of [publications] has. */
internal fun siblingKeys(publications: List<CataloguePublication>): Set<String> =
    publications.groupingBy { it.sameBookKey() }.eachCount().filterValues { it > 1 }.keys

private fun CatalogueMediaType?.isCataloguePage(): Boolean =
    this != null && type.equals("application", true) && subtype.lowercase() in setOf("atom+xml", "opds+json", "opds-publication+json")

/** The link a folder opens: the entry's link to another catalogue page. */
internal fun CataloguePublication.pageLink(): CatalogueLink? =
    links.firstOrNull { it.target != null && it.mediaType.isCataloguePage() }
        ?: links.firstOrNull { it.target != null && it.mediaType == null }

/**
 * Conservative opt-in rule for link-only book lists: a paginated feed or search result,
 * a plain-text entry body, and a queryless resource link. Query links remain navigation
 * (author/subject searches); an unpaginated navigation feed, including the start page,
 * remains folders. No provider names, path patterns, titles or image bytes are consulted.
 */
fun linkedBookEntries(flagged: Boolean, feed: CatalogueFeedDocument, searchResults: Boolean = false): List<CataloguePublication> {
    if (!flagged || (!searchResults && listOf(feed.pagination.first, feed.pagination.next, feed.pagination.previous, feed.pagination.last).all { it == null })) return emptyList()
    return feed.navigation.filter { entry ->
        val body = entry.content?.takeIf { it.format == com.retro99.server.api.CatalogueDescription.Format.Text }?.body.display() ?: entry.summary.display()
        entry.acquisitionChoices.isEmpty() && !body.isNullOrBlank() && entry.pageLink()?.resolvedHref?.let { '?' !in it && '#' !in it } == true
    }
}

/** What opening a place turned out to be, by the Phase 1 grouping rule (plan §11.7, Still open 1). */
enum class CatalogueOpening {
    /** One book, with its editions: the book page. */
    OneBook,

    /** The same book listed several times, not groupable into editions (opds-editionsList). */
    SameBookList,
    Page,
}

/**
 * The rule of `OpdsGroupingRule`, on the catalogue model: a listing entry without files whose
 * target is an unpaginated list of books that share one title is a single book with editions.
 * Anything else is a list; when all of its entries still share title and author it is drawn as
 * the same book listed several times.
 *
 * @param openedFromEntryWithoutFiles the page was opened from a listing entry that had no file
 *   of its own
 */
fun decideCatalogueOpening(openedFromEntryWithoutFiles: Boolean, feed: CatalogueFeedDocument): CatalogueOpening {
    val books = feed.publications
    if (books.isEmpty() || feed.navigation.isNotEmpty() || feed.groups.isNotEmpty()) return CatalogueOpening.Page
    val oneTitle = books.map { it.title }.distinct().size == 1
    val unpaginated = feed.pagination.next == null && feed.pagination.first == null
    return when {
        openedFromEntryWithoutFiles && unpaginated && oneTitle -> CatalogueOpening.OneBook
        books.size > 1 && books.map { it.sameBookKey() }.distinct().size == 1 -> CatalogueOpening.SameBookList
        else -> CatalogueOpening.Page
    }
}

internal sealed interface CatalogueLoadProblem {
    data object SignInNeeded : CatalogueLoadProblem
    data object Offline : CatalogueLoadProblem
    data object RateLimited : CatalogueLoadProblem
    data class Failed(val reason: CataloguePageFailure) : CatalogueLoadProblem
}

/** Why a page did not load, from the catalogue session's error. */
internal fun AppError.toLoadProblem(): CatalogueLoadProblem {
    if (this is AppError.NetworkError) return CatalogueLoadProblem.Offline
    val kind = (this as? AppError.ApiError)?.message?.let { name -> CatalogueErrorKind.entries.firstOrNull { it.name == name } }
    return when (kind) {
        CatalogueErrorKind.SignInNeeded -> CatalogueLoadProblem.SignInNeeded
        CatalogueErrorKind.OfflineNoSavedCopy, CatalogueErrorKind.Unreachable -> CatalogueLoadProblem.Offline
        CatalogueErrorKind.RateLimited -> CatalogueLoadProblem.RateLimited
        CatalogueErrorKind.Timeout -> CatalogueLoadProblem.Failed(CataloguePageFailure.TimedOut)
        CatalogueErrorKind.ServerError -> CatalogueLoadProblem.Failed(CataloguePageFailure.CatalogueError)
        // A sign-in method Parrot cannot use and a link the catalogue session will not follow:
        // either way this page is not open to Parrot.
        CatalogueErrorKind.Forbidden, CatalogueErrorKind.SignInUnsupported, CatalogueErrorKind.SecurityPolicy ->
            CatalogueLoadProblem.Failed(CataloguePageFailure.NotAllowed)
        CatalogueErrorKind.NotFound -> CatalogueLoadProblem.Failed(CataloguePageFailure.NotFound)
        CatalogueErrorKind.TooLarge -> CatalogueLoadProblem.Failed(CataloguePageFailure.TooLarge)
        CatalogueErrorKind.InvalidDocument -> CatalogueLoadProblem.Failed(CataloguePageFailure.NotACatalogue)
        CatalogueErrorKind.Tls -> CatalogueLoadProblem.Failed(CataloguePageFailure.Certificate)
        null -> when ((this as? AppError.ApiError)?.message) {
            "WebPage", "NotCatalogue" -> CatalogueLoadProblem.Failed(CataloguePageFailure.NotACatalogue)
            else -> CatalogueLoadProblem.Failed(CataloguePageFailure.CatalogueError)
        }
    }
}

/** How long ago, in the forms of design prompt §11.11. */
sealed interface CatalogueTimeAgo {
    data object JustNow : CatalogueTimeAgo
    data class Minutes(val count: Int) : CatalogueTimeAgo
    data class Hours(val count: Int) : CatalogueTimeAgo
    data object Yesterday : CatalogueTimeAgo
    data class Days(val count: Int) : CatalogueTimeAgo

    /** @param date the shared helper's medium date, without the year when it is this year */
    data class OnDate(val date: String) : CatalogueTimeAgo
}

/**
 * Under a minute "just now"; under an hour minutes; within 24 hours hours; then calendar
 * "yesterday" from the app's shared date helper; up to six days a day count; after that the date.
 */
fun catalogueTimeAgo(thenMillis: Long, nowMillis: Long, timeZoneId: String? = null): CatalogueTimeAgo {
    val elapsed = (nowMillis - thenMillis).coerceAtLeast(0)
    val minutes = elapsed / MILLIS_PER_MINUTE
    if (minutes < 1) return CatalogueTimeAgo.JustNow
    if (minutes < 60) return CatalogueTimeAgo.Minutes(minutes.toInt())
    if (minutes < 24 * 60) return CatalogueTimeAgo.Hours((minutes / 60).toInt())
    val then = Instant.fromEpochMilliseconds(thenMillis).toString()
    val label = if (timeZoneId == null) calendarDateLabel(then, nowMillis) else calendarDateLabel(then, nowMillis, timeZoneId)
    val days = (minutes / (24 * 60)).toInt()
    return when {
        label == CalendarDateLabel.Yesterday -> CatalogueTimeAgo.Yesterday
        days < 7 -> CatalogueTimeAgo.Days(days.coerceAtLeast(2))
        label is CalendarDateLabel.Medium -> CatalogueTimeAgo.OnDate(withoutTrailingYear(label.text, yearOf(nowMillis)))
        else -> CatalogueTimeAgo.Days(days)
    }
}

/** "12 Mar 2026" and "Mar 12, 2026" lose the year; a date that does not end in it is left alone. */
internal fun withoutTrailingYear(date: String, year: Int): String =
    date.replace(Regex(",?\\s+$year$"), "").ifBlank { date }

/** The calendar year, in UTC: near midnight on New Year's Eve a date can keep or lose its year a few hours early. */
private fun yearOf(epochMillis: Long): Int = Instant.fromEpochMilliseconds(epochMillis).toString().substringBefore('-').toInt()

private const val MILLIS_PER_MINUTE = 60_000L
