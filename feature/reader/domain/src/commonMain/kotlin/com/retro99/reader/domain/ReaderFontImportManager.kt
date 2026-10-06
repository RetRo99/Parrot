package com.retro99.reader.domain

import com.retro99.base.result.AppResult
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import io.github.vinceglb.filekit.PlatformFile

interface ReaderFontImportManager {
    suspend fun importFont(platformFile: PlatformFile): AppResult<CustomReaderFontDomainModel>
}
