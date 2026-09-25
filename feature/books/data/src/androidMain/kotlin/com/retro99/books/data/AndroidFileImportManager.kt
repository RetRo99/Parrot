package com.retro99.books.data

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.map
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.source.ImportedBooksLocalSource
import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.base.server.ServerType
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.LocalBookFileUsageCoordinator
import com.retro99.books.domain.LocalBookFileUsageLease
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import io.github.vinceglb.filekit.core.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android implementation of [FileImportManager].
 */
@Single(binds = [FileImportManager::class])
class AndroidFileImportManager(
    @Provided private val context: Context,
    @Provided private val metadataExtractor: EpubMetadataExtractor,
    @Provided private val importedBooksLocalSource: ImportedBooksLocalSource,
    @Provided private val analytics: Analytics,
    @Provided private val fileUsageCoordinator: LocalBookFileUsageCoordinator,
) : FileImportManager {

    private val ebooksDir: File
        get() = File(context.filesDir, "ebooks").apply { mkdirs() }

    private val coversDir: File
        get() = File(context.filesDir, "imported_covers").apply { mkdirs() }

    override suspend fun importEpubFile(
        platformFile: PlatformFile,
    ): AppResult<BookDomainModel.LocalBook> = withContext(Dispatchers.IO) {
        try {
            val uuid = UUID.randomUUID().toString()

            // First, copy to temporary location to extract metadata
            val tempFile = File(context.cacheDir, "$uuid.epub.tmp")
            context.contentResolver.openInputStream(platformFile.uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 8192)
                }
            } ?: return@withContext Err(
                AppError.UnknownError(Throwable("Could not open input stream for file"))
            )

            val fileSize = tempFile.length()
            if (fileSize == 0L) {
                tempFile.delete()
                return@withContext Err(
                    AppError.UnknownError(Throwable("File is empty"))
                )
            }

            // Extract metadata to determine book type
            metadataExtractor.extractMetadata(tempFile.absolutePath).andThen { metadata ->
                // Determine book type based on media overlays
                val bookType = if (metadata.hasMediaOverlays) {
                    BookType.READALOUD
                } else {
                    BookType.EBOOK
                }

                // Move to final location with proper naming
                val destFile = File(ebooksDir, "${uuid}_${bookType.value}.epub")
                if (!tempFile.renameTo(destFile)) {
                    // If rename fails, try copy and delete
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                // Save cover if available
                val coverPath = metadata.coverBytes?.let { coverBytes ->
                    val coverFile = File(coversDir, "$uuid.png")
                    coverFile.writeBytes(coverBytes)
                    coverFile.absolutePath
                }

                val importedBook = BookDomainModel.LocalBook(
                    uuid = uuid,
                    serverId = LOCAL_SERVER_ID,
                    serverType = ServerType.Local,
                    title = metadata.title,
                    author = metadata.author,
                    description = metadata.description,
                    coverUrl = coverPath?.let { "file://$it" },
                    filePath = destFile.absolutePath,
                    fileSize = fileSize,
                    contentHash = calculateFileContentHash(destFile.absolutePath),
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
            analytics.logException(e, "AndroidFileImportManager: Error importing EPUB")
            Err(AppError.UnknownError(e))
        }
    }

    override fun getImportedBookPath(uuid: String): String? {
        // Check both ebook and readaloud types
        val ebookFile = File(ebooksDir, "${uuid}_${BookType.EBOOK.value}.epub")
        if (ebookFile.exists()) return ebookFile.absolutePath

        val readaloudFile = File(ebooksDir, "${uuid}_${BookType.READALOUD.value}.epub")
        if (readaloudFile.exists()) return readaloudFile.absolutePath

        return null
    }

    override fun deleteImportedBookFiles(uuid: String): Boolean {
        // Try to delete both possible file types
        val ebookFile = File(ebooksDir, "${uuid}_${BookType.EBOOK.value}.epub")
        val readaloudFile = File(ebooksDir, "${uuid}_${BookType.READALOUD.value}.epub")
        val coverFile = File(coversDir, "$uuid.png")

        val ebookDeleted = if (ebookFile.exists()) ebookFile.delete() else true
        val readaloudDeleted = if (readaloudFile.exists()) readaloudFile.delete() else true
        val coverDeleted = if (coverFile.exists()) coverFile.delete() else true

        return ebookDeleted && readaloudDeleted && coverDeleted
    }

    override fun deleteImportedBookReplicaFile(
        uuid: String,
        storageReference: String,
    ): Boolean {
        val expectedFiles = listOf(
            File(ebooksDir, "${uuid}_${BookType.EBOOK.value}.epub"),
            File(ebooksDir, "${uuid}_${BookType.READALOUD.value}.epub"),
        )
        val target = File(storageReference)
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return false
        val isOwnedImportPath = expectedFiles.any { file ->
            runCatching { file.canonicalPath }.getOrNull() == targetPath
        }
        if (!isOwnedImportPath) return false
        if (!target.exists()) return true
        if (!target.isFile) return false
        return target.delete()
    }

    override suspend fun deleteLocalBook(uuid: String): CompletableResult {
        val paths = listOf(
            File(ebooksDir, "${uuid}_${BookType.EBOOK.value}.epub").absolutePath,
            File(ebooksDir, "${uuid}_${BookType.READALOUD.value}.epub").absolutePath,
        ).sorted()
        val leases = mutableListOf<LocalBookFileUsageLease>()
        try {
            paths.forEach { path ->
                leases += fileUsageCoordinator.acquireRemoval(path)
            }

            return importedBooksLocalSource.deleteImportedBook(uuid).map { _ ->
                // Delete actual files best-effort; orphaned files can be cleaned up later.
                deleteImportedBookFiles(uuid)
                Unit
            }
        } finally {
            leases.asReversed().forEach { lease -> lease.release() }
        }
    }
}
