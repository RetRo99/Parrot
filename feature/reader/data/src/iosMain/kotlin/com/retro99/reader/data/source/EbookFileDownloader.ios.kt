package com.retro99.reader.data.source

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.file.safeFileName
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookType
import com.retro99.server.api.ServerNetworkClientProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import retro99.network.api.NetworkClient

@Single
actual class EbookFileDownloader(
    @Provided private val networkClientFactory: ServerNetworkClientProvider,
) {

    private val ebooksDir: String
        @OptIn(ExperimentalForeignApi::class)
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSCachesDirectory,
                NSUserDomainMask,
                true,
            )
            val cachesDir = paths.firstOrNull() as? String ?: ""
            val ebooksPath = "$cachesDir/ebooks"
            NSFileManager.defaultManager.createDirectoryAtPath(
                ebooksPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return ebooksPath
        }

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

    @OptIn(ExperimentalForeignApi::class)
    actual fun getCachedEbookPath(bookUuid: String, bookType: BookType): String? {
        val dirPath = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        val dirExists = NSFileManager.defaultManager.fileExistsAtPath(dirPath)
        if (dirExists) {
            val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dirPath, error = null)
            if (contents != null && (contents as? List<*>)?.isNotEmpty() == true) {
                return dirPath
            }
        }
        val singlePath = "$ebooksDir/${getSingleFileName(bookUuid, bookType)}"
        return if (NSFileManager.defaultManager.fileExistsAtPath(singlePath)) singlePath else null
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun isEbookCached(bookUuid: String, bookType: BookType): Boolean {
        val dirPath = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        val dirExists = NSFileManager.defaultManager.fileExistsAtPath(dirPath)
        if (dirExists) {
            val contents = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dirPath, error = null)
            if (contents != null && (contents as? List<*>)?.isNotEmpty() == true) {
                return true
            }
        }
        return NSFileManager.defaultManager.fileExistsAtPath(
            "$ebooksDir/${getSingleFileName(bookUuid, bookType)}",
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean {
        val dirPath = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        if (NSFileManager.defaultManager.fileExistsAtPath(dirPath)) {
            return NSFileManager.defaultManager.removeItemAtPath(dirPath, error = null)
        }
        val singlePath = "$ebooksDir/${getSingleFileName(bookUuid, bookType)}"
        return if (NSFileManager.defaultManager.fileExistsAtPath(singlePath)) {
            NSFileManager.defaultManager.removeItemAtPath(singlePath, error = null)
        } else {
            true
        }
    }

    private suspend fun downloadSingleFile(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
    ): AppResult<String> {
        val localPath = "$ebooksDir/${getSingleFileName(bookUuid, bookType)}"
        val (path, queryParams) = ebookFilePath.parseDownloadPath()

        return networkClient.downloadFileToPath(
            path = path,
            destinationPath = localPath,
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
        val localPath = "$ebooksDir/${getSingleFileName(bookUuid, bookType)}"
        val (path, queryParams) = ebookFilePath.parseDownloadPath()

        return networkClient.downloadFileToPathWithProgress(
            path = path,
            destinationPath = localPath,
            onProgress = onProgress,
            queryBuilder = { queryParams.forEach { (k, v) -> k to v } },
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun downloadMultipleFiles(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
    ): AppResult<String> {
        val paths = ebookFilePath.multiFilePaths()
        val targetDir = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        NSFileManager.defaultManager.createDirectoryAtPath(
            targetDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        for ((index, path) in paths.withIndex()) {
            val localPath = "$targetDir/${formatFileIndex(index)}"
            val (urlPath, queryParams) = path.parseDownloadPath()

            val result = networkClient.downloadFileToPath(
                path = urlPath,
                destinationPath = localPath,
                queryBuilder = { queryParams.forEach { (k, v) -> k to v } },
            )
            if (result.isErr) return result
        }
        return Ok(targetDir)
    }

    @OptIn(ExperimentalForeignApi::class)
    private suspend fun downloadMultipleFilesWithProgress(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): AppResult<String> {
        val paths = ebookFilePath.multiFilePaths()
        val targetDir = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        NSFileManager.defaultManager.createDirectoryAtPath(
            targetDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        for ((index, path) in paths.withIndex()) {
            val localPath = "$targetDir/${formatFileIndex(index)}"
            val (urlPath, queryParams) = path.parseDownloadPath()

            onProgress(index.toLong(), paths.size.toLong())

            val result = networkClient.downloadFileToPathWithProgress(
                path = urlPath,
                destinationPath = localPath,
                onProgress = { _, _ -> },
                queryBuilder = { queryParams.forEach { (k, v) -> k to v } },
            )
            if (result.isErr) return result
        }
        onProgress(paths.size.toLong(), paths.size.toLong())
        return Ok(targetDir)
    }

    private fun formatFileIndex(index: Int): String =
        (index + 1).toString().padStart(2, '0')

    // Ids come from the server; sanitise so they cannot traverse paths.
    private fun getSingleFileName(bookUuid: String, bookType: BookType): String =
        "${bookUuid.safeFileName()}_${bookType.value}.epub"

    private fun getDirectoryName(bookUuid: String, bookType: BookType): String =
        "${bookUuid.safeFileName()}_${bookType.value}"
}
