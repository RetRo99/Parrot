package com.retro99.books.data

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.map
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.data.source.LibraryLocalSource
import com.retro99.books.domain.BookFileOrigin
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.ImportedBookFile
import com.retro99.books.domain.StagedBookFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android implementation of [FileImportManager]. Copies the picked file into the cache and
 * hands the copy to [StagedBookImporter].
 */
@Single(binds = [FileImportManager::class])
class AndroidFileImportManager(
    @Provided private val context: Context,
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val libraryLocalSource: LibraryLocalSource,
    @Provided private val analytics: Analytics,
) : FileImportManager {

    private val stagedBookImporter = StagedBookImporter(metadataExtractor, libraryLocalSource)

    override suspend fun importEpubFile(
        platformFile: PlatformFile,
    ): AppResult<ImportedBookFile> = importCopy { stagedFile ->
        val bytes = platformFile.readBytes()
        FileOutputStream(stagedFile).use { outputStream ->
            outputStream.write(bytes)
        }
    }

    /** Imports what [copyPickedFileTo] writes. The staged copy is always gone afterwards. */
    internal suspend fun importCopy(
        copyPickedFileTo: suspend (File) -> Unit,
    ): AppResult<ImportedBookFile> = withContext(Dispatchers.IO) {
        // Keep .epub as the final extension so Readium recognizes the staged file format.
        val stagedFile = File(context.cacheDir, "${UUID.randomUUID()}.tmp.epub")
        try {
            copyPickedFileTo(stagedFile)

            stagedBookImporter.importOrThrow(
                StagedBookFile(path = stagedFile.absolutePath, origin = BookFileOrigin.Import),
            ).map { imported -> ImportedBookFile(imported.libraryBookId, imported.mediaType) }
                .also { stagedFile.delete() }
        } catch (e: CancellationException) {
            stagedFile.delete()
            throw e
        } catch (e: Exception) {
            stagedFile.delete()
            analytics.logException(e, "AndroidFileImportManager: Error importing EPUB")
            Err(AppError.UnknownError(e))
        }
    }
}
