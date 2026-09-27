package com.retro99.books.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.ServerType
import com.retro99.books.data.source.ImportedBooksLocalSource
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import io.github.vinceglb.filekit.core.PlatformFile
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
import platform.Foundation.NSURL
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
    @Provided private val importedBooksLocalSource: ImportedBooksLocalSource,
) : FileImportManager {

    private val ebooksDir: String
        @OptIn(ExperimentalForeignApi::class)
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            )
            val documentsDir = paths.firstOrNull() as? String ?: ""
            val ebooksPath = "$documentsDir/ebooks"
            NSFileManager.defaultManager.createDirectoryAtPath(
                ebooksPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return ebooksPath
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
        platformFile: PlatformFile,
    ): AppResult<BookDomainModel.LocalBook> = withContext(Dispatchers.IO) {
        try {
            val uuid = NSUUID().UUIDString
            val fileManager = NSFileManager.defaultManager

            // First, copy to temporary location to extract metadata
            // Keep .epub as the final extension so the metadata reader recognizes the format.
            val tempPath = "$ebooksDir/$uuid.tmp.epub"
            val sourceUrl = platformFile.nsUrl
            val tempUrl = NSURL.fileURLWithPath(tempPath)

            val copySuccess = fileManager.copyItemAtURL(sourceUrl, tempUrl, error = null)
            if (!copySuccess) {
                return@withContext Err(
                    AppError.UnknownError(Throwable("Failed to copy file"))
                )
            }

            // Get file size
            val attributes = fileManager.attributesOfItemAtPath(tempPath, error = null)
            val fileSize = (attributes?.get("NSFileSize") as? Long) ?: 0L

            if (fileSize == 0L) {
                fileManager.removeItemAtPath(tempPath, error = null)
                return@withContext Err(
                    AppError.UnknownError(Throwable("File is empty"))
                )
            }

            // Extract metadata to determine book type
            metadataExtractor.extractMetadata(tempPath).andThen { metadata ->
                // Determine book type based on media overlays
                val bookType = if (metadata.hasMediaOverlays) {
                    BookType.READALOUD
                } else {
                    BookType.EBOOK
                }

                // Move to final location with proper naming
                val destPath = "$ebooksDir/${uuid}_${bookType.value}.epub"
                val destUrl = NSURL.fileURLWithPath(destPath)
                val moveSuccess = fileManager.moveItemAtURL(tempUrl, destUrl, error = null)
                if (!moveSuccess) {
                    fileManager.removeItemAtPath(tempPath, error = null)
                    return@andThen Err(
                        AppError.UnknownError(Throwable("Failed to move file to final location"))
                    )
                }

                // Save cover if available
                val coverPath = metadata.coverBytes?.let { coverBytes ->
                    val coverFilePath = "$coversDir/$uuid.png"
                    writeBytesToFile(coverBytes, coverFilePath)
                    coverFilePath
                }

                val importedBook = BookDomainModel.LocalBook(
                    uuid = uuid,
                    serverId = LOCAL_SERVER_ID,
                    serverType = ServerType.Local,
                    title = metadata.title,
                    author = metadata.author,
                    description = metadata.description,
                    coverUrl = coverPath?.let { "file://$it" },
                    filePath = destPath,
                    fileSize = fileSize,
                    contentHash = calculateFileContentHash(destPath),
                    contentHashAlgorithm = CONTENT_HASH_ALGORITHM,
                    importedAt = Clock.System.now().toString(),
                    lastOpenedAt = null,
                    bookType = bookType,
                    publicationDate = metadata.publicationDate,
                )

                // Save to database
                importedBooksLocalSource.saveImportedBook(importedBook).andThen {
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
        val fileManager = NSFileManager.defaultManager

        // Check both ebook and readaloud types
        val ebookPath = "$ebooksDir/${uuid}_${BookType.EBOOK.value}.epub"
        if (fileManager.fileExistsAtPath(ebookPath)) return ebookPath

        val readaloudPath = "$ebooksDir/${uuid}_${BookType.READALOUD.value}.epub"
        if (fileManager.fileExistsAtPath(readaloudPath)) return readaloudPath

        return null
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteImportedBookFiles(uuid: String): Boolean {
        val fileManager = NSFileManager.defaultManager

        // Try to delete both possible file types
        val ebookPath = "$ebooksDir/${uuid}_${BookType.EBOOK.value}.epub"
        val readaloudPath = "$ebooksDir/${uuid}_${BookType.READALOUD.value}.epub"
        val coverPath = "$coversDir/$uuid.png"

        val ebookDeleted = if (fileManager.fileExistsAtPath(ebookPath)) {
            fileManager.removeItemAtPath(ebookPath, error = null)
        } else true

        val readaloudDeleted = if (fileManager.fileExistsAtPath(readaloudPath)) {
            fileManager.removeItemAtPath(readaloudPath, error = null)
        } else true

        val coverDeleted = if (fileManager.fileExistsAtPath(coverPath)) {
            fileManager.removeItemAtPath(coverPath, error = null)
        } else true

        return ebookDeleted && readaloudDeleted && coverDeleted
    }

    override suspend fun deleteLocalBook(uuid: String): CompletableResult {
        // First delete from database
        return importedBooksLocalSource.deleteImportedBook(uuid).map {
            // Then delete the actual files (best effort - orphaned files can be cleaned up later)
            deleteImportedBookFiles(uuid)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
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
