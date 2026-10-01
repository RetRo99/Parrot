package com.retro99.books.data

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import org.koin.core.annotation.Single
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalForeignApi::class)
@Single(binds = [EpubMetadataExtractor::class])
class IosEpubMetadataExtractor : EpubMetadataExtractor {

    override suspend fun extractMetadata(filePath: String): AppResult<EpubMetadata> {
        val bridge = EpubMetadataBridgeRegistry.getBridge()
            ?: return Ok(fallbackFromFilename(filePath))

        return suspendCoroutine { continuation ->
            bridge.extractMetadata(filePath) { result ->
                if (result == null) {
                    continuation.resume(Ok(fallbackFromFilename(filePath)))
                } else {
                    val coverBytes = result.coverFilePath?.let { path ->
                        readPngFile(path)
                    }
                    continuation.resume(
                        Ok(
                            EpubMetadata(
                                title = result.title,
                                author = result.author,
                                description = result.description,
                                coverBytes = coverBytes,
                                hasMediaOverlays = result.hasMediaOverlays,
                                publicationDate = result.publicationDate,
                                isbn = isbnFromIdentifier(result.identifier),
                            )
                        )
                    )
                }
            }
        }
    }

    private fun readPngFile(path: String): ByteArray? {
        val nsData = NSData.dataWithContentsOfFile(path) ?: return null
        val length = nsData.length.toInt()
        if (length == 0) return ByteArray(0)
        val ptr = nsData.bytes?.reinterpret<ByteVar>() ?: return null
        return ptr.readBytes(length)
    }

    private fun fallbackFromFilename(filePath: String): EpubMetadata {
        val fileName = filePath.substringAfterLast("/").substringBeforeLast(".")
        val parts = fileName.split(" - ", limit = 2)
        val (author, title) = if (parts.size == 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to fileName
        }
        return EpubMetadata(
            title = title,
            author = author,
            description = null,
            coverBytes = null,
            hasMediaOverlays = false,
            publicationDate = null,
        )
    }
}
