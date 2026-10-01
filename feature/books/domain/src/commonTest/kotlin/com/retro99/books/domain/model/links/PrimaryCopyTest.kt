package com.retro99.books.domain.model.links

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.server.api.ServerType
import kotlin.test.Test
import kotlin.test.assertEquals

class PrimaryCopyTest {

    private val parrot = testLibraryBook("b1", inParrot = true)
    private val device = testLibraryBook("b2", inParrot = false)
    private val storyteller = testServerBook("s1")
    private val storytellerTwin = testServerBook("s0")
    private val audiobookshelf = testServerBook("a1", ServerType.Audiobookshelf)

    private data class Case(
        val name: String,
        val copies: List<BookDomainModel>,
        val lastOpened: Map<CopyKey, Long> = emptyMap(),
        val currentlyReading: CopyKey? = null,
        val expected: BookDomainModel,
    )

    @Test
    fun `choosePrimary follows the primary copy rules in order`() {
        // Given
        val cases = listOf(
            Case(
                name = "the copy being read wins over a more recently opened one",
                copies = listOf(parrot, storyteller, audiobookshelf),
                lastOpened = mapOf(storyteller.copyKey() to 900L),
                currentlyReading = audiobookshelf.copyKey(),
                expected = audiobookshelf,
            ),
            Case(
                name = "the most recently opened copy wins",
                copies = listOf(parrot, storyteller, audiobookshelf),
                lastOpened = mapOf(
                    storyteller.copyKey() to 900L,
                    audiobookshelf.copyKey() to 100L,
                ),
                expected = storyteller,
            ),
            Case(
                name = "a copy that is being read elsewhere is ignored",
                copies = listOf(storyteller, audiobookshelf),
                currentlyReading = CopyKey(CopySource.Library, "other"),
                expected = storyteller,
            ),
            Case(
                name = "never opened: Parrot Cloud first",
                copies = listOf(audiobookshelf, storyteller, device, parrot),
                expected = parrot,
            ),
            Case(
                name = "never opened: this device before servers",
                copies = listOf(audiobookshelf, storyteller, device),
                expected = device,
            ),
            Case(
                name = "never opened: Storyteller before Audiobookshelf",
                copies = listOf(audiobookshelf, storyteller),
                expected = storyteller,
            ),
            Case(
                name = "same home: the smaller key wins, whatever the order",
                copies = listOf(storyteller, storytellerTwin),
                expected = storytellerTwin,
            ),
            Case(
                name = "opened at the same moment: falls back to the home order",
                copies = listOf(audiobookshelf, storyteller),
                lastOpened = mapOf(
                    storyteller.copyKey() to 500L,
                    audiobookshelf.copyKey() to 500L,
                ),
                expected = storyteller,
            ),
        )

        cases.forEach { case ->
            // When
            val primary = choosePrimary(case.copies, case.lastOpened, case.currentlyReading)

            // Then
            assertEquals(case.expected, primary, case.name)
        }
    }
}
