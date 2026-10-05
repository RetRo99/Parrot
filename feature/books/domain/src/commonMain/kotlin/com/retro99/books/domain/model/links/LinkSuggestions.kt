package com.retro99.books.domain.model.links

import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.home
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** A book as the suggestion scoring sees it. */
data class LinkCandidate(
    val key: CopyKey,
    val title: String,
    val authors: List<String>,
    val language: String?,
    /** Normalized ISBN-13s, plus ASINs as `asin:<value>`. */
    val identifiers: Set<String>,
    /** Where the copy lives, for showing the suggestion. Scoring ignores it. */
    val home: BookHome = key.source.defaultHome(),
) {
    val source: CopySource get() = key.source
}

private fun CopySource.defaultHome(): BookHome = when (this) {
    CopySource.Library -> BookHome.ThisDevice
    CopySource.Storyteller -> BookHome.Storyteller
    CopySource.Audiobookshelf -> BookHome.Audiobookshelf
}

enum class SuggestionReason { IdentifierMatch, TitleAndAuthor }

data class SuggestionScore(val score: Int, val reason: SuggestionReason)

/** Two copies on different sources that may be the same book. The user decides. */
data class LinkSuggestion(
    val first: LinkCandidate,
    val second: LinkCandidate,
    val score: Int,
    val reason: SuggestionReason,
) {
    val pairKey: String get() = pairKey(first.key, second.key)

    val sharedIsbn: String? get() = first.identifiers.intersect(second.identifiers)
        .firstOrNull { normalizeIsbn(it) == it }
    val sharedAsin: String? get() = first.identifiers.intersect(second.identifiers)
        .firstOrNull { it.startsWith("asin:") && validAsin(it.removePrefix("asin:")) }

    /** Only verified ISBN matches are offered for bulk linking. */
    val isConfident: Boolean get() = sharedIsbn != null
}

const val CONFIDENT_SCORE = 90

private const val MIN_COMBINED_SCORE = 75
private const val MIN_TITLE_ONLY_SCORE = 85
private const val TITLE_WEIGHT = 0.7
private const val AUTHOR_WEIGHT = 0.3
private const val MIN_TOKEN_LENGTH = 3
private const val MAX_TRAILING_GROUPS = 3
private val SKIP_DURATION = 30.days
private val STOP_TOKENS = setOf("the", "and", "of", "a", "an")

private val LEADING_NUMBERING = Regex("^\\s*(0\\d{1,2}\\s+|\\d{1,3}[.)]\\s+)")
private val TRAILING_GROUP = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")
private val TRAILING_BOOK_NUMBER = Regex("[\\s,:;-]*\\bbook\\s+\\d+\\s*$")
private val TRAILING_ARTICLE = Regex("^(.*),\\s*(the|a|an)$")
private val LAST_FIRST = Regex("^([^,]+),([^,]+)$")
private val WHITESPACE = Regex("\\s+")

private val ACCENT_FOLDS = mapOf(
    "àáâãäåāăą" to "a",
    "çćĉċč" to "c",
    "ďđ" to "d",
    "èéêëēĕėęě" to "e",
    "ĝğġģ" to "g",
    "ìíîïĩīĭįı" to "i",
    "ñńņň" to "n",
    "òóôõöøōŏő" to "o",
    "śŝşš" to "s",
    "ţťŧ" to "t",
    "ùúûüũūŭůűų" to "u",
    "ýÿŷ" to "y",
    "źżž" to "z",
    "ł" to "l",
    "ŕŗř" to "r",
    "ß" to "ss",
    "æ" to "ae",
    "œ" to "oe",
).flatMap { (characters, replacement) ->
    characters.map { character -> character to replacement }
}.toMap()

private val LANGUAGE_CODES = mapOf(
    "english" to "en",
    "german" to "de",
    "deutsch" to "de",
    "french" to "fr",
    "spanish" to "es",
    "italian" to "it",
    "dutch" to "nl",
    "portuguese" to "pt",
    "polish" to "pl",
    "swedish" to "sv",
    "czech" to "cs",
    "chinese" to "zh",
    "slovenian" to "sl",
    "slovene" to "sl",
)

private fun String.foldAccents(): String = buildString {
    this@foldAccents.forEach { character -> append(ACCENT_FOLDS[character] ?: character) }
}

private fun String.straightenQuotesAndDashes(): String = this
    .replace('‘', '\'')
    .replace('’', '\'')
    .replace('“', '"')
    .replace('”', '"')
    .replace('‐', '-')
    .replace('‑', '-')
    .replace('–', '-')
    .replace('—', '-')
    .replace('−', '-')

/** Apostrophes vanish ("don't" is "dont"); other punctuation becomes a word break. */
private fun String.removePunctuation(): String = buildString {
    this@removePunctuation.forEach { character ->
        when {
            character.isLetterOrDigit() -> append(character)
            character == '\'' -> Unit
            else -> append(' ')
        }
    }
}

private fun String.collapseWhitespace(): String = replace(WHITESPACE, " ").trim()

/** A title reduced to what two servers would agree on. The steps and their order are fixed. */
fun normalizeTitle(title: String): String {
    var result = title.lowercase().foldAccents().straightenQuotesAndDashes()
    result = result.replace(LEADING_NUMBERING, "")
    repeat(MAX_TRAILING_GROUPS) { result = result.replace(TRAILING_GROUP, "") }
    result = result.replace(TRAILING_BOOK_NUMBER, "")
    TRAILING_ARTICLE.matchEntire(result.trim())?.let { match ->
        result = "${match.groupValues[2]} ${match.groupValues[1]}"
    }
    return result.removePunctuation().collapseWhitespace()
}

private fun normalizeAuthor(author: String): String {
    val lowered = author.lowercase().foldAccents().straightenQuotesAndDashes().trim()
    val firstLast = LAST_FIRST.matchEntire(lowered)?.let { match ->
        "${match.groupValues[2]} ${match.groupValues[1]}"
    } ?: lowered
    return firstLast.removePunctuation().collapseWhitespace()
}

fun levenshtein(first: String, second: String): Int {
    if (first.isEmpty()) return second.length
    if (second.isEmpty()) return first.length
    var previous = IntArray(second.length + 1) { index -> index }
    first.forEachIndexed { firstIndex, firstChar ->
        val current = IntArray(second.length + 1)
        current[0] = firstIndex + 1
        second.forEachIndexed { secondIndex, secondChar ->
            val substitution = previous[secondIndex] + if (firstChar == secondChar) 0 else 1
            current[secondIndex + 1] = minOf(
                previous[secondIndex + 1] + 1,
                current[secondIndex] + 1,
                substitution,
            )
        }
        previous = current
    }
    return previous[second.length]
}

/** 0..100: how alike two normalized strings are, ignoring word order. Two empties score 0. */
fun tokenSortSimilarity(first: String, second: String): Double {
    val sortedFirst = first.split(' ').sorted().joinToString(" ")
    val sortedSecond = second.split(' ').sorted().joinToString(" ")
    val longest = max(sortedFirst.length, sortedSecond.length)
    if (longest == 0) return 0.0
    return 100.0 * (1.0 - levenshtein(sortedFirst, sortedSecond).toDouble() / longest)
}

/** The best match over all author pairs, or null when either book names no author. */
fun bestAuthorSimilarity(first: List<String>, second: List<String>): Double? {
    val normalizedFirst = first.map { author -> normalizeAuthor(author) }
    val normalizedSecond = second.map { author -> normalizeAuthor(author) }
    return normalizedFirst.flatMap { one ->
        normalizedSecond.map { other -> tokenSortSimilarity(one, other) }
    }.maxOrNull()
}

/** The ISBN-13 for an ISBN-10 or ISBN-13, digits only. Null when it is neither. */
fun normalizeIsbn(value: String): String? {
    if (value.any { it !in '0'..'9' && it !in "xX- " }) return null
    val compact = value.filter { character -> character.isDigit() || character in "xX" }
        .uppercase()
    return when {
        compact.length == ISBN_13_LENGTH && (compact.startsWith("978") || compact.startsWith("979")) &&
            compact.all { character -> character.isDigit() } ->
            compact.takeIf { digits ->
                digits.mapIndexed { index, digit -> digit.digitToInt() * if (index % 2 == 0) 1 else 3 }.sum() % 10 == 0
            }
        compact.length == ISBN_10_LENGTH &&
            compact.dropLast(1).all { character -> character.isDigit() } -> {
            val checksum = compact.mapIndexed { index, character ->
                (if (character == 'X' && index == 9) 10 else character.digitToInt()) * (10 - index)
            }.sum()
            if (checksum % 11 != 0) return null
            val body = "978" + compact.dropLast(1)
            val sum = body.mapIndexed { index, character ->
                character.digitToInt() * if (index % 2 == 0) 1 else 3
            }.sum()
            body + (10 - sum % 10) % 10
        }
        else -> null
    }
}

private const val ISBN_13_LENGTH = 13
private const val ISBN_10_LENGTH = 10

private fun validAsin(value: String): Boolean =
    value.length == 10 && value.all { it in 'A'..'Z' || it in '0'..'9' }

/** Two-letter code for a language tag ("en-US") or a common English name ("German"). */
private fun languageCode(language: String): String {
    val lowered = language.trim().lowercase()
    return LANGUAGE_CODES[lowered] ?: lowered.take(2)
}

/** Returns 0..100, or null when the pair must not be suggested. */
fun scorePair(a: LinkCandidate, b: LinkCandidate): SuggestionScore? {
    if (a.source == b.source) return null
    if (a.language != null && b.language != null &&
        languageCode(a.language) != languageCode(b.language)
    ) {
        return null
    }
    val sharedIdentifier = a.identifiers.intersect(b.identifiers).any {
        normalizeIsbn(it) == it || (it.startsWith("asin:") && validAsin(it.removePrefix("asin:")))
    }
    if (sharedIdentifier) return SuggestionScore(100, SuggestionReason.IdentifierMatch)

    val titleScore = tokenSortSimilarity(normalizeTitle(a.title), normalizeTitle(b.title))
    val authorScore = bestAuthorSimilarity(a.authors, b.authors) // null if either is empty
    val score = if (authorScore == null) {
        titleScore
    } else {
        titleScore * TITLE_WEIGHT + authorScore * AUTHOR_WEIGHT
    }
    // Keep when the combined score is good, or when the title alone is very strong
    // (the author strings may just be formatted differently). Anything under 90 is only
    // reviewable, never "confident".
    if (score < MIN_COMBINED_SCORE && titleScore < MIN_TITLE_ONLY_SCORE) return null
    return SuggestionScore(score.roundToInt(), SuggestionReason.TitleAndAuthor)
}

/**
 * Pairs of copies on different sources that may be the same book, best first. Nothing is
 * linked here; the user approves each suggestion.
 *
 * A pair is left out when either copy is already linked to a copy from the other's source,
 * when the user said "not the same book", or when they skipped it less than 30 days ago.
 * Only books that share a title word or an identifier are compared.
 */
fun suggestLinks(
    candidates: List<LinkCandidate>,
    links: List<BookLink>,
    decisions: Map<String, LinkDecision>,
    now: Instant,
    score: (LinkCandidate, LinkCandidate) -> SuggestionScore? = ::scorePair,
): List<LinkSuggestion> {
    val unique = candidates.distinctBy { candidate -> candidate.key }
    val linkedSources = links
        .flatMap { link ->
            val sources = link.members.mapTo(mutableSetOf()) { member -> member.source }
            link.members.map { member -> member to sources }
        }
        .toMap()

    val seenPairs = mutableSetOf<String>()
    val suggestions = mutableListOf<LinkSuggestion>()
    blocks(unique).forEach { block ->
        block.forEachIndexed { index, one ->
            block.drop(index + 1).forEach { other ->
                val (first, second) = listOf(one, other).sortedBy { candidate ->
                    candidate.key.value
                }
                val pair = pairKey(first.key, second.key)
                if (first.source == second.source || !seenPairs.add(pair)) return@forEach
                if (repeatedMergeSource(first.key, second.key, links) != null) return@forEach
                if (second.source in linkedSources[first.key].orEmpty() ||
                    first.source in linkedSources[second.key].orEmpty()
                ) {
                    return@forEach
                }
                if (decisions[pair].hides(now)) return@forEach
                val result = score(first, second) ?: return@forEach
                suggestions += LinkSuggestion(first, second, result.score, result.reason)
            }
        }
    }
    return suggestions.sortedWith(
        compareByDescending<LinkSuggestion> { suggestion -> suggestion.score }
            .thenBy { suggestion -> suggestion.pairKey },
    )
}

private fun LinkDecision?.hides(now: Instant): Boolean = when (this?.type) {
    null -> false
    LinkDecisionType.Never -> true
    LinkDecisionType.Skip -> now - decidedAt < SKIP_DURATION
}

/**
 * Groups of candidates worth comparing: those sharing a title word (three letters or more,
 * not "the", "and", "of", "a", "an") or an identifier. This avoids comparing every pair.
 */
private fun blocks(candidates: List<LinkCandidate>): Collection<List<LinkCandidate>> {
    val index = mutableMapOf<String, MutableList<LinkCandidate>>()
    candidates.forEach { candidate ->
        val tokens = normalizeTitle(candidate.title).split(' ').filter { token ->
            token.length >= MIN_TOKEN_LENGTH && token !in STOP_TOKENS
        }
        val blockKeys = tokens.map { token -> "title:$token" } +
            candidate.identifiers.map { identifier -> "id:$identifier" }
        blockKeys.distinct().forEach { blockKey ->
            index.getOrPut(blockKey) { mutableListOf() } += candidate
        }
    }
    return index.values.filter { block -> block.size > 1 }
}

fun BookDomainModel.toLinkCandidate(): LinkCandidate = LinkCandidate(
    key = copyKey(),
    title = title,
    authors = when (this) {
        is BookDomainModel.LibraryBook -> listOfNotNull(author)
        is BookDomainModel.StorytellerBook -> authors.map { author -> author.name }
    },
    language = (this as? BookDomainModel.StorytellerBook)?.language,
    identifiers = setOfNotNull(
        isbn?.let { value -> normalizeIsbn(value) },
        asin?.trim()?.uppercase()?.takeIf(::validAsin)?.let { value -> "asin:$value" },
    ),
    home = home,
)
