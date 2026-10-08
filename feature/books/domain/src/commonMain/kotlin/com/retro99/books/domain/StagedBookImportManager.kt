package com.retro99.books.domain

import com.retro99.base.result.AppResult

/**
 * Adds an EPUB that is already on this device's disk to your library. The file picker and
 * catalogue downloads both end here.
 */
interface StagedBookImportManager {
    /**
     * Reads the staged file's metadata, hashes it and moves it into the library. The file
     * is streamed and moved, never read into memory whole.
     *
     * A file whose bytes are already known is attached to the existing book, which keeps
     * its progress, positions and annotations. Different bytes always become a new book,
     * even with the same title or ISBN.
     *
     * On success the staged file is gone: moved into the library, or deleted when the
     * library already had it. On failure it is left where it is, for the caller to retry
     * or delete.
     */
    suspend fun importStagedEpub(file: StagedBookFile): AppResult<StagedBookImportResult>
}

/**
 * A complete EPUB waiting in a staging location.
 *
 * @param path absolute path on local disk. It must end in `.epub`, or the metadata reader
 *   does not recognize the format.
 * @param provenance where the file came from, when it did not come from the file picker
 */
data class StagedBookFile(
    val path: String,
    val origin: BookFileOrigin,
    val provenance: BookFileProvenance? = null,
)

/** How a file got onto this device. */
enum class BookFileOrigin {
    /** Picked by you from this device's files. */
    Import,

    /** Downloaded from a catalogue you browsed. */
    CatalogueDownload,
}

/**
 * The catalogue publication a file was acquired from. Identifiers only: never a signed
 * link, a header or a password.
 */
data class BookFileProvenance(
    val sourceId: String,
    val publicationKey: String,
    val representationKey: String? = null,
)

data class StagedBookImportResult(
    val libraryBookId: String,
    val mediaType: String,
    val outcome: StagedBookImportOutcome,
)

enum class StagedBookImportOutcome {
    /** The bytes were new, so a new library book was created. */
    NewBook,

    /** The library already had these exact bytes; the file belongs to that book. */
    ExistingBook,
}
