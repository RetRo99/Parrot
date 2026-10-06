package com.retro99.books.data

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryLocalSource
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.ImportedBookFile
import com.retro99.books.domain.model.BookType
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
 * Android implementation of [FileImportManager]. Stages the picked file, reads it, and
 * hands it to [LibraryLocalSource], which decides which book it belongs to.
 */
@Single(binds = [FileImportManager::class])
class AndroidFileImportManager(
    @Provided private val context: Context,
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val libraryLocalSource: LibraryLocalSource,
    @Provided private val analytics: Analytics,
) : FileImportManager {

    override suspend fun importEpubFile(
        platformFile: PlatformFile,
    ): AppResult<ImportedBookFile> = withContext(Dispatchers.IO) {
        // Keep .epub as the final extension so Readium recognizes the staged file format.
        val stagedFile = File(context.cacheDir, "${UUID.randomUUID()}.tmp.epub")
        try {
            val bytes = platformFile.readBytes()
            FileOutputStream(stagedFile).use { outputStream ->
                outputStream.write(bytes)
            }

            val fileSize = stagedFile.length()
            if (fileSize == 0L) {
                stagedFile.delete()
                return@withContext Err(AppError.UnknownError(Throwable("File is empty")))
            }

            metadataExtractor.extractMetadata(stagedFile.absolutePath).andThen { metadata ->
                val mediaType = if (metadata.hasMediaOverlays) {
                    BookType.READALOUD.value
                } else {
                    BookType.EBOOK.value
                }
                libraryLocalSource.addImportedFile(
                    ImportedFileCandidate(
                        stagedPath = stagedFile.absolutePath,
                        mediaType = mediaType,
                        fileSize = fileSize,
                        contentHash = calculateFileContentHash(stagedFile.absolutePath),
                        contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
                        metadata = metadata,
                    ),
                ).map { libraryBookId -> ImportedBookFile(libraryBookId, mediaType) }
            }.also { stagedFile.delete() }
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
