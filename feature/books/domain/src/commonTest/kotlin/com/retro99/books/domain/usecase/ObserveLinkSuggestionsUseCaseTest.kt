package com.retro99.books.domain.usecase

import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.links.BookLink
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.LinkDecision
import com.retro99.books.domain.model.links.LinkDecisionType
import com.retro99.books.domain.model.links.testLink
import com.retro99.server.api.ServerType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ObserveLinkSuggestionsUseCaseTest {

    private val links = MutableStateFlow(emptyList<BookLink>())
    private val decisions = MutableStateFlow(emptyMap<String, LinkDecision>())
    private val now = Instant.parse("2026-10-01T12:00:00Z")

    private val repository = object : BookLinksRepository {
        override fun observeLinks(): Flow<List<BookLink>> = links
        override fun observeDecisions(): Flow<Map<String, LinkDecision>> = decisions
        override suspend fun link(first: CopyKey, second: CopyKey): AppResult<BookLink> =
            error("not used")
        override suspend fun unlink(copy: CopyKey): CompletableResult = error("not used")
        override suspend fun decide(
            first: CopyKey,
            second: CopyKey,
            decision: LinkDecisionType,
        ): CompletableResult = error("not used")
    }

    @Test
    fun `suggestions follow the books, links and decisions`() = runTest {
        // Given
        val classUnderTest = useCase(StandardTestDispatcher(testScheduler))

        // When
        val initial = classUnderTest().first()
        decisions.value = mapOf(
            "audiobookshelf:a1|storyteller:s1" to LinkDecision(
                pairKey = "audiobookshelf:a1|storyteller:s1",
                type = LinkDecisionType.Never,
                decidedAt = now,
            ),
        )
        val afterDecision = classUnderTest().first()

        // Then
        assertEquals(
            listOf("audiobookshelf:a1|storyteller:s1"),
            initial.map { suggestion -> suggestion.pairKey },
        )
        assertEquals(emptyList(), afterDecision)
    }

    @Test
    fun `suggestions are computed once per library snapshot`() = runTest {
        // Given
        val classUnderTest = useCase(StandardTestDispatcher(testScheduler))

        // When: two collectors see the same library, then a link changes it
        classUnderTest().first()
        classUnderTest().first()
        val afterSameSnapshot = classUnderTest.computationCount
        links.value = listOf(testLink("link-1", "storyteller:s1", "audiobookshelf:a1"))
        val afterLink = classUnderTest().first()

        // Then
        assertEquals(1, afterSameSnapshot)
        assertEquals(2, classUnderTest.computationCount)
        assertEquals(emptyList(), afterLink)
    }

    private fun useCase(dispatcher: TestDispatcher) = ObserveLinkSuggestionsUseCase(
        getBooksUseCase = GetBooksUseCase(
            repositoryProvider = provider(
                repository("st-1", ServerType.Storyteller, "s1" to "Dune", "s2" to "Emma"),
                repository("abs-1", ServerType.Audiobookshelf, "a1" to "Dune (Unabridged)"),
            ),
            bookLinksRepository = repository,
        ),
        bookLinksRepository = repository,
    ).also { useCase ->
        useCase.dispatcher = dispatcher
        useCase.now = { now }
    }
}
