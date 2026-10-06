package com.retro99.reader.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.reader.domain.ReaderFontImportManager
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import kotlin.coroutines.cancellation.CancellationException

@Single(binds = [ReaderFontImportManager::class])
class IosReaderFontImportManager : ReaderFontImportManager {

    private val fontsDir: String
        @OptIn(ExperimentalForeignApi::class)
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                true,
            )
            val appSupportDir = paths.firstOrNull() as? String ?: ""
            val fontsPath = "$appSupportDir/reader-fonts"
            NSFileManager.defaultManager.createDirectoryAtPath(
                fontsPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            return fontsPath
        }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun importFont(
        platformFile: PlatformFile,
    ): AppResult<CustomReaderFontDomainModel> = withContext(Dispatchers.IO) {
        try {
            val extension = platformFile.name.substringAfterLast('.', "").lowercase()
            if (extension !in SUPPORTED_EXTENSIONS) {
                return@withContext Err(
                    AppError.UnknownError(Throwable("Unsupported font file type: $extension"))
                )
            }

            val id = NSUUID().UUIDString
            val displayName = platformFile.name.substringBeforeLast('.', platformFile.name)
                .ifBlank { "Custom Font" }
            val safeName = displayName.toSafeCssToken()
            val destPath = "$fontsDir/$id.$extension"
            val destUrl = NSURL.fileURLWithPath(destPath)

            val sourceUrl = platformFile.nsUrl
            sourceUrl.startAccessingSecurityScopedResource()
            val copySuccess = NSFileManager.defaultManager.copyItemAtURL(
                sourceUrl,
                destUrl,
                error = null,
            )
            sourceUrl.stopAccessingSecurityScopedResource()

            if (!copySuccess) {
                return@withContext Err(AppError.UnknownError(Throwable("Failed to copy font file")))
            }

            Ok(
                CustomReaderFontDomainModel(
                    id = id,
                    displayName = displayName,
                    cssFamily = "UserFont-$safeName-$id",
                    filePath = destPath,
                    fileName = platformFile.name,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Err(AppError.UnknownError(e))
        }
    }

    private fun String.toSafeCssToken(): String =
        lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "custom" }

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("ttf", "otf", "woff", "woff2")
    }
}
