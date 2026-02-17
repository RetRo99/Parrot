package com.retro99.reader.data

import com.retro99.base.result.AppResult

/**
 * Service for extracting metadata from EPUB files.
 */
interface EpubMetadataExtractor {
    /**
     * Extracts metadata from an EPUB file.
     *
     * @param filePath The path to the EPUB file
     * @return The extracted metadata, or an error if extraction fails
     */
    suspend fun extractMetadata(filePath: String): AppResult<EpubMetadata>
}

