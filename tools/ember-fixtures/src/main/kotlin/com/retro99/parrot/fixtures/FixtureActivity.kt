package com.retro99.parrot.fixtures

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.retro99.base.ui.compose.EmberMode
import com.retro99.base.ui.compose.ParrotTheme
import com.retro99.base.ui.IntentDispatcher
import com.retro99.books.domain.model.*
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.copyKey
import com.retro99.books.ui.model.toUiModel
import com.retro99.books.ui.series.*
import com.retro99.books.ui.series.detail.*
import com.retro99.books.ui.series.model.toListUiModel
import com.retro99.server.api.ServerBook
import com.retro99.server.api.ServerBookSeries
import com.retro99.server.api.ServerType

/** In-memory test profile. Only production screen content and domain aggregation are reused. */
class FixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = when (intent.getStringExtra("theme")) {
            "eink" -> EmberMode.Eink
            "night" -> EmberMode.Night
            else -> EmberMode.Day
        }
        val empty = intent.getBooleanExtra("empty", false)
        setContent {
            ParrotTheme(mode) {
                var selected by rememberSaveable { mutableStateOf(intent.getStringExtra("series")) }
                val longList by rememberSaveable { mutableStateOf(intent.getBooleanExtra("longlist", false)) }
                var offline by rememberSaveable { mutableStateOf(intent.getBooleanExtra("offline", false)) }
                var searchVisible by rememberSaveable { mutableStateOf(false) }
                val savedScreens = rememberSaveableStateHolder()
                val series = remember { if (empty) emptyList() else seededSeries(longList) }
                val current = series.find { it.name == selected }
                BackHandler(current != null) { selected = null }
                savedScreens.SaveableStateProvider(current?.key ?: "browse") {
                if (current == null) {
                    SeriesListScreenContent(SeriesListViewState(
                        series = series.map { it.toListUiModel() }, isLoading = false,
                        failedSources = if (offline) listOf(SeriesFailureUiModel("storyteller", "Storyteller")) else emptyList(),
                        hasConnectedServer = !empty,
                    ), IntentDispatcher { event -> when (event) {
                        is SeriesListIntent.OnSeriesClicked -> selected = event.series.name
                        SeriesListIntent.OnRefresh -> offline = false
                        else -> Unit
                    } })
                } else {
                    SeriesDetailScreenContent(SeriesDetailViewState(
                        seriesName = current.name, isLoading = false,
                        isSearchVisible = searchVisible,
                        rows = current.books.map { SeriesDetailRow(it.key, it.book.toUiModel(), it.position, it.progress) },
                        finishedCount = current.finishedCount, inProgressCount = current.inProgressCount,
                        progress = current.progress,
                        failedSources = if (offline) listOf(SeriesFailureUiModel("storyteller", "Storyteller")) else emptyList(),
                    ), IntentDispatcher { event -> when (event) {
                        SeriesDetailIntent.OnBackClicked -> selected = null
                        SeriesDetailIntent.OnRefresh -> offline = false
                        SeriesDetailIntent.OnSearchToggled -> searchVisible = !searchVisible
                        else -> Unit
                    } })
                }
                }
            }
        }
    }
}

private fun seededSeries(longList: Boolean = false): List<SeriesBrowseEntry> {
    fun book(id: String, title: String, number: Double?, source: String = "storyteller", series: String = "The Salt Roads", audio: Boolean = false): BookDomainModel = ServerBook(
        uuid = id, serverId = source, title = title, description = null, coverUrl = null,
        authors = listOf("Ines Varga"), narrators = emptyList(), tags = emptyList(),
        series = listOf(ServerBookSeries("$source-series", series, number?.toFloat())),
        hasEbook = !audio, hasAudiobook = audio, hasReadaloud = false,
        serverType = if (source == "abs") ServerType.Audiobookshelf else ServerType.Storyteller,
    ).toBookDomainModel()
    fun entry(book: BookDomainModel, progress: Double?) = BookWithProgressDomainModel(book,
        progress?.let { BookProgressInfoDomainModel(book.uuid, it, null, false, false, false) })
    val salt = book("salt", "Salt and Stone", 1.0)
    val lantern = book("lantern", "The Lantern Ferry", 2.0)
    val linked = book("lantern-abs", "The Lantern Ferry", 2.0, "abs", " the SALT roads ", true)
    val harbour = (book("harbour", "The Harbour Master", 3.0, audio = true) as BookDomainModel.StorytellerBook).let {
        it.copy(series = it.series + SeriesDomainModel("harbour-series", "Harbour Mysteries", null, 1.0, null, null))
    }
    val copies = listOf(entry(salt, 1.0), entry(lantern, .62), entry(linked, .43),
        entry(book("low", "Low Water", 2.5), null), entry(harbour, null),
        entry(book("tales", "Tales from the Salt Roads", null), null),
        entry(book("winter", "Winter Orchard", 1.0, series = "Orchard Quartet"), .87),
        entry(book("spring", "Spring Orchard", 2.0, "abs", "Orchard Quartet"), 1.0))
    val catalogue = listOf(
        SeriesDomainModel("st-salt", "The Salt Roads", null, null, null, null, listOf(SeriesSource("storyteller", "st-salt"))),
        SeriesDomainModel("abs-salt", " the SALT roads ", null, null, null, null, listOf(SeriesSource("abs", "abs-salt"))),
    )
    val extra = if (longList) List(24) { index ->
        entry(book("extra-$index", "Fixture book $index", 1.0, series = "Series ${index.toString().padStart(2, '0')}"), null)
    } else emptyList()
    return buildSeriesBrowse(catalogue, copies + extra, listOf(BookLink("fixture-link", setOf(lantern.copyKey(), linked.copyKey()))))
}
