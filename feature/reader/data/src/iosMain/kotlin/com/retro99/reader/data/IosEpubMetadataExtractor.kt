package com.retro99.reader.data

import com.github.michaelbull.result.Err
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import org.koin.core.annotation.Single

/**
 * iOS implementation of [EpubMetadataExtractor].
 *
 * Note: This is a stub implementation. Full iOS support would require
 * a Swift bridge similar to EpubReaderBridge to use Readium Swift.
 * For now, this extracts basic metadata from the file name.
 */
@Single(binds = [EpubMetadataExtractor::class])
class IosEpubMetadataExtractor : EpubMetadataExtractor {

    override suspend fun extractMetadata(filePath: String): AppResult<EpubMetadata> {
        // Extract basic metadata from file name
        val fileName = filePath.substringAfterLast("/").substringBeforeLast(".")
        
        // Try to parse "Author - Title" format, otherwise use filename as title
        val parts = fileName.split(" - ", limit = 2)
        val (author, title) = if (parts.size == 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            null to fileName
        }

        return com.github.michaelbull.result.Ok(
            EpubMetadata(
                title = title,
                author = author,
                description = null,
                coverBytes = null,
            )
        )
    }
}

