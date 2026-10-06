package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.retro99.base.result.AppResult
import com.retro99.reader.domain.ReaderFontImportManager
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class ImportCustomReaderFontUseCase(
    @Provided private val readerFontImportManager: ReaderFontImportManager,
    @Provided private val readerSettingsRepository: ReaderSettingsRepository,
) {
    suspend operator fun invoke(platformFile: PlatformFile): AppResult<CustomReaderFontDomainModel> {
        return readerFontImportManager.importFont(platformFile).andThen { font ->
            val existingFonts = readerSettingsRepository.getCustomFonts().first()
            readerSettingsRepository.saveCustomFonts(existingFonts + font).andThen {
                Ok(font)
            }
        }
    }
}
