package com.retro99.reader.data.source

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
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
import java.util.UUID

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

    actual fun getCachedEbookPath(bookUuid: String, bookType: BookType): String? {
        recoverPreviousMultiFileBundle(bookUuid, bookType)
        val multiFileDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        if (multiFileDir.exists() && multiFileDir.isDirectory &&
            multiFileDir.listFiles()?.isNotEmpty() == true
        ) {
            return multiFileDir.absolutePath
        }
        val singleFile = File(ebooksDir, getSingleFileName(bookUuid, bookType))
        return if (singleFile.exists()) singleFile.absolutePath else null
    }

    actual fun isEbookCached(bookUuid: String, bookType: BookType): Boolean {
        recoverPreviousMultiFileBundle(bookUuid, bookType)
        val multiFileDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        if (multiFileDir.exists() && multiFileDir.isDirectory &&
            multiFileDir.listFiles()?.isNotEmpty() == true
        ) {
            return true
        }
        return File(ebooksDir, getSingleFileName(bookUuid, bookType)).exists()
    }

    actual fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean {
        recoverPreviousMultiFileBundle(bookUuid, bookType)
        val multiFileDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        if (multiFileDir.exists() && !multiFileDir.deleteRecursively()) {
            return false
        }
        val previousDir = previousBundleDirectory(multiFileDir)
        if (previousDir.exists() && !previousDir.deleteRecursively()) return false
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
        val targetDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        val stagingDir = newStagingDirectory(targetDir)
        return downloadMultiFileBundle(
            filePaths = paths,
            destinationPath = { index ->
                File(stagingDir, formatFileIndex(index)).absolutePath
            },
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
            successResult = { Ok(targetDir.absolutePath) },
        )
    }

    private suspend fun downloadMultipleFilesWithProgress(
        ebookFilePath: String,
        bookUuid: String,
        bookType: BookType,
        networkClient: NetworkClient,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): AppResult<String> {
        val paths = ebookFilePath.multiFilePaths()
        val targetDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        val stagingDir = newStagingDirectory(targetDir)
        return downloadMultiFileBundle(
            filePaths = paths,
            destinationPath = { index ->
                File(stagingDir, formatFileIndex(index)).absolutePath
            },
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
                Ok(targetDir.absolutePath)
            },
        )
    }

    private fun newStagingDirectory(targetDir: File): File = File(
        targetDir.parentFile,
        ".${targetDir.name}.${UUID.randomUUID()}.download",
    )

    private fun prepareStagingDirectory(stagingDir: File) {
        if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
            error("Could not clear the multi-file download staging directory")
        }
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
            error("Could not create the multi-file download staging directory")
        }
    }

    private fun deleteStagingDirectory(stagingDir: File) {
        if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
            error("Could not remove the failed multi-file download staging directory")
        }
    }

    private fun promoteStagingDirectory(stagingDir: File, targetDir: File) {
        recoverPreviousMultiFileBundle(targetDir)
        val previousDir = previousBundleDirectory(targetDir)
        if (previousDir.exists() && !previousDir.deleteRecursively()) {
            error("Could not clear the previous multi-file cache directory")
        }

        val hadPreviousBundle = targetDir.exists()
        if (hadPreviousBundle && !targetDir.renameTo(previousDir)) {
            error("Could not preserve the existing multi-file cache directory")
        }
        if (!stagingDir.renameTo(targetDir)) {
            if (hadPreviousBundle && !previousDir.renameTo(targetDir)) {
                error("Could not promote the new bundle or restore the existing cache")
            }
            error("Could not promote the completed multi-file download")
        }
        if (hadPreviousBundle) previousDir.deleteRecursively()
    }

    private fun recoverPreviousMultiFileBundle(bookUuid: String, bookType: BookType) {
        val targetDir = File(ebooksDir, getDirectoryName(bookUuid, bookType))
        recoverPreviousMultiFileBundle(targetDir)
    }

    private fun recoverPreviousMultiFileBundle(targetDir: File) {
        val previousDir = previousBundleDirectory(targetDir)
        if (!targetDir.exists() && previousDir.exists()) previousDir.renameTo(targetDir)
    }

    private fun previousBundleDirectory(targetDir: File): File =
        File(targetDir.parentFile, "${targetDir.name}.previous")

    private fun formatFileIndex(index: Int): String =
        (index + 1).toString().padStart(2, '0')

    private fun getSingleFileName(bookUuid: String, bookType: BookType): String =
        "${bookUuid}_${bookType.value}.epub"

    private fun getDirectoryName(bookUuid: String, bookType: BookType): String =
        "${bookUuid}_${bookType.value}"
}
