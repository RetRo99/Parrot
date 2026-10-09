package com.retro99.books.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.map
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.data.source.LibraryLocalSource
import com.retro99.books.domain.BookFileOrigin
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.ImportedBookFile
import com.retro99.books.domain.StagedBookFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS implementation of [FileImportManager]. Copies the picked file into the temporary
 * directory and hands the copy to [StagedBookImporter].
 */
@Single(binds = [FileImportManager::class])
class IosFileImportManager(
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val libraryLocalSource: LibraryLocalSource,
) : FileImportManager {

    private val stagedBookImporter = StagedBookImporter(metadataExtractor, libraryLocalSource)

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun importEpubFile(
        platformFile: PlatformFile,
    ): AppResult<ImportedBookFile> = withContext(Dispatchers.IO) {
        val fileManager = NSFileManager.defaultManager
        // Keep .epub as the final extension so the metadata reader recognizes the format.
        val stagedPath = "${NSTemporaryDirectory()}${NSUUID().UUIDString}.tmp.epub"
        try {
            val copied = fileManager.copyItemAtURL(
                platformFile.nsUrl,
                NSURL.fileURLWithPath(stagedPath),
                error = null,
            )
            if (!copied) {
                return@withContext Err(AppError.UnknownError(Throwable("Failed to copy file")))
            }

            stagedBookImporter.importOrThrow(
                StagedBookFile(path = stagedPath, origin = BookFileOrigin.Import),
            ).map { imported -> ImportedBookFile(imported.libraryBookId, imported.mediaType) }
                .also { fileManager.removeItemAtPath(stagedPath, error = null) }
        } catch (e: CancellationException) {
            fileManager.removeItemAtPath(stagedPath, error = null)
            throw e
        } catch (e: Exception) {
            fileManager.removeItemAtPath(stagedPath, error = null)
            Err(AppError.UnknownError(e))
        }
    }
}
