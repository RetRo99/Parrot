package com.retro99.books.domain.model

import com.retro99.base.result.AppError
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.choosePrimary
import com.retro99.books.domain.model.links.copyKey
import kotlin.time.Instant

/** A catalogue id is meaningful only within its server. */
data class SeriesSource(val serverId: String, val uuid: String)
data class SeriesSourceFailure(val serverId: String, val error: AppError)

data class SeriesBrowseBook(
    val key: String,
    val book: BookDomainModel,
    val position: Double?,
    val progress: Double?,
    val sources: List<SeriesSource>,
) {
    val finished: Boolean get() = progress != null && progress >= 1.0
    val inProgress: Boolean get() = progress != null && progress > 0.0 && !finished
}

data class SeriesBrowseEntry(
    val key: String,
    val name: String,
    val sources: List<SeriesSource>,
    val books: List<SeriesBrowseBook>,
) {
    val finishedCount: Int get() = books.count { it.finished }
    val inProgressCount: Int get() = books.count { it.inProgress }
    val progress: Double get() = if (books.isEmpty()) 0.0 else
        books.sumOf { it.progress?.coerceIn(0.0, 1.0) ?: 0.0 } / books.size
}

fun normalisedSeriesName(name: String): String = name.trim().lowercase()
    .replace(Regex("\\s+"), " ")

/** Group identity first, then inspect every membership; a library primary has no series. */
fun buildSeriesBrowse(
    catalogue: List<SeriesDomainModel>,
    entries: List<BookWithProgressDomainModel>,
    links: List<BookLink>,
): List<SeriesBrowseEntry> {
    val linkByMember = links.flatMap { link -> link.members.map { it to link.linkId } }.toMap()
    val groups = entries.groupBy { entry ->
        linkByMember[entry.book.copyKey()]?.let { "link:$it" }
            ?: "source:${entry.book.serverId}:${entry.book.uuid}"
    }
    val allSeries = catalogue + entries.flatMap { it.book.series }
    return allSeries.groupBy { normalisedSeriesName(it.name) }.filterKeys { it.isNotBlank() }
        .map { (nameKey, records) ->
            val books = groups.mapNotNull { (key, copies) ->
                val memberships = copies.flatMap { entry -> entry.book.series
                    .filter { normalisedSeriesName(it.name) == nameKey }
                    .map { entry.book.serverId to it } }
                if (memberships.isEmpty()) return@mapNotNull null
                val lastOpened = copies.mapNotNull { entry ->
                    val value = when (val book = entry.book) {
                        is BookDomainModel.LibraryBook -> book.lastOpenedAt
                        is BookDomainModel.StorytellerBook -> book.lastOpenedAt
                    }
                    (entry.lastOpenedMillis ?: value?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() })?.let { entry.book.copyKey() to it }
                }.toMap()
                val primary = choosePrimary(copies.map { it.book }, lastOpened, copies.firstOrNull { it.currentlyReading }?.book?.copyKey())
                val selected = copies.first { it.book.serverId == primary.serverId && it.book.uuid == primary.uuid }
                SeriesBrowseBook(
                    key = key,
                    book = primary,
                    position = memberships.sortedWith(compareBy({ it.first }, { it.second.uuid }))
                        .firstOrNull { it.second.position != null }?.second?.position,
                    progress = selected.progressInfo?.displayProgression,
                    sources = memberships.map { SeriesSource(it.first, it.second.uuid) }.distinct(),
                )
            }.sortedWith(compareBy({ it.position ?: Double.MAX_VALUE }, { it.book.title.lowercase() }, { it.key }))
            SeriesBrowseEntry(
                key = "series:$nameKey",
                name = records.map { it.name.trim() }.sorted().first(),
                sources = (records.flatMap { it.sources } + books.flatMap { it.sources }).distinct(),
                books = books,
            )
        }.sortedBy { normalisedSeriesName(it.name) }
}
