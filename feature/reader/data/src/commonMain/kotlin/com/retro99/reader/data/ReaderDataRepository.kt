package com.retro99.reader.data

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onFailure
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.reader.data.model.toDomain
import com.retro99.reader.data.model.toLocal
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.data.source.ReaderLocalSource
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [ReaderSettingsRepository::class])
internal class ReaderDataRepository(
    @Provided private val localSource: ReaderLocalSource,
    @Provided private val analytics: Analytics,
) : ReaderSettingsRepository {

    override suspend fun prepareEbook(
        bookUuid: String,
        ebookFilePath: String,
        bookType: BookType,
    ): AppResult<String> {
        // Return cached path if available.
        // Downloads are handled by BookDownloadManager which provides progress tracking
        // and background download support. This method should only be called after
        // the download is complete (i.e., when DownloadState is Cached).
        val cachedPath = localSource.getCachedEbookPath(bookUuid, bookType)

        return if (cachedPath != null) {
            Ok(cachedPath)
        } else {
            Err(
                AppError.UnknownError(
                    IllegalStateException(
                        "Ebook not cached. Use BookDownloadManager to download first."
                    )
                )
            )
        }
    }

    override fun getReaderSettings(): Flow<ReaderSettingsDomainModel> {
        return localSource.getReaderSettings().map { it.toDomain() }
    }

    override suspend fun saveReaderSettings(
        settings: ReaderSettingsDomainModel,
    ): CompletableResult {
        // Reader Settings owns the user-facing operation boundary and reports one contextual
        // diagnostic after observing this result. Do not report again at this repository layer.
        return localSource.saveReaderSettings(settings.toLocal())
    }

    override fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>> {
        return localSource.getCustomFonts().map { fonts ->
            fonts.map { it.toDomain() }
        }
    }

    override suspend fun saveCustomFonts(
        fonts: List<CustomReaderFontDomainModel>,
    ): CompletableResult {
        return localSource.saveCustomFonts(fonts.map { it.toLocal() }).onFailure { error ->
            logError(error, "Failed to save custom reader fonts")
        }
    }

    override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean {
        return localSource.isEbookCached(bookUuid, bookType)
    }

    override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean {
        return localSource.deleteEbookCache(bookUuid, bookType)
    }

    override suspend fun getCachedMediaPath(bookUuid: String, bookType: BookType): String? {
        return localSource.getCachedEbookPath(bookUuid, bookType)
    }

    override fun getCurrentlyReading(): CurrentlyReadingDomainModel? {
        return localSource.getCurrentlyReading()
    }

    override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> {
        return localSource.observeCurrentlyReading()
    }

    override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) {
        localSource.setCurrentlyReading(currentlyReading)
    }

    override fun clearCurrentlyReading() {
        localSource.clearCurrentlyReading()
    }

    override suspend fun getAllPositions(): AppResult<List<PositionDomainModel>> {
        return localSource.getAllPositions().map { positions ->
            positions.map { it.toDomain(serverId = "") }
        }
    }


    private fun logError(error: AppError, message: String) {
        val throwable = when (error) {
            is AppError.NetworkError -> error.throwable
            is AppError.DatabaseError -> error.throwable
            is AppError.UnknownError -> error.throwable
            is AppError.ApiError -> Exception("API Error ${error.code}: ${error.message}")
            is AppError.AuthError -> Exception("Auth Error: ${error.message}")
            is AppError.NotFoundError -> Exception("Not Found: ${error.message}")
        }
        analytics.logException(throwable, message)
    }
}
