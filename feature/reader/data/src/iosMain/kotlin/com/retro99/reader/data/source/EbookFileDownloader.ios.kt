package com.retro99.reader.data.source

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
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
import platform.Foundation.NSUUID
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
            downloadSingleFile(
                ebookFilePath,
                bookUuid,
                bookType,
                networkClient,
            )
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
            downloadMultipleFilesWithProgress(
                ebookFilePath,
                bookUuid,
                bookType,
                networkClient,
                onProgress,
            )
        } else {
            downloadSingleFileWithProgress(
                ebookFilePath,
                bookUuid,
                bookType,
                networkClient,
                onProgress,
            )
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun getCachedEbookPath(bookUuid: String, bookType: BookType): String? {
        recoverPreviousMultiFileBundle(bookUuid, bookType)
        val dirPath = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        val dirExists = NSFileManager.defaultManager.fileExistsAtPath(dirPath)
        if (dirExists) {
            val contents = NSFileManager.defaultManager
                .contentsOfDirectoryAtPath(dirPath, error = null)
            if (contents != null && (contents as? List<*>)?.isNotEmpty() == true) {
                return dirPath
            }
        }
        val singlePath = "$ebooksDir/${getSingleFileName(bookUuid, bookType)}"
        return if (NSFileManager.defaultManager.fileExistsAtPath(singlePath)) singlePath else null
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun isEbookCached(bookUuid: String, bookType: BookType): Boolean {
        recoverPreviousMultiFileBundle(bookUuid, bookType)
        val dirPath = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        val dirExists = NSFileManager.defaultManager.fileExistsAtPath(dirPath)
        if (dirExists) {
            val contents = NSFileManager.defaultManager
                .contentsOfDirectoryAtPath(dirPath, error = null)
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
        recoverPreviousMultiFileBundle(bookUuid, bookType)
        val dirPath = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        if (NSFileManager.defaultManager.fileExistsAtPath(dirPath)) {
            if (!NSFileManager.defaultManager.removeItemAtPath(dirPath, error = null)) {
                return false
            }
        }
        val previousDir = previousBundleDirectory(dirPath)
        if (NSFileManager.defaultManager.fileExistsAtPath(previousDir) &&
            !NSFileManager.defaultManager.removeItemAtPath(previousDir, error = null)
        ) {
            return false
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
        val stagingDir = newStagingDirectory(targetDir)
        return downloadMultiFileBundle(
            filePaths = paths,
            destinationPath = { index -> "$stagingDir/${formatFileIndex(index)}" },
            prepareStagingDirectory = { prepareStagingDirectory(stagingDir) },
            downloadFile = { path, destinationPath, _ ->
                val (urlPath, queryParams) = path.parseDownloadPath()
                networkClient.downloadFileToPath(
                    path = urlPath,
                    destinationPath = destinationPath,
                    queryBuilder = { queryParams.forEach { (key, value) -> key to value } },
                )
            },
            isFailure = { result -> result.isErr },
            promoteStagingDirectory = { promoteStagingDirectory(stagingDir, targetDir) },
            deleteStagingDirectory = { deleteStagingDirectory(stagingDir) },
            successResult = { Ok(targetDir) },
        )
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
        val stagingDir = newStagingDirectory(targetDir)
        return downloadMultiFileBundle(
            filePaths = paths,
            destinationPath = { index -> "$stagingDir/${formatFileIndex(index)}" },
            prepareStagingDirectory = { prepareStagingDirectory(stagingDir) },
            downloadFile = { path, destinationPath, index ->
                onProgress(index.toLong(), paths.size.toLong())
                val (urlPath, queryParams) = path.parseDownloadPath()
                networkClient.downloadFileToPathWithProgress(
                    path = urlPath,
                    destinationPath = destinationPath,
                    onProgress = { _, _ -> },
                    queryBuilder = { queryParams.forEach { (key, value) -> key to value } },
                )
            },
            isFailure = { result -> result.isErr },
            promoteStagingDirectory = { promoteStagingDirectory(stagingDir, targetDir) },
            deleteStagingDirectory = { deleteStagingDirectory(stagingDir) },
            successResult = {
                onProgress(paths.size.toLong(), paths.size.toLong())
                Ok(targetDir)
            },
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun newStagingDirectory(targetDir: String): String =
        "$ebooksDir/.${targetDir.substringAfterLast('/')}.${NSUUID().UUIDString}.download"

    @OptIn(ExperimentalForeignApi::class)
    private fun prepareStagingDirectory(stagingDir: String) {
        deleteStagingDirectory(stagingDir)
        val fileManager = NSFileManager.defaultManager
        fileManager.createDirectoryAtPath(
            stagingDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        if (!fileManager.fileExistsAtPath(stagingDir)) {
            error("Could not create the multi-file download staging directory")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun deleteStagingDirectory(stagingDir: String) {
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(stagingDir) &&
            !fileManager.removeItemAtPath(stagingDir, error = null)
        ) {
            error("Could not remove the failed multi-file download staging directory")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun promoteStagingDirectory(stagingDir: String, targetDir: String) {
        val fileManager = NSFileManager.defaultManager
        recoverPreviousMultiFileBundle(targetDir)
        val previousDir = previousBundleDirectory(targetDir)
        if (fileManager.fileExistsAtPath(previousDir) &&
            !fileManager.removeItemAtPath(previousDir, error = null)
        ) {
            error("Could not clear the previous multi-file cache directory")
        }

        val hadPreviousBundle = fileManager.fileExistsAtPath(targetDir)
        if (hadPreviousBundle &&
            !fileManager.moveItemAtPath(targetDir, previousDir, error = null)
        ) {
            error("Could not preserve the existing multi-file cache directory")
        }
        if (!fileManager.moveItemAtPath(stagingDir, targetDir, error = null)) {
            if (hadPreviousBundle &&
                !fileManager.moveItemAtPath(previousDir, targetDir, error = null)
            ) {
                error("Could not promote the new bundle or restore the existing cache")
            }
            error("Could not promote the completed multi-file download")
        }
        if (hadPreviousBundle) fileManager.removeItemAtPath(previousDir, error = null)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun recoverPreviousMultiFileBundle(bookUuid: String, bookType: BookType) {
        val targetDir = "$ebooksDir/${getDirectoryName(bookUuid, bookType)}"
        recoverPreviousMultiFileBundle(targetDir)
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun recoverPreviousMultiFileBundle(targetDir: String) {
        val fileManager = NSFileManager.defaultManager
        val previousDir = previousBundleDirectory(targetDir)
        if (!fileManager.fileExistsAtPath(targetDir) &&
            fileManager.fileExistsAtPath(previousDir)
        ) {
            fileManager.moveItemAtPath(previousDir, targetDir, error = null)
        }
    }

    private fun previousBundleDirectory(targetDir: String): String = "$targetDir.previous"

    private fun formatFileIndex(index: Int): String =
        (index + 1).toString().padStart(2, '0')

    private fun getSingleFileName(bookUuid: String, bookType: BookType): String =
        "${bookUuid}_${bookType.value}.epub"

    private fun getDirectoryName(bookUuid: String, bookType: BookType): String =
        "${bookUuid}_${bookType.value}"
}
