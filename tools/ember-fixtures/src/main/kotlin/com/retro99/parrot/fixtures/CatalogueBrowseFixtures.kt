package com.retro99.parrot.fixtures

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.catalogue.ui.browse.CatalogueBookRow
import com.retro99.catalogue.ui.browse.CatalogueBrowseActions
import com.retro99.catalogue.ui.browse.CatalogueBrowseContent
import com.retro99.catalogue.ui.browse.CatalogueBrowseContentScreen
import com.retro99.catalogue.ui.browse.CatalogueBrowseState
import com.retro99.catalogue.ui.browse.CatalogueFilterChip
import com.retro99.catalogue.ui.browse.CatalogueFilterOption
import com.retro99.catalogue.ui.browse.CatalogueFilterSheet
import com.retro99.catalogue.ui.browse.CatalogueFolderRow
import com.retro99.catalogue.ui.browse.CataloguePageFailure
import com.retro99.catalogue.ui.browse.CataloguePaging
import com.retro99.catalogue.ui.browse.CatalogueShelf
import com.retro99.catalogue.ui.browse.CatalogueTellingLine
import com.retro99.catalogue.ui.downloads.ListDownloadState

/**
 * The catalogue browser's boards (design/ember/catalogues/screens/opds-browse … editionsList),
 * drawn by the production screen from states built here. The rows have no download button,
 * progress or cancel yet: those come with the downloads run.
 */
@Composable
fun CatalogueBrowseBoard(view: String) {
    CatalogueBrowseContentScreen(
        state = browseState(view),
        actions = CatalogueBrowseActions(),
        modifier = Modifier.fillMaxSize(),
        nowEpochMillis = FIXTURE_NOW,
        deviceName = "this phone",
    )
}

private const val FIXTURE_NOW = 1_791_547_200_000L
private const val GUTENBERG = "Project Gutenberg"

private fun row(title: String, author: String?, inLibrary: Boolean = false, telling: CatalogueTellingLine? = null) =
    CatalogueBookRow(key = "$title-$telling", title = title, author = author, cover = null, inLibrary = inLibrary, telling = telling)

private val root = CatalogueBrowseContent.Loaded(
    shelves = listOf(
        CatalogueShelf(
            "popular", "Popular this week", hasSeeAll = true,
            books = listOf(row("Frankenstein", "Mary Shelley"), row("Pride and Prejudice", "Jane Austen"), row("Moby Dick", "Herman Melville"), row("Dracula", "Bram Stoker")),
        ),
    ),
    folders = listOf(
        CatalogueFolderRow("latest", "Latest additions", null),
        CatalogueFolderRow("popular", "Most popular", null),
        CatalogueFolderRow("subject", "By subject", "Adventure, History, Poetry…"),
        CatalogueFolderRow("language", "By language", null),
        CatalogueFolderRow("author", "By author", null),
    ),
)

private val chips = listOf(CatalogueFilterChip(0, "Language", "English"), CatalogueFilterChip(1, "Sort", "Most popular"))
private val popular = listOf(
    row("Treasure Island", "Robert Louis Stevenson", inLibrary = true),
    row("The War of the Worlds", "H. G. Wells"),
    row("A Very Long Title That Goes On: Being an Account of Everything That Happened", null),
    row("Middlemarch", "George Eliot"),
    row("The Count of Monte Cristo", "Alexandre Dumas"),
)

private fun failed(reason: CataloguePageFailure) =
    CatalogueBrowseState(catalogueName = GUTENBERG, title = "By subject", content = CatalogueBrowseContent.Failed(reason))

private fun browseState(view: String): CatalogueBrowseState = when (view) {
    "listStates" -> CatalogueBrowseState(catalogueName = GUTENBERG, title = "Popular", content = CatalogueBrowseContent.Loaded(books = listOf(
        row("The War of the Worlds", "H. G. Wells").copy(download = ListDownloadState.GettingReady),
        row("Middlemarch", "George Eliot").copy(download = ListDownloadState.Downloading(420_000, 1_000_000)),
        row("The Time Machine", "H. G. Wells").copy(download = ListDownloadState.Downloading(1_400_000, null)),
        row("Emma", "Jane Austen").copy(download = ListDownloadState.Waiting),
        row("Dracula", "Bram Stoker").copy(download = ListDownloadState.Adding),
        row("Treasure Island", "Robert Louis Stevenson", inLibrary = true),
    )))
    "browse" -> CatalogueBrowseState(catalogueName = GUTENBERG, searchAvailable = true, content = root)
    "localNet" -> CatalogueBrowseState(catalogueName = GUTENBERG, searchAvailable = true, content = root, localNetworkHost = "192.168.1.20")
    "browsePlain" -> CatalogueBrowseState(
        catalogueName = "Home Calibre",
        content = CatalogueBrowseContent.Loaded(
            folders = listOf("Fiction", "Non-fiction", "Recently added", "Unsorted imports from the old laptop, 2019 to 2021, not yet checked", "All books")
                .map { CatalogueFolderRow(it, it, null) },
        ),
    )
    "firstLoad" -> CatalogueBrowseState(catalogueName = GUTENBERG)
    "emptyFolder" -> CatalogueBrowseState(catalogueName = GUTENBERG, title = "Poetry", content = CatalogueBrowseContent.EmptyFolder)
    "limited" -> CatalogueBrowseState(catalogueName = GUTENBERG, content = CatalogueBrowseContent.RateLimited)
    "offline" -> CatalogueBrowseState(
        catalogueName = GUTENBERG, title = "Popular",
        content = CatalogueBrowseContent.Loaded(chips = chips, books = popular.take(4), savedCopyAt = FIXTURE_NOW - 2 * 60 * 60 * 1000),
    )
    "offlineNone" -> CatalogueBrowseState(catalogueName = GUTENBERG, title = "By subject", content = CatalogueBrowseContent.OfflineNone)
    // E-ink shows "Load more" here: it never loads by itself.
    "list" -> CatalogueBrowseState(catalogueName = GUTENBERG, title = "Popular", content = CatalogueBrowseContent.Loaded(chips = chips, books = popular, more = CataloguePaging.Auto))
    "listEink" -> CatalogueBrowseState(catalogueName = GUTENBERG, title = "Popular", content = CatalogueBrowseContent.Loaded(chips = chips, books = popular, more = CataloguePaging.Button))
    "listFailed" -> CatalogueBrowseState(catalogueName = GUTENBERG, title = "Popular", content = CatalogueBrowseContent.Loaded(chips = chips, books = popular.take(4), more = CataloguePaging.Failed))
    "search" -> CatalogueBrowseState(
        catalogueName = GUTENBERG, searchAvailable = true, searchText = "whale", searchQuery = "whale",
        content = CatalogueBrowseContent.Loaded(
            books = listOf(
                row("Moby Dick; Or, The Whale", "Herman Melville"),
                row("The Whale and the Reactor", null),
                row("Whale Fishing in the South Seas", "Frank T. Bullen", inLibrary = true),
                row("A Whaleman’s Log", null),
            ),
        ),
    )
    "noResults" -> CatalogueBrowseState(catalogueName = GUTENBERG, searchAvailable = true, searchText = "whalle", searchQuery = "whalle", content = CatalogueBrowseContent.NoResults("whalle"))
    "filter" -> CatalogueBrowseState(
        catalogueName = GUTENBERG, title = "Popular",
        content = CatalogueBrowseContent.Loaded(chips = chips, books = popular),
        filterSheet = CatalogueFilterSheet(
            groupIndex = 0, group = "Language", optionCount = 54, searchable = true, searchText = "",
            options = listOf("Any language" to null, "English" to 41_208L, "French" to 3_982L, "German" to 2_415L, "Finnish" to 2_301L, "Dutch" to 1_047L, "Italian" to 1_006L, "Spanish" to 829L, "Portuguese" to 612L)
                .mapIndexed { index, (title, count) -> CatalogueFilterOption(index, title, count, selected = title == "English") },
        ),
    )
    "editionsList" -> CatalogueBrowseState(
        catalogueName = GUTENBERG, title = "Treasure Island",
        content = CatalogueBrowseContent.Loaded(
            sameBookCount = 3,
            books = listOf(
                row("Treasure Island", "Robert Louis Stevenson", telling = CatalogueTellingLine("Illustrated edition", "1911", 2)),
                row("Treasure Island", "Robert Louis Stevenson", telling = CatalogueTellingLine(null, "2004", 1)),
                row("Treasure Island", "Robert Louis Stevenson", inLibrary = true, telling = CatalogueTellingLine(null, null, 3)),
            ),
        ),
    )
    "failedTimedOut" -> failed(CataloguePageFailure.TimedOut)
    "failedCatalogueError" -> failed(CataloguePageFailure.CatalogueError)
    "failedNotAllowed" -> failed(CataloguePageFailure.NotAllowed)
    "failedNotFound" -> failed(CataloguePageFailure.NotFound)
    "failedTooLarge" -> failed(CataloguePageFailure.TooLarge)
    "failedNotACatalogue" -> failed(CataloguePageFailure.NotACatalogue)
    "failedCertificate" -> failed(CataloguePageFailure.Certificate)
    else -> error("Unknown browse board $view")
}
