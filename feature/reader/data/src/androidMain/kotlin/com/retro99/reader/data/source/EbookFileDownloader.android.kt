package com.retro99.reader.data.source

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.file.encodeFileNameSegment
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookType
import com.retro99.server.api.ServerNetworkClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import retro99.network.api.NetworkClient
import java.io.File

@Single
actual class EbookFileDownloader(
    @Provided private val context: Context,
    @Provided private val networkClientFactory: ServerNetworkClientProvider,
) {

    private val ebooksDir: File
        get() = File(context.filesDir, "ebooks").apply { mkdirs() }

    actual suspend fun downloadEbook(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        serverId: String,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val networkClient = networkClientFactory.createForServerId(serverId)
            ?: return@withContext Err(AppError.NotFoundError("Server not found: $serverId"))
        if (ebookFilePath.isMultiFileDownload()) {
            downloadMultipleFiles(ebookFilePath, bookUuid, bookType, networkClient)
        } else {
            downloadSingleFile(ebookFilePath, bookUuid, bookType, networkClient)
        }
    }

    actual suspend fun downloadEbookWithProgress(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        serverId: String,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val networkClient = networkClientFactory.createForServerId(serverId)
            ?: return@withContext Err(AppError.NotFoundError("Server not found: $serverId"))
        if (ebookFilePath.isMultiFileDownload()) {
            downloadMultipleFilesWithProgress(ebookFilePath, bookUuid, bookType, networkClient, onProgress)
        } else {
            downloadSingleFileWithProgress(ebookFilePath, bookUuid, bookType, networkClient, onProgress)
        }
    }

    actual fun getCachedEbookPath(bookUuid: String, bookType: BookType): String? {
        val multiFileDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        if (multiFileDir.exists() && multiFileDir.isDirectory && multiFileDir.listFiles()?.isNotEmpty() == true) {
            return multiFileDir.absolutePath
        }
        val singleFile = File(ebooksDir, getSingleFileName(bookUuid, bookType))
        return if (singleFile.exists()) singleFile.absolutePath else null
    }

    actual fun isEbookCached(bookUuid: String, bookType: BookType): Boolean {
        val multiFileDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        if (multiFileDir.exists() && multiFileDir.isDirectory && multiFileDir.listFiles()?.isNotEmpty() == true) {
            return true
        }
        return File(ebooksDir, getSingleFileName(bookUuid, bookType)).exists()
    }

    actual fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean {
        val multiFileDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        if (multiFileDir.exists()) {
            return multiFileDir.deleteRecursively()
        }
        val singleFile = File(ebooksDir, getSingleFileName(bookUuid, bookType))
        return if (singleFile.exists()) singleFile.delete() else true
    }

    private suspend fun downloadSingleFile(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
    ): AppResult<String> {
        val localFile = File(ebooksDir, getSingleFileName(bookUuid, bookType))
        val (path, queryParams) = ebookFilePath.parseDownloadPath()

        return networkClient.downloadFileToPath(
            path = path,
            destinationPath = localFile.absolutePath,
            queryBuilder = { queryParams.forEach { (k, v) -> k to v } },
        )
    }

    private suspend fun downloadSingleFileWithProgress(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): AppResult<String> {
        val localFile = File(ebooksDir, getSingleFileName(bookUuid, bookType))
        val (path, queryParams) = ebookFilePath.parseDownloadPath()

        return networkClient.downloadFileToPathWithProgress(
            path = path,
            destinationPath = localFile.absolutePath,
            onProgress = onProgress,
            queryBuilder = { queryParams.forEach { (k, v) -> k to v } },
        )
    }

    private suspend fun downloadMultipleFiles(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
    ): AppResult<String> {
        val paths = ebookFilePath.multiFilePaths()
        val targetDir = File(ebooksDir, getDirectoryName(bookUuid, bookType)).apply { mkdirs() }

        for ((index, path) in paths.withIndex()) {
            val localFile = File(targetDir, formatFileIndex(index))
            val (urlPath, queryParams) = path.parseDownloadPath()

            val result = networkClient.downloadFileToPath(
                path = urlPath,
                destinationPath = localFile.absolutePath,
                queryBuilder = { queryParams.forEach { (k, v) -> k to v } },
            )
            if (result.isErr) return result
        }
        return Ok(targetDir.absolutePath)
    }

    private suspend fun downloadMultipleFilesWithProgress(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): AppResult<String> {
        val paths = ebookFilePath.multiFilePaths()
        val targetDir = File(ebooksDir, getDirectoryName(bookUuid, bookType)).apply { mkdirs() }

        for ((index, path) in paths.withIndex()) {
            val localFile = File(targetDir, formatFileIndex(index))
            val (urlPath, queryParams) = path.parseDownloadPath()

            onProgress(index.toLong(), paths.size.toLong())

            val result = networkClient.downloadFileToPathWithProgress(
                path = urlPath,
                destinationPath = localFile.absolutePath,
                onProgress = { _, _ -> },
                queryBuilder = { queryParams.forEach { (k, v) -> k to v } },
            )
            if (result.isErr) return result
        }
        onProgress(paths.size.toLong(), paths.size.toLong())
        return Ok(targetDir.absolutePath)
    }

    private fun formatFileIndex(index: Int): String =
        (index + 1).toString().padStart(2, '0')

    // Server ids: encode so they cannot traverse or collide; safe ids keep
    // their existing cache names.
    private fun getSingleFileName(bookUuid: String, bookType: BookType): String =
        "${bookUuid.encodeFileNameSegment()}_${bookType.value}.epub"

    private fun getDirectoryName(bookUuid: String, bookType: BookType): String =
        "${bookUuid.encodeFileNameSegment()}_${bookType.value}"
}
