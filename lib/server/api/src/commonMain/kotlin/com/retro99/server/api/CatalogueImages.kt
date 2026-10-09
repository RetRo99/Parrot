package com.retro99.server.api

import kotlin.io.encoding.Base64

/**
 * A picture from a catalogue, named by the catalogue it belongs to. Screens hand this to the
 * image loader, never the bare address: the loader then asks that catalogue's own session for
 * the bytes, so the request follows the catalogue's rules and no library server's token can
 * be attached to it (plan §10.7).
 *
 * [url] can hold a key in its path and stays out of logs and navigation state.
 */
data class CatalogueImageModel(val sourceId: String, val url: String) {
    /** A `data:` picture carried inside the catalogue page. It is decoded, never requested. */
    val isInline: Boolean get() = url.startsWith("data:", ignoreCase = true)

    override fun toString() = "CatalogueImageModel(redacted)"
}

object CatalogueImageLimits {
    /** Ceiling for one picture fetched from a catalogue. */
    const val MAX_IMAGE_BYTES: Long = 4L * 1024 * 1024 // 4 MiB

    /** Ceiling for one decoded `data:` picture. Thumbnails only; a page is at most a few MiB. */
    const val MAX_INLINE_IMAGE_BYTES: Int = 256 * 1024 // 256 KiB
}

/** A catalogue session that can fetch pictures. Implemented next to [ServerCatalogueRepository]. */
interface CatalogueImageRepository {
    /**
     * The bytes behind [url], fetched with the same redirect, origin and account rules as a
     * catalogue page and at most [CatalogueImageLimits.MAX_IMAGE_BYTES] long. Null for any
     * failure; the reason is never needed to draw a placeholder. Nothing is cached.
     */
    suspend fun loadImage(url: String): ByteArray?
}

private val RASTER_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/**
 * The media type of [bytes] when they start like a PNG, JPEG, GIF or WebP file, else null.
 * The catalogue's own label is not trusted: SVG, HTML and everything else is refused here.
 */
fun catalogueRasterImageType(bytes: ByteArray): String? {
    fun startsWith(vararg signature: Int) =
        bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it].toByte() }
    fun textAt(offset: Int, text: String) =
        bytes.size >= offset + text.length && text.indices.all { bytes[offset + it] == text[it].code.toByte() }
    return when {
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        textAt(0, "GIF87a") || textAt(0, "GIF89a") -> "image/gif"
        textAt(0, "RIFF") && textAt(8, "WEBP") -> "image/webp"
        else -> null
    }
}

/**
 * Decodes a base64 `data:` picture. Null unless it is labelled as one of the four raster types,
 * its bytes are of that type, and it is no longer than
 * [CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES].
 */
fun decodeCatalogueDataImage(uri: String): ByteArray? {
    if (!uri.startsWith("data:", ignoreCase = true)) return null
    // Four base64 characters carry three bytes; checked before anything is copied or decoded.
    val maxChars = "data:".length + MAX_DATA_HEADER_CHARS + (CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES + 2) / 3 * 4
    if (uri.length > maxChars) return null
    val comma = uri.indexOf(',')
    if (comma < 0 || comma > "data:".length + MAX_DATA_HEADER_CHARS) return null
    val header = uri.substring("data:".length, comma).split(';').map { it.trim().lowercase() }
    if (header.first() !in RASTER_TYPES || header.last() != "base64" || header.size < 2) return null
    val bytes = try {
        Base64.Default.decode(uri.substring(comma + 1))
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.isEmpty() || bytes.size > CatalogueImageLimits.MAX_INLINE_IMAGE_BYTES) return null
    return bytes.takeIf { catalogueRasterImageType(it) == header.first() }
}

private const val MAX_DATA_HEADER_CHARS = 64
