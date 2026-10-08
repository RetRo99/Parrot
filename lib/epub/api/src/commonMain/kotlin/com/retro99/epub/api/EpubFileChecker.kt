package com.retro99.epub.api

/**
 * Decides whether a file on disk is an EPUB that Parrot can open, without trusting where it
 * came from. A catalogue download is checked with this before it goes anywhere near the
 * library: a server can send a web page, half a file, a protected book or a hostile archive
 * under an EPUB content type.
 *
 * Only the archive's directory and a few small entries are read; the book is never loaded
 * whole and nothing is extracted to disk.
 */
interface EpubFileChecker {
    suspend fun check(filePath: String): EpubFileCheck
}

sealed interface EpubFileCheck {
    data object Valid : EpubFileCheck

    /** The content is encrypted (DRM). Font obfuscation alone does not count. */
    data object Protected : EpubFileCheck

    data class NotAnEpub(val problem: EpubFileProblem) : EpubFileCheck
}

enum class EpubFileProblem {
    /** No file, or it cannot be opened. */
    Unreadable,
    Empty,

    /** A web page, a truncated download, or anything else that is not a ZIP archive. */
    NotAZip,

    /** Bytes before the first entry or after the end record, overlapping or repeated entries. */
    MalformedArchive,
    TooManyEntries,
    TooLargeUncompressed,

    /** An entry with an absolute path, a `..` step, a backslash or a control character. */
    UnsafeEntryPath,
    MimetypeNotFirst,
    WrongMimetype,
    NoContainer,
    NoPackageDocument,
}

/** Stated limits of [EpubFileChecker]. Tunables, not EPUB specification limits. */
object EpubFileLimits {
    const val MAX_ENTRIES: Int = 20_000

    /** Total size the archive's entries claim to unpack to. */
    const val MAX_UNCOMPRESSED_BYTES: Long = 2L * 1024 * 1024 * 1024

    /**
     * Above [RATIO_CHECK_FLOOR_BYTES] of declared content, the archive may not claim to
     * unpack to more than this many times its own size.
     */
    const val MAX_EXPANSION_RATIO: Long = 100
    const val RATIO_CHECK_FLOOR_BYTES: Long = 64L * 1024 * 1024
}
