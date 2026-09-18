package com.retro99.reader.data.source

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.model.BookType
import com.retro99.database.api.DatabaseExecutor
import com.retro99.database.api.books.BookmarkEntity
import com.retro99.database.api.books.BookmarkMutation
import com.retro99.database.api.books.BookmarksDatabase
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.reader.ReaderSettingsDatabase
import com.retro99.database.api.reader.ReaderSettingsEntity
import com.retro99.database.api.reader.ReaderSettingsMutation
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.getObject
import com.retro99.preferences.api.observeObject
import com.retro99.preferences.api.putObject
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.RemoveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.data.model.BookmarkLocalModel
import com.retro99.reader.data.model.CurrentlyReadingLocalModel
import com.retro99.reader.data.model.CustomReaderFontLocalModel
import com.retro99.reader.data.model.PositionLocalModel
import com.retro99.reader.data.model.ReaderSettingsJsonCodec
import com.retro99.reader.data.model.ReaderSettingsLocalModel
import com.retro99.reader.data.model.toDomain
import com.retro99.reader.data.model.toLocal
import com.retro99.reader.data.model.toLocalModel
import com.retro99.reader.domain.model.CurrentlyReadingDomainModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * Implementation of [ReaderLocalSource].
 * Handles reading progress, settings, and file caching.
 *
 * Note: Publication opening/closing is handled by [EpubReaderController] in the UI layer.
 */
@Single(binds = [ReaderLocalSource::class])
class ReaderLocalDataSource(
    @Provided private val preferences: Preferences,
    @Provided private val fileDownloader: EbookFileDownloader,
    @Provided private val positionDatabase: PositionDatabase,
    @Provided private val bookmarksDatabase: BookmarksDatabase,
    @Provided private val readerSettingsDatabase: ReaderSettingsDatabase,
    @Provided private val databaseExecutor: DatabaseExecutor,
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val observeUserPreferenceUseCase: ObserveUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val removeUserPreferenceUseCase: RemoveUserPreferenceUseCase,
) : ReaderLocalSource {

    private val legacySettingsMigrationMutex = Mutex()

    @Volatile
    private var legacySettingsMigrated = false

    override suspend fun getReadingProgress(
        bookUuid: String,
    ): AppResult<PositionLocalModel?> {
        return databaseExecutor.executeDatabaseOperation {
            positionDatabase.getPositionByBookUuid(bookUuid)?.toLocalModel()
        }
    }

    override suspend fun saveReadingProgress(
        progress: PositionLocalModel,
    ): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            positionDatabase.upsertPosition(progress)
        }
    }

    override fun getReaderSettings(): Flow<ReaderSettingsLocalModel> {
        return flow {
            migrateLegacyReaderSettings()
            emitAll(
                readerSettingsDatabase.observeAll()
                    .map { entries -> ReaderSettingsJsonCodec.decode(entries) },
            )
        }
    }

    override suspend fun saveReaderSettings(
        settings: ReaderSettingsLocalModel,
    ): CompletableResult {
        migrateLegacyReaderSettings()
        return databaseExecutor.executeDatabaseOperation {
            val changedSettings = ReaderSettingsJsonCodec.encodeDiff(
                settings = settings,
                stored = readerSettingsDatabase.getAll(),
            )
            if (changedSettings.isNotEmpty()) {
                readerSettingsDatabase.upsertSettingsWithMutations(
                    changedSettings.map { entity -> entity.toReaderSettingsMutation() },
                )
            }
        }
    }

    override fun getCustomFonts(): Flow<List<CustomReaderFontLocalModel>> {
        return preferences.observeObject<List<CustomReaderFontLocalModel>>(PreferencesKey.ReaderCustomFonts)
            .map { fonts -> fonts ?: emptyList() }
    }

    override suspend fun saveCustomFonts(fonts: List<CustomReaderFontLocalModel>): CompletableResult {
        preferences.putObject(PreferencesKey.ReaderCustomFonts, fonts)
        return Ok(Unit)
    }

    override suspend fun isEbookCached(bookUuid: String, bookType: BookType): Boolean {
        return fileDownloader.isEbookCached(bookUuid, bookType)
    }

    override suspend fun getCachedEbookPath(bookUuid: String, bookType: BookType): String? {
        return fileDownloader.getCachedEbookPath(bookUuid, bookType)
    }

    override suspend fun deleteEbookCache(bookUuid: String, bookType: BookType): Boolean {
        return fileDownloader.deleteEbookCache(bookUuid, bookType)
    }

    override fun getCurrentlyReading(): CurrentlyReadingDomainModel? {
        return getUserPreferenceUseCase<CurrentlyReadingLocalModel>(PreferencesKey.CurrentlyReading)?.toDomain()
    }

    override fun observeCurrentlyReading(): Flow<CurrentlyReadingDomainModel?> {
        return observeUserPreferenceUseCase<CurrentlyReadingLocalModel>(PreferencesKey.CurrentlyReading)
            .map { localModel -> localModel?.toDomain() }
    }

    override fun setCurrentlyReading(currentlyReading: CurrentlyReadingDomainModel) {
        saveUserPreferenceUseCase(PreferencesKey.CurrentlyReading, currentlyReading.toLocal())
    }

    override fun clearCurrentlyReading() {
        removeUserPreferenceUseCase(PreferencesKey.CurrentlyReading)
    }

    override suspend fun getAllPositions(): AppResult<List<PositionLocalModel>> {
        return databaseExecutor.executeDatabaseOperation {
            positionDatabase.getAllPositions().map { it.toLocalModel() }
        }
    }

    override fun observeBookmarks(bookUuid: String): Flow<List<BookmarkLocalModel>> {
        return bookmarksDatabase.observeBookmarks(bookUuid)
            .map { bookmarks -> bookmarks.map { it.toLocalModel() } }
    }

    override suspend fun addBookmark(bookmark: BookmarkLocalModel): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            bookmarksDatabase.upsertBookmarksWithMutations(
                listOf(bookmark.toBookmarkMutation()),
            )
        }
    }

    override suspend fun deleteBookmark(id: String): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            val bookmark = bookmarksDatabase.getBookmark(id)
            if (bookmark != null && bookmark.deletedAt == null) {
                val deletedBookmark = bookmark.toLocalModel().copy(
                    deletedAt = Clock.System.now().toString(),
                )
                bookmarksDatabase.upsertBookmarksWithMutations(
                    listOf(deletedBookmark.toBookmarkMutation()),
                )
            }
        }
    }

    override suspend fun updateBookmarkTitle(id: String, title: String): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            val bookmark = bookmarksDatabase.getBookmark(id)
            if (bookmark != null && bookmark.deletedAt == null) {
                val updatedBookmark = bookmark.toLocalModel().copy(
                    locatorTitle = title,
                )
                bookmarksDatabase.upsertBookmarksWithMutations(
                    listOf(updatedBookmark.toBookmarkMutation()),
                )
            }
        }
    }

    override suspend fun updateBookmarkSortOrders(orders: List<Pair<String, Int>>): CompletableResult {
        return databaseExecutor.executeDatabaseOperation {
            val mutations = orders.mapNotNull { (bookmarkId, sortOrder) ->
                val bookmark = bookmarksDatabase.getBookmark(bookmarkId)
                if (bookmark == null || bookmark.deletedAt != null) {
                    null
                } else {
                    bookmark.toLocalModel()
                        .copy(sortOrder = sortOrder)
                        .toBookmarkMutation()
                }
            }
            if (mutations.isNotEmpty()) {
                bookmarksDatabase.upsertBookmarksWithMutations(mutations)
            }
        }
    }

    private suspend fun migrateLegacyReaderSettings() {
        if (legacySettingsMigrated) {
            return
        }
        legacySettingsMigrationMutex.withLock {
            if (legacySettingsMigrated) {
                return
            }
            val legacySettings = preferences.getObject<ReaderSettingsLocalModel>(PreferencesKey.ReaderSettings)
            val userScopedSettings = getUserPreferenceUseCase<ReaderSettingsLocalModel>(PreferencesKey.ReaderSettings)

            if (readerSettingsDatabase.getAll().isEmpty()) {
                val settings = userScopedSettings ?: legacySettings
                if (settings != null) {
                    readerSettingsDatabase.upsertSettings(ReaderSettingsJsonCodec.encodeAll(settings))
                }
            }

            if (legacySettings != null) {
                preferences.remove(PreferencesKey.ReaderSettings)
            }
            if (userScopedSettings != null) {
                removeUserPreferenceUseCase(PreferencesKey.ReaderSettings)
            }
            legacySettingsMigrated = true
        }
    }
}

private val syncPayloadJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

private fun BookmarkEntity.toBookmarkMutation(): BookmarkMutation {
    return BookmarkMutation(
        bookmark = this,
        outboxEntry = toSyncOutboxEntry(),
    )
}

private fun BookmarkEntity.toSyncOutboxEntry(): SyncOutboxEntry {
    return SyncOutboxEntry.new(
        entityType = SyncOutboxEntry.ENTITY_TYPE_BOOKMARK,
        entityId = id,
        operation = if (deletedAt == null) {
            SyncOutboxEntry.OPERATION_UPSERT
        } else {
            SyncOutboxEntry.OPERATION_DELETE
        },
        payload = syncPayloadJson.encodeToString(
            LocalBookmarkMutation(
                bookmarkId = id,
                bookUuid = bookUuid,
                locatorHref = locatorHref,
                locatorType = locatorType,
                locatorTitle = locatorTitle,
                progression = progression,
                totalProgression = totalProgression,
                chapterIndex = chapterIndex,
                position = position,
                createdAt = createdAt,
                sortOrder = sortOrder,
                remoteRevision = remoteRevision,
                deletedAt = deletedAt,
            ),
        ),
        baseRevision = remoteRevision,
    )
}

private fun ReaderSettingsEntity.toReaderSettingsMutation(): ReaderSettingsMutation {
    return ReaderSettingsMutation(
        settings = this,
        outboxEntry = SyncOutboxEntry.new(
            entityType = SyncOutboxEntry.ENTITY_TYPE_READER_SETTINGS,
            entityId = key,
            operation = SyncOutboxEntry.OPERATION_UPSERT,
            payload = value,
            baseRevision = remoteRevision,
        ),
    )
}

@Serializable
private data class LocalBookmarkMutation(
    @SerialName("bookmark_id")
    val bookmarkId: String,
    @SerialName("book_uuid")
    val bookUuid: String,
    @SerialName("locator_href")
    val locatorHref: String,
    @SerialName("locator_type")
    val locatorType: String?,
    @SerialName("locator_title")
    val locatorTitle: String?,
    val progression: Double?,
    @SerialName("total_progression")
    val totalProgression: Double?,
    @SerialName("chapter_index")
    val chapterIndex: Int?,
    val position: Int?,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("sort_order")
    val sortOrder: Int,
    @SerialName("remote_revision")
    val remoteRevision: Long?,
    @SerialName("deleted_at")
    val deletedAt: String?,
)
