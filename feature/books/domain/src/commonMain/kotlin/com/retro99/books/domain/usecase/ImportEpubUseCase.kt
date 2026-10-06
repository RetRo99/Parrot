package com.retro99.books.domain.usecase

import com.retro99.base.result.AppResult
import com.retro99.books.domain.FileImportManager
import com.retro99.books.domain.ImportedBookFile
import io.github.vinceglb.filekit.PlatformFile
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for importing an EPUB file into your library.
 */
@Factory
class ImportEpubUseCase(
    @Provided private val fileImportManager: FileImportManager,
) {
    suspend operator fun invoke(platformFile: PlatformFile): AppResult<ImportedBookFile> {
        return fileImportManager.importEpubFile(platformFile)
    }
}
