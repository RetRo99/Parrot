package com.retro99.reader.data

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.reader.domain.ReaderFontImportManager
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

@Single(binds = [ReaderFontImportManager::class])
class AndroidReaderFontImportManager(
    @Provided private val context: Context,
) : ReaderFontImportManager {

    private val fontsDir: File
        get() = File(context.filesDir, "reader-fonts").apply { mkdirs() }

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

            val id = UUID.randomUUID().toString()
            val displayName = platformFile.name.substringBeforeLast('.', platformFile.name)
                .ifBlank { "Custom Font" }
            val safeName = displayName.toSafeCssToken()
            val destFile = File(fontsDir, "$id.$extension")

            val bytes = platformFile.readBytes()
            FileOutputStream(destFile).use { outputStream ->
                outputStream.write(bytes)
            }

            if (destFile.length() == 0L) {
                destFile.delete()
                return@withContext Err(AppError.UnknownError(Throwable("Font file is empty")))
            }

            Ok(
                CustomReaderFontDomainModel(
                    id = id,
                    displayName = displayName,
                    cssFamily = "UserFont-$safeName-$id",
                    filePath = destFile.absolutePath,
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
