package com.retro99.books.data

/**
 * Metadata extracted from an EPUB file.
 */
data class EpubMetadata(
    val title: String,
    val author: String?,
    val description: String?,
    val coverBytes: ByteArray?,
    val hasMediaOverlays: Boolean,
    val publicationDate: String?,
    /** The file's ISBN, when its `dc:identifier` is one. Digits, with `X` allowed last. */
    val isbn: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as EpubMetadata

        if (title != other.title) return false
        if (author != other.author) return false
        if (description != other.description) return false
        if (hasMediaOverlays != other.hasMediaOverlays) return false
        if (publicationDate != other.publicationDate) return false
        if (isbn != other.isbn) return false
        if (coverBytes != null) {
            if (other.coverBytes == null) return false
            if (!coverBytes.contentEquals(other.coverBytes)) return false
        } else if (other.coverBytes != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + (author?.hashCode() ?: 0)
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + hasMediaOverlays.hashCode()
        result = 31 * result + (publicationDate?.hashCode() ?: 0)
        result = 31 * result + (isbn?.hashCode() ?: 0)
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}

private val ISBN_PREFIX = Regex("^(urn:)?isbn:", RegexOption.IGNORE_CASE)
private val ISBN_DIGITS = Regex("^(\\d{13}|\\d{9}[\\dX])$")

/**
 * The ISBN in an EPUB `dc:identifier`, or null when the identifier is something else (a
 * UUID, a Calibre id). Accepts `urn:isbn:…`, `isbn:…` and bare ISBN-10/13, with hyphens or
 * spaces.
 */
fun isbnFromIdentifier(identifier: String?): String? {
    val candidate = identifier.orEmpty()
        .trim()
        .replace(ISBN_PREFIX, "")
        .replace("-", "")
        .replace(" ", "")
        .uppercase()
    return candidate.takeIf { value -> ISBN_DIGITS.matches(value) }
}
