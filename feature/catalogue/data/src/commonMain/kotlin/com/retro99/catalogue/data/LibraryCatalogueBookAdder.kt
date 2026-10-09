package com.retro99.catalogue.data

import com.github.michaelbull.result.mapBoth
import com.retro99.base.result.AppError
import com.retro99.books.domain.BookFileOrigin
import com.retro99.books.domain.BookFileProvenance
import com.retro99.books.domain.StagedBookFile
import com.retro99.books.domain.StagedBookImportManager
import com.retro99.books.domain.StagedBookNotReadable
import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.catalogue.domain.CatalogueBookAdder
import com.retro99.catalogue.domain.StagedCatalogueBook
import kotlinx.coroutines.CancellationException

/**
 * Adds a staged catalogue download through the library's own staged import, the same one the
 * file picker ends in. The library decides which book the bytes belong to: a file it already
 * holds exactly is attached to that book and nothing else about the book changes.
 *
 * [importer] is lazy: the library import sits behind the server registry, and the registry is
 * handed the queue that owns this adder.
 */
internal class LibraryCatalogueBookAdder(
    private val importer: Lazy<StagedBookImportManager>,
    private val activeProfileId: () -> String?,
) : CatalogueBookAdder {

    override suspend fun add(profileId: String, book: StagedCatalogueBook): CatalogueBookAddResult {
        // The library writes into the open profile. Another one being open means this
        // request's profile was closed while its file was being checked.
        requireOpen(profileId)
        return importer.value.importStagedEpub(
            StagedBookFile(
                path = book.path,
                origin = BookFileOrigin.CatalogueDownload,
                provenance = BookFileProvenance(book.sourceId, book.publicationKey, book.representationKey),
            ),
        ).mapBoth(
            success = { imported -> CatalogueBookAddResult.Added(imported.libraryBookId) },
            failure = { error -> CatalogueBookAddResult.Failed(error.failureReason()) },
        )
    }

    override suspend fun settleInterruptedAdds(profileId: String) {
        requireOpen(profileId)
        importer.value.settleInterruptedImports()
    }

    override suspend fun findAddedBook(profileId: String, contentHash: String): String? {
        requireOpen(profileId)
        return importer.value.findBookOnDevice(contentHash)
    }

    private fun requireOpen(profileId: String) {
        if (activeProfileId() != profileId) throw CancellationException("Profile is no longer open")
    }

    /** The file passed Parrot's own check, so anything but "the library cannot read it" is this device. */
    private fun AppError.failureReason(): AcquisitionFailureReason =
        if ((this as? AppError.UnknownError)?.throwable is StagedBookNotReadable) {
            AcquisitionFailureReason.Invalid
        } else {
            AcquisitionFailureReason.Storage
        }
}
