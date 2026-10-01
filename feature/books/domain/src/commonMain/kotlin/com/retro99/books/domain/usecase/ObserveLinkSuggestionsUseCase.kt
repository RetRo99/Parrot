package com.retro99.books.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.LinkCandidate
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkSuggestion
import com.retro99.books.domain.model.links.suggestLinks
import com.retro99.books.domain.model.links.toLinkCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Pairs of books on different sources that may be the same book, for the user to review.
 *
 * Scoring runs off the main thread and its result is kept per library snapshot (the books,
 * links and decisions), so the banner and the review screen share one computation and
 * nothing is recomputed until the library changes. It is a single instance for that reason.
 */
@Single
class ObserveLinkSuggestionsUseCase(
    private val getBooksUseCase: GetBooksUseCase,
    @Provided private val bookLinksRepository: BookLinksRepository,
) {
    /** Where suggestions are computed. Replaced in tests. */
    var dispatcher: CoroutineDispatcher = Dispatchers.Default

    /** The clock that decides when a skipped pair comes back. Replaced in tests. */
    var now: () -> Instant = { Clock.System.now() }

    /** How many times suggestions were computed rather than served from the cache. */
    var computationCount: Int = 0
        private set

    private val cacheMutex = Mutex()
    private var cachedSnapshot: Snapshot? = null
    private var cachedSuggestions: List<LinkSuggestion> = emptyList()

    operator fun invoke(): Flow<List<LinkSuggestion>> = combine(
        getBooksUseCase(groupLinked = false),
        bookLinksRepository.observeLinks(),
        bookLinksRepository.observeDecisions(),
    ) { booksResult, links, decisions ->
        val candidates = booksResult.getOrElse { emptyList() }
            .map { book -> book.toLinkCandidate() }
            .distinctBy { candidate -> candidate.key }
            .sortedBy { candidate -> candidate.key.value }
        // A skipped pair comes back after 30 days, so a snapshot is good for one day.
        Snapshot(candidates, links, decisions, day = now().toEpochMilliseconds() / DAY_MILLIS)
    }
        .distinctUntilChanged()
        .map { snapshot -> suggestionsFor(snapshot) }
        .flowOn(dispatcher)

    private suspend fun suggestionsFor(snapshot: Snapshot): List<LinkSuggestion> =
        cacheMutex.withLock {
            if (snapshot != cachedSnapshot) {
                cachedSuggestions = suggestLinks(
                    candidates = snapshot.candidates,
                    links = snapshot.links,
                    decisions = snapshot.decisions,
                    now = now(),
                )
                cachedSnapshot = snapshot
                computationCount++
            }
            cachedSuggestions
        }

    private data class Snapshot(
        val candidates: List<LinkCandidate>,
        val links: List<BookLink>,
        val decisions: Map<String, LinkDecision>,
        val day: Long,
    )

    private companion object {
        val DAY_MILLIS = 1.days.inWholeMilliseconds
    }
}
