package com.retro99.reader.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.ImportedBooksRepository
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS implementation of [FileImportManager].
 */
@Single(binds = [FileImportManager::class])
class IosFileImportManager(
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val importedBooksRepository: ImportedBooksRepository,
) : FileImportManager {

    private val importedBooksDir: String
        @OptIn(ExperimentalForeignApi::class)
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            )
            val documentsDir = paths.firstOrNull() as? String ?: ""
            val importedPath = "$documentsDir/imported_books"
            NSFileManager.defaultManager.createDirectoryAtPath(
                importedPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return importedPath
        }

    private val coversDir: String
        @OptIn(ExperimentalForeignApi::class)
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            )
            val documentsDir = paths.firstOrNull() as? String ?: ""
            val coversPath = "$documentsDir/imported_covers"
            NSFileManager.defaultManager.createDirectoryAtPath(
                coversPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return coversPath
        }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun importEpubFile(
        fileBytes: ByteArray,
        fileName: String,
    ): AppResult<BookDomainModel.LocalBook> = withContext(Dispatchers.IO) {
        try {
            val uuid = NSUUID().UUIDString

            if (fileBytes.isEmpty()) {
                return@withContext Err(
                    AppError.UnknownError(Throwable("File bytes are empty"))
                )
            }

            // Write bytes to app storage
            val destPath = "$importedBooksDir/$uuid.epub"
            writeBytesToFile(fileBytes, destPath)

            // Get file size
            val fileManager = NSFileManager.defaultManager
            val attributes = fileManager.attributesOfItemAtPath(destPath, error = null)
            val fileSize = (attributes?.get("NSFileSize") as? Long) ?: 0L

            // Extract metadata
            metadataExtractor.extractMetadata(destPath).andThen { metadata ->
                // Save cover if available
                val coverPath = metadata.coverBytes?.let { coverBytes ->
                    val coverFilePath = "$coversDir/$uuid.png"
                    writeBytesToFile(coverBytes, coverFilePath)
                    coverFilePath
                }

                val importedBook = BookDomainModel.LocalBook(
                    uuid = uuid,
                    title = metadata.title,
                    author = metadata.author,
                    description = metadata.description,
                    coverUrl = coverPath?.let { "file://$it" },
                    filePath = destPath,
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
            Err(AppError.UnknownError(e))
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun getImportedBookPath(uuid: String): String? {
        val path = "$importedBooksDir/$uuid.epub"
        return if (NSFileManager.defaultManager.fileExistsAtPath(path)) path else null
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteImportedBookFiles(uuid: String): Boolean {
        val fileManager = NSFileManager.defaultManager
        val epubPath = "$importedBooksDir/$uuid.epub"
        val coverPath = "$coversDir/$uuid.png"

        val epubDeleted = if (fileManager.fileExistsAtPath(epubPath)) {
            fileManager.removeItemAtPath(epubPath, error = null)
        } else true

        val coverDeleted = if (fileManager.fileExistsAtPath(coverPath)) {
            fileManager.removeItemAtPath(coverPath, error = null)
        } else true

        return epubDeleted && coverDeleted
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeBytesToFile(bytes: ByteArray, filePath: String) {
    val fileManager = NSFileManager.defaultManager
    if (!fileManager.fileExistsAtPath(filePath)) {
        fileManager.createFileAtPath(filePath, contents = null, attributes = null)
    }

    val fileHandle = NSFileHandle.fileHandleForWritingAtPath(filePath) ?: return

    try {
        bytes.usePinned { pinned ->
            val data = NSData.create(
                bytes = pinned.addressOf(0),
                length = bytes.size.toULong(),
            )
            fileHandle.writeData(data)
        }
    } finally {
        fileHandle.closeFile()
    }
}

