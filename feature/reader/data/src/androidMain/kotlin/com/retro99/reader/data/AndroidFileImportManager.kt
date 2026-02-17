package com.retro99.reader.data

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.ImportedBooksRepository
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android implementation of [FileImportManager].
 */
@Single(binds = [FileImportManager::class])
class AndroidFileImportManager(
    @Provided private val context: Context,
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val importedBooksRepository: ImportedBooksRepository,
    @Provided private val analytics: Analytics,
) : FileImportManager {

    private val importedBooksDir: File
        get() = File(context.filesDir, "imported_books").apply { mkdirs() }

    private val coversDir: File
        get() = File(context.filesDir, "imported_covers").apply { mkdirs() }

    override suspend fun importEpubFile(
        fileBytes: ByteArray,
        fileName: String,
    ): AppResult<BookDomainModel.LocalBook> = withContext(Dispatchers.IO) {
        try {
            val uuid = UUID.randomUUID().toString()

            if (fileBytes.isEmpty()) {
                return@withContext Err(
                    AppError.UnknownError(Throwable("File bytes are empty"))
                )
            }

            // Write bytes to app storage
            val destFile = File(importedBooksDir, "$uuid.epub")
            destFile.writeBytes(fileBytes)
            val fileSize = destFile.length()

            // Extract metadata
            metadataExtractor.extractMetadata(destFile.absolutePath).andThen { metadata ->
                // Save cover if available
                val coverPath = metadata.coverBytes?.let { coverBytes ->
                    val coverFile = File(coversDir, "$uuid.png")
                    coverFile.writeBytes(coverBytes)
                    coverFile.absolutePath
                }

                val importedBook = BookDomainModel.LocalBook(
                    uuid = uuid,
                    title = metadata.title,
                    author = metadata.author,
                    description = metadata.description,
                    coverUrl = coverPath?.let { "file://$it" },
                    filePath = destFile.absolutePath,
                    fileSize = fileSize,
                    importedAt = Clock.System.now().toString(),
                    lastOpenedAt = null,
                )

                // Save to database
                importedBooksRepository.saveImportedBook(importedBook).andThen {
                    Ok(importedBook)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analytics.logException(e, "AndroidFileImportManager: Error importing EPUB")
            Err(AppError.UnknownError(e))
        }
    }

    override fun getImportedBookPath(uuid: String): String? {
        val file = File(importedBooksDir, "$uuid.epub")
        return if (file.exists()) file.absolutePath else null
    }

    override fun deleteImportedBookFiles(uuid: String): Boolean {
        val epubFile = File(importedBooksDir, "$uuid.epub")
        val coverFile = File(coversDir, "$uuid.png")

        val epubDeleted = if (epubFile.exists()) epubFile.delete() else true
        val coverDeleted = if (coverFile.exists()) coverFile.delete() else true

        return epubDeleted && coverDeleted
    }
}

