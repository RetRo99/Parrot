package com.retro99.epub.api

import com.retro99.base.result.AppResult

/**
 * Reads an EPUB's full spine, the way an EPUB CFI counts it: its `/6/N` step addresses every
 * `<itemref>` in `<spine>`, including `linear="no"` ones such as a cover or notes, which the
 * text reading order ([EpubTextReader]) leaves out.
 */
interface EpubSpineReader {
    /**
     * Every itemref's resource in spine order, named like [EpubChapterText.href] (its path
     * inside the archive); an error when the file can't be read.
     */
    suspend fun readSpineHrefs(filePath: String): AppResult<List<String>>
}
