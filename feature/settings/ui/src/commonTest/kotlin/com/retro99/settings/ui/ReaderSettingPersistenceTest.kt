package com.retro99.settings.ui

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.AnalyticsEvent
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.analytics.api.ReaderAnalyticsEvent
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.ReaderFontImportManager
import com.retro99.reader.domain.ReaderSettingsRepository
import com.retro99.reader.domain.model.BookmarkDomainModel
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import com.retro99.reader.domain.model.CustomReaderFontDomainModel
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.reader.domain.usecase.GetCustomReaderFontsUseCase
import com.retro99.reader.domain.usecase.GetReaderSettingsUseCase
import com.retro99.reader.domain.usecase.ImportCustomReaderFontUseCase
import com.retro99.reader.domain.usecase.SaveReaderSettingsUseCase
import com.retro99.settings.ui.model.ReaderThemeUiModel
import io.github.vinceglb.filekit.core.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSettingPersistenceTest {

    @Test
    fun failedSaveRollsBackAndRetryIsReportedAsRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeReaderSettingsRepository().apply {
                failNextSave = true
            }
            val analytics = RecordingAnalytics()
            val viewModel = createViewModel(repository, analytics)
            advanceUntilIdle()

            viewModel.onIntent(SettingsIntent.OnThemeChanged(ReaderThemeUiModel.SEPIA))
            advanceUntilIdle()

            assertEquals(ReaderThemeUiModel.SYSTEM, viewModel.currentViewState().theme)
            assertNotNull(viewModel.currentViewState().settingSaveFailureRequestId)
            assertNull(viewModel.currentViewState().undoReaderSettings)
            assertTrue(analytics.events.any { it is ReaderAnalyticsEvent.ReaderSettingSaveAttempted && !it.isRetry })
            assertTrue(analytics.events.any { it is ReaderAnalyticsEvent.ReaderSettingSaveFailed && !it.isRetry })
            assertFalse(analytics.events.any { it is ReaderAnalyticsEvent.SettingChanged })
            assertEquals(1, analytics.exceptions.size)
            assertEquals(2, analytics.breadcrumbs.count { it.operation == "reader_setting_save" })

            viewModel.onIntent(SettingsIntent.OnRetrySettingsSave)
            advanceUntilIdle()

            assertEquals(ReaderThemeUiModel.SEPIA, viewModel.currentViewState().theme)
            assertNull(viewModel.currentViewState().settingSaveFailureRequestId)
            assertTrue(analytics.events.any { it is ReaderAnalyticsEvent.ReaderSettingSaveAttempted && it.isRetry })
            assertTrue(analytics.events.any { it is ReaderAnalyticsEvent.ReaderSettingSaveSucceeded && it.isRetry })
            assertTrue(analytics.events.any { it is ReaderAnalyticsEvent.SettingChanged && it.isRetry })
            assertEquals(1, analytics.exceptions.size)
            assertEquals(4, analytics.breadcrumbs.count { it.operation == "reader_setting_save" })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun undoAfterSecondChangeRevertsOnlyTheMostRecentSetting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeReaderSettingsRepository()
            val analytics = RecordingAnalytics()
            val viewModel = createViewModel(repository, analytics)
            advanceUntilIdle()

            viewModel.onIntent(SettingsIntent.OnThemeChanged(ReaderThemeUiModel.SEPIA))
            advanceUntilIdle()
            viewModel.onIntent(SettingsIntent.OnFontSizeChanged(1.15))
            advanceUntilIdle()

            assertEquals(ReaderThemeUiModel.SEPIA, viewModel.currentViewState().theme)
            assertEquals(1.15, viewModel.currentViewState().fontSize)
            viewModel.onIntent(SettingsIntent.OnUndoSettingsChange)
            advanceUntilIdle()

            assertEquals(ReaderThemeUiModel.SEPIA, viewModel.currentViewState().theme)
            assertEquals(1.0, viewModel.currentViewState().fontSize)
            assertTrue(analytics.events.any { it is ReaderAnalyticsEvent.ReaderSettingChangeUndone })
            assertFalse(analytics.events.any {
                it is ReaderAnalyticsEvent.SettingChanged && it.settingName == "font_size" && it.newValue == "reversed"
            })
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun createViewModel(
        repository: FakeReaderSettingsRepository,
        analytics: RecordingAnalytics,
    ) = SettingsViewModel(
        getReaderSettingsUseCase = GetReaderSettingsUseCase(repository),
        saveReaderSettingsUseCase = SaveReaderSettingsUseCase(repository),
        getCustomReaderFontsUseCase = GetCustomReaderFontsUseCase(repository),
        importCustomReaderFontUseCase = ImportCustomReaderFontUseCase(
            readerFontImportManager = object : ReaderFontImportManager {
                override suspend fun importFont(platformFile: PlatformFile) =
                    Err(AppError.UnknownError(IllegalStateException("unused")))
            },
            readerSettingsRepository = repository,
        ),
        analytics = analytics,
    )

    private class RecordingAnalytics : Analytics {
        val events = mutableListOf<AnalyticsEvent>()
        val breadcrumbs = mutableListOf<DiagnosticContext>()
        val exceptions = mutableListOf<DiagnosticContext?>()

        override fun logException(throwable: Throwable, message: String?) {
            exceptions += null
        }

        override fun logException(throwable: Throwable, context: DiagnosticContext) {
            exceptions += context
        }

        override fun logBreadcrumb(context: DiagnosticContext) {
            breadcrumbs += context
        }

        override fun logEvent(event: AnalyticsEvent) {
            events += event
        }

        override fun setUserId(userId: String?) = Unit
    }

    private class FakeReaderSettingsRepository : ReaderSettingsRepository {
        private val settings = MutableStateFlow(ReaderSettingsDomainModel())
        private val customFonts = MutableStateFlow<List<CustomReaderFontDomainModel>>(emptyList())
        var failNextSave = false

        override suspend fun prepareEbook(
            bookUuid: String,
            ebookFilePath: String,
            bookType: BookType,
        ): AppResult<String> = error("unused")

        override fun getReaderSettings(): Flow<ReaderSettingsDomainModel> = settings

        override suspend fun saveReaderSettings(settings: ReaderSettingsDomainModel): CompletableResult {
            if (failNextSave) {
                failNextSave = false
                return Err(AppError.DatabaseError(IllegalStateException("fixture")))
            }
            this.settings.value = settings
            return Ok(Unit)
        }

        override fun getCustomFonts(): Flow<List<CustomReaderFontDomainModel>> = customFonts

        override suspend fun saveCustomFonts(fonts: List<CustomReaderFontDomainModel>): CompletableResult {
            customFonts.value = fonts
            return Ok(Unit)
        }

        override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean = false
        override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean = false
        override fun getCurrentlyReading(): CurrentlyReadingDomainModel? = null
        override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> = flowOf(null)
        override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) = Unit
        override fun clearCurrentlyReading() = Unit
        override suspend fun getAllPositions(): AppResult<List<PositionDomainModel>> = Ok(emptyList())
        override fun observeBookmarks(bookUuid: String): Flow<List<BookmarkDomainModel>> = flowOf(emptyList())
        override suspend fun addBookmark(bookmark: BookmarkDomainModel): CompletableResult = Ok(Unit)
        override suspend fun deleteBookmark(id: String): CompletableResult = Ok(Unit)
        override suspend fun updateBookmarkTitle(id: String, title: String): CompletableResult = Ok(Unit)
        override suspend fun updateBookmarkSortOrders(orders: List<Pair<String, Int>>): CompletableResult = Ok(Unit)
    }
}
