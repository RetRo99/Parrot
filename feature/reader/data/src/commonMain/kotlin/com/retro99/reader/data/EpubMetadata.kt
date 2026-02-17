package com.retro99.reader.data

/**
 * Metadata extracted from an EPUB file.
 */
data class EpubMetadata(
    val title: String,
    val author: String?,
    val description: String?,
    val coverBytes: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as EpubMetadata

        if (title != other.title) return false
        if (author != other.author) return false
        if (description != other.description) return false
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
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}

