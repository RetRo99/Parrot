package com.retro99.books.domain.model.links

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class LinkSuggestionsTest {

    private val now = Instant.parse("2026-10-01T12:00:00Z")

    @Test
    fun `normalizeTitle strips what differs between servers`() {
        // Given
        val cases = listOf(
            "01. The Hobbit (Unabridged)" to "the hobbit",
            "Hobbit, The" to "the hobbit",
            "Dune: Book 1" to "dune",
            "Pride & Prejudice [readaloud]" to "pride prejudice",
            "02 Emma" to "emma",
            "1) Emma" to "emma",
            "13 Reasons Why" to "13 reasons why",
            "1984" to "1984",
            "Les Misérables (Unabridged) (Dramatized) [2024]" to "les miserables",
            "Emma (a) (b) (c) (d)" to "emma a",
            "Wizard’s First Rule — Sword of Truth, Book 1" to "wizards first rule sword of truth",
            "Tale of Two Cities, A" to "a tale of two cities",
            "  Dune   Messiah  " to "dune messiah",
            "Straße, Die" to "strasse die",
        )

        cases.forEach { (title, expected) ->
            // When
            val normalized = normalizeTitle(title)

            // Then
            assertEquals(expected, normalized, "for '$title'")
        }
    }

    @Test
    fun `tokenSortSimilarity ignores word order`() {
        // When
        val reordered = tokenSortSimilarity("the hobbit", "hobbit the")
        val different = tokenSortSimilarity("dune", "dune messiah")
        val empty = tokenSortSimilarity("", "")

        // Then
        assertEquals(100.0, reordered)
        assertTrue(different < 75.0, "dune vs dune messiah scored $different")
        assertEquals(0.0, empty)
    }

    @Test
    fun `levenshtein counts single-character edits`() {
        // Given
        val cases = listOf(
            Triple("kitten", "sitting", 3),
            Triple("", "abc", 3),
            Triple("abc", "abc", 0),
            Triple("flaw", "lawn", 2),
        )

        cases.forEach { (first, second, expected) ->
            // When
            val distance = levenshtein(first, second)

            // Then
            assertEquals(expected, distance, "for $first and $second")
        }
    }

    @Test
    fun `bestAuthorSimilarity compares authors however they are written`() {
        // When
        val reversed = bestAuthorSimilarity(listOf("Tolkien, J. R. R."), listOf("J.R.R. Tolkien"))
        val oneOfMany = bestAuthorSimilarity(
            listOf("Neil Gaiman", "Terry Pratchett"),
            listOf("Terry Pratchett"),
        )
        val missing = bestAuthorSimilarity(emptyList(), listOf("Terry Pratchett"))

        // Then
        assertEquals(100.0, reversed)
        assertEquals(100.0, oneOfMany)
        assertNull(missing)
    }

    @Test
    fun `an ISBN-10 and its ISBN-13 give the same identifier`() {
        // Given
        val cases = listOf(
            "0-261-10221-4" to "9780261102217",
            "9780261102217" to "9780261102217",
            "978-0-261-10221-7" to "9780261102217",
            "080442957X" to "9780804429573",
            "12345" to null,
            "" to null,
        )

        cases.forEach { (isbn, expected) ->
            // When
            val normalized = normalizeIsbn(isbn)

            // Then
            assertEquals(expected, normalized, "for '$isbn'")
        }
    }

    @Test
    fun `scorePair follows the scoring rules`() {
        // Given
        val hobbit = candidate("storyteller:s1", "The Hobbit", "J. R. R. Tolkien")
        val cases = listOf(
            "the same source" to
                (hobbit to candidate("storyteller:s2", "The Hobbit", "J. R. R. Tolkien")) to null,
            "different languages" to
                (
                    hobbit.copy(language = "en") to
                        candidate("library:b1", "The Hobbit", "J. R. R. Tolkien", language = "de")
                    ) to null,
            "a regional variant of the same language" to
                (
                    hobbit.copy(language = "en-US") to
                        candidate("library:b1", "The Hobbit", "J. R. R. Tolkien", language = "en")
                    ) to SuggestionScore(100, SuggestionReason.TitleAndAuthor),
            "a language name and its code" to
                (
                    hobbit.copy(language = "German") to
                        candidate("library:b1", "The Hobbit", "J. R. R. Tolkien", language = "de")
                    ) to SuggestionScore(100, SuggestionReason.TitleAndAuthor),
            "a shared identifier" to
                (
                    hobbit.copy(identifiers = setOf("9780261102217")) to
                        candidate("library:b1", "Der kleine Hobbit", "Someone Else")
                            .copy(identifiers = setOf("9780261102217", "asin:B007978NPG"))
                    ) to SuggestionScore(100, SuggestionReason.IdentifierMatch),
            "the same title written differently" to
                (
                    hobbit to candidate(
                        key = "audiobookshelf:a1",
                        title = "Hobbit, The (Unabridged)",
                        author = "Tolkien, J.R.R.",
                    )
                    ) to SuggestionScore(100, SuggestionReason.TitleAndAuthor),
            "a strong title with a different author string is kept for review" to
                (
                    hobbit to candidate("audiobookshelf:a1", "The Hobbit", "Rob Inglis")
                    ) to SuggestionScore(72, SuggestionReason.TitleAndAuthor),
            "no author on one side scores on the title alone" to
                (hobbit to candidate("library:b1", "The Hobbit", author = null)) to
                SuggestionScore(100, SuggestionReason.TitleAndAuthor),
            "different books" to
                (hobbit to candidate("library:b1", "Dune Messiah", "Frank Herbert")) to null,
        )

        cases.forEach { (case, expected) ->
            val (name, pair) = case

            // When
            val score = scorePair(pair.first, pair.second)

            // Then
            assertEquals(expected, score, name)
        }
    }

    @Test
    fun `suggestLinks suggests likely pairs with the confident ones first`() {
        // Given
        val candidates = listOf(
            candidate("storyteller:s1", "The Hobbit", "J. R. R. Tolkien"),
            candidate("audiobookshelf:a1", "The Hobbit", "Rob Inglis"),
            candidate("library:b1", "Hobbit, The", "J. R. R. Tolkien"),
            candidate("library:b2", "Emma", "Jane Austen"),
        )

        // When
        val suggestions = suggestLinks(candidates, emptyList(), emptyMap(), now)

        // Then
        assertEquals(
            listOf(
                Triple("library:b1|storyteller:s1", 100, true),
                Triple("audiobookshelf:a1|library:b1", 72, false),
                Triple("audiobookshelf:a1|storyteller:s1", 72, false),
            ),
            suggestions.map { suggestion ->
                Triple(suggestion.pairKey, suggestion.score, suggestion.isConfident)
            },
        )
    }

    @Test
    fun `a pair is skipped when either copy is already linked into the other's source`() {
        // Given
        val candidates = listOf(
            candidate("storyteller:s1", "The Hobbit", "J. R. R. Tolkien"),
            candidate("storyteller:s2", "The Hobbit", "J. R. R. Tolkien"),
            candidate("library:b1", "The Hobbit", "J. R. R. Tolkien"),
            candidate("audiobookshelf:a1", "The Hobbit", "J. R. R. Tolkien"),
        )
        val links = listOf(testLink("link-1", "storyteller:s1", "library:b1"))

        // When
        val suggestions = suggestLinks(candidates, links, emptyMap(), now)

        // Then: nothing pairs library with Storyteller any more, in either direction
        assertEquals(
            listOf(
                "audiobookshelf:a1|library:b1",
                "audiobookshelf:a1|storyteller:s1",
                "audiobookshelf:a1|storyteller:s2",
            ),
            suggestions.map { suggestion -> suggestion.pairKey },
        )
    }

    @Test
    fun `decisions hide a pair for good or for thirty days`() {
        // Given
        val candidates = listOf(
            candidate("storyteller:s1", "The Hobbit", "J. R. R. Tolkien"),
            candidate("library:b1", "The Hobbit", "J. R. R. Tolkien"),
        )
        val pair = "library:b1|storyteller:s1"
        val cases = listOf(
            "never" to decision(pair, LinkDecisionType.Never, daysAgo = 400) to false,
            "skipped 10 days ago" to decision(pair, LinkDecisionType.Skip, daysAgo = 10) to false,
            "skipped 40 days ago" to decision(pair, LinkDecisionType.Skip, daysAgo = 40) to true,
        )

        cases.forEach { (case, expectedSuggested) ->
            val (name, decision) = case

            // When
            val suggestions = suggestLinks(candidates, emptyList(), mapOf(pair to decision), now)

            // Then
            assertEquals(expectedSuggested, suggestions.isNotEmpty(), name)
        }
    }

    @Test
    fun `books sharing no title token are never compared`() {
        // Given
        val candidates = listOf(
            candidate("storyteller:s1", "The Hobbit", "J. R. R. Tolkien"),
            candidate("library:b1", "Dune Messiah", "Frank Herbert"),
            candidate("audiobookshelf:a1", "An Emma of the Sea", "Jane Austen"),
            candidate("library:b2", "Emma", "Jane Austen"),
            candidate("storyteller:s2", "Hobbit", "J. R. R. Tolkien"),
        )
        val compared = mutableListOf<String>()

        // When
        suggestLinks(candidates, emptyList(), emptyMap(), now) { first, second ->
            compared += pairKey(first.key, second.key)
            null
        }

        // Then: only the pair sharing "emma"; the two Hobbits are both from Storyteller,
        // and "the", "an" and "of" never count as shared tokens
        assertEquals(listOf("audiobookshelf:a1|library:b2"), compared)
    }

    @Test
    fun `books with the same identifier are compared even when their titles differ`() {
        // Given
        val candidates = listOf(
            candidate("storyteller:s1", "HP1", author = null)
                .copy(identifiers = setOf("9780747532699")),
            candidate("library:b1", "Harry Potter and the Philosopher's Stone", author = null)
                .copy(identifiers = setOf("9780747532699")),
        )

        // When
        val suggestions = suggestLinks(candidates, emptyList(), emptyMap(), now)

        // Then
        assertEquals(SuggestionReason.IdentifierMatch, suggestions.single().reason)
    }

    @Test
    fun `toLinkCandidate reads identifiers from a book`() {
        // Given
        val serverBook = testServerBook(
            uuid = "s1",
            authors = listOf("J. R. R. Tolkien"),
            language = "en",
            isbn = "0-261-10221-4",
            asin = "b007978npg",
        )
        val libraryBook = testLibraryBook("b1", author = "J. R. R. Tolkien", isbn = "9780261102217")

        // When
        val server = serverBook.toLinkCandidate()
        val library = libraryBook.toLinkCandidate()

        // Then
        assertEquals(setOf("9780261102217", "asin:B007978NPG"), server.identifiers)
        assertEquals("en", server.language)
        assertEquals(listOf("J. R. R. Tolkien"), server.authors)
        assertEquals(setOf("9780261102217"), library.identifiers)
        assertNotNull(scorePair(server, library))
    }

    private fun candidate(
        key: String,
        title: String,
        author: String?,
        language: String? = null,
    ) = LinkCandidate(
        key = requireNotNull(CopyKey.parse(key)),
        title = title,
        authors = listOfNotNull(author),
        language = language,
        identifiers = emptySet(),
    )

    private fun decision(pairKey: String, type: LinkDecisionType, daysAgo: Int) = LinkDecision(
        pairKey = pairKey,
        type = type,
        decidedAt = now - daysAgo.days,
    )
}
