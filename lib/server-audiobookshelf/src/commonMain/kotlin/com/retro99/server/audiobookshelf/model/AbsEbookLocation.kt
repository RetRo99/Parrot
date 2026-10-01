package com.retro99.server.audiobookshelf.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Audiobookshelf stores `ebookLocation` as an opaque string written by whichever reader saved
 * it: an EPUB CFI from its web reader (epub.js), a JSON Readium locator from its mobile apps,
 * or, from older versions of this app, a bare chapter href.
 */
enum class AbsLocationShape { ReadiumJson, Cfi, Href, Empty, Unknown }

/** What a stored `ebookLocation` says, in our position's terms. */
data class ParsedAbsLocation(
    val shape: AbsLocationShape,
    /** A chapter of the publication's reading order; null when it can't be known. */
    val href: String? = null,
    val type: String? = null,
    val progression: Double? = null,
    val totalProgression: Double? = null,
    val cssSelector: String? = null,
    /** 0-based spine item of a CFI. */
    val spineIndex: Int? = null,
)

/** Our ebook position, as needed to write an `ebookLocation`. */
data class AbsEbookPlace(
    val href: String?,
    val type: String?,
    val title: String?,
    val progression: Double?,
    val totalProgression: Double?,
    val cssSelector: String?,
    /** The chapter's reading-order index, when known; used when the book isn't on this device. */
    val chapterIndex: Int?,
)

private val json = Json { ignoreUnknownKeys = true }

private val cfiSpineStep = Regex("""^epubcfi\(/6/(\d+)""")

fun isAbsCfi(raw: String?): Boolean = raw?.trim()?.startsWith(CFI_PREFIX) == true

/**
 * Reads [raw] by its shape. A CFI's spine step is resolved to a chapter through the book's
 * full spine ([readingOrderHrefs]: every itemref, non-linear ones included, as CFIs count
 * them; null when the book isn't on this device); the element
 * path inside the chapter isn't resolved yet, so [ebookProgress] stands in for the place.
 */
suspend fun parseAbsEbookLocation(
    raw: String?,
    ebookProgress: Double?,
    readingOrderHrefs: suspend () -> List<String>?,
): ParsedAbsLocation {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) {
        return ParsedAbsLocation(AbsLocationShape.Empty, totalProgression = ebookProgress)
    }
    if (value.startsWith("{")) {
        return parseReadiumJson(value, ebookProgress)
            ?: ParsedAbsLocation(AbsLocationShape.Unknown, totalProgression = ebookProgress)
    }
    if (isAbsCfi(value)) {
        val spineIndex = cfiSpineStep.find(value)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?.takeIf { step -> step >= 2 && step % 2 == 0 }
            ?.let { step -> step / 2 - 1 }
        val href = spineIndex?.let { index -> readingOrderHrefs()?.getOrNull(index) }
        return ParsedAbsLocation(
            shape = AbsLocationShape.Cfi,
            href = href,
            totalProgression = ebookProgress,
            spineIndex = spineIndex,
        )
    }
    if (value.any { char -> char.isWhitespace() }) {
        return ParsedAbsLocation(AbsLocationShape.Unknown, totalProgression = ebookProgress)
    }
    return ParsedAbsLocation(AbsLocationShape.Href, href = value, totalProgression = ebookProgress)
}

/**
 * The `ebookLocation` to write for [place], in the shape already stored ([storedRaw]): "the
 * reader that wrote it is the reader that will read it back". A JSON locator is mirrored as
 * JSON; anything else (a CFI, nothing, an old bare href) becomes a CFI at the chapter start,
 * which keeps the web reader working. Null when no valid value can be built; a bare href is
 * never returned.
 */
suspend fun buildAbsEbookLocation(
    place: AbsEbookPlace,
    storedRaw: String?,
    readingOrderHrefs: suspend () -> List<String>?,
): String? {
    val href = place.href?.substringBefore('#')?.takeIf { value -> value.isNotBlank() }
        ?: return null
    if (isAbsCfi(href) || href.startsWith("{")) return null
    if (storedRaw?.trim()?.let { value -> parseReadiumJson(value, null) } != null) {
        return readiumJson(place, href)
    }
    val readingOrder = readingOrderHrefs()
    val spineIndex = if (readingOrder != null) {
        readingOrder.indexOfFirst { candidate -> sameHref(candidate, href) }
    } else {
        place.chapterIndex
    }
    return spineIndex?.takeIf { index -> index >= 0 }?.let(::chapterStartCfi)
}

/** The start of spine item [spineIndex] (0-based): `/6` is the spine, `!/4` the body. */
fun chapterStartCfi(spineIndex: Int): String = "$CFI_PREFIX/6/${2 * (spineIndex + 1)}!/4)"

private fun parseReadiumJson(value: String, ebookProgress: Double?): ParsedAbsLocation? {
    val root = runCatching { json.parseToJsonElement(value) }.getOrNull() as? JsonObject
        ?: return null
    val href = root.string("href")?.takeIf { text -> text.isNotBlank() } ?: return null
    val locations = root["locations"] as? JsonObject
    val otherLocations = locations?.get("otherLocations") as? JsonObject
    return ParsedAbsLocation(
        shape = AbsLocationShape.ReadiumJson,
        href = href,
        type = root.string("type"),
        progression = locations?.double("progression"),
        totalProgression = locations?.double("totalProgression") ?: ebookProgress,
        cssSelector = locations?.string("cssSelector")
            ?: otherLocations?.string("cssSelector")
            ?: root.string("cssSelector"),
    )
}

private fun readiumJson(place: AbsEbookPlace, href: String): String = buildJsonObject {
    put("href", href)
    put("type", place.type ?: XHTML_TYPE)
    place.title?.let { title -> put("title", title) }
    put(
        "locations",
        buildJsonObject {
            place.progression?.let { value -> put("progression", value) }
            place.totalProgression?.let { value -> put("totalProgression", value) }
            place.cssSelector?.let { value -> put("cssSelector", value) }
        },
    )
}.toString()

private fun sameHref(first: String, second: String): Boolean =
    first.substringBefore('#').trimStart('/') == second.substringBefore('#').trimStart('/')

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.contentOrNull

private fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

private const val CFI_PREFIX = "epubcfi("
private const val XHTML_TYPE = "application/xhtml+xml"
