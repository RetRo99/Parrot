package com.retro99.books.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalSource
import com.retro99.books.domain.BookFileOrigin
import com.retro99.books.domain.StagedBookFile
import com.retro99.books.domain.StagedBookImportManager
import com.retro99.books.domain.StagedBookImportOutcome
import com.retro99.books.domain.StagedBookImportResult
import com.retro99.books.domain.model.BookType
import com.retro99.database.api.library.DeviceFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads a staged EPUB and hands it to [LibraryLocalSource], which decides which book it
 * belongs to. Holds no state: [LibraryLocalSource] is what serializes imports.
 */
@Single(binds = [StagedBookImportManager::class])
internal class StagedBookImporter(
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val libraryLocalSource: LibraryLocalSource,
) : StagedBookImportManager {

    override suspend fun importStagedEpub(file: StagedBookFile): AppResult<StagedBookImportResult> =
        try {
            importOrThrow(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Err(AppError.UnknownError(e))
        }

    override suspend fun settleInterruptedImports() = libraryLocalSource.reconcileInterruptedImports()

    override suspend fun findBookOnDevice(contentSha256: String): String? =
        libraryLocalSource.findBookWithDeviceFile(CONTENT_HASH_ALGORITHM, contentSha256)

    /** [importStagedEpub] for the file pickers, which report a thrown exception themselves. */
    suspend fun importOrThrow(
        file: StagedBookFile,
    ): AppResult<StagedBookImportResult> = withContext(Dispatchers.IO) {
        val fileSize = fileSizeBytes(file.path)
        if (fileSize == 0L) {
            return@withContext Err(AppError.UnknownError(Throwable("File is empty")))
        }

        metadataExtractor.extractMetadata(file.path).andThen { metadata ->
            val mediaType = if (metadata.hasMediaOverlays) {
                BookType.READALOUD.value
            } else {
                BookType.EBOOK.value
            }
            libraryLocalSource.addStagedFile(
                ImportedFileCandidate(
                    stagedPath = file.path,
                    mediaType = mediaType,
                    fileSize = fileSize,
                    contentHash = calculateFileContentHash(file.path),
                    contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
                    metadata = metadata,
                    origin = file.origin.deviceFileOrigin(),
                    provenance = file.provenance,
                ),
            ).map { added ->
                StagedBookImportResult(
                    libraryBookId = added.libraryBookId,
                    mediaType = mediaType,
                    outcome = if (added.isNewBook) {
                        StagedBookImportOutcome.NewBook
                    } else {
                        StagedBookImportOutcome.ExistingBook
                    },
                )
            }
        }
    }

    private fun BookFileOrigin.deviceFileOrigin(): String = when (this) {
        BookFileOrigin.Import -> DeviceFileEntity.ORIGIN_IMPORT
        BookFileOrigin.CatalogueDownload -> DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD
    }
}
