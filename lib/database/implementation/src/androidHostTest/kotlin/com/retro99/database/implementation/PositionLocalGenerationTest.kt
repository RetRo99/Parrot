package com.retro99.database.implementation

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.dao.books.BooksDatabaseImpl
import com.retro99.database.implementation.dao.books.BooksSqlDelightDao
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PositionLocalGenerationTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var databaseManager: DatabaseManager
    private lateinit var booksDatabase: BooksDatabaseImpl

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        databaseManager = DatabaseManager(
            userRegistry = FakeUserRegistry,
            driverFactory = object : SqlDriverFactory {
                override fun createDriver(userId: String): SqlDriver = driver
                override fun deleteUserDatabase(userId: String): Boolean = true
            },
        )
        booksDatabase = BooksDatabaseImpl(BooksSqlDelightDao(databaseManager))
    }

    @AfterTest
    fun tearDown() {
        runBlocking { databaseManager.close() }
    }

    @Test
    fun upsertPositionWithMutationStoresLocalGeneration() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            booksDatabase.upsertPositionWithMutation(
                position = TestPosition(localGeneration = 3L),
                mutation = SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_READING_POSITION,
                    entityId = "book-1",
                    operation = SyncOutboxEntry.OPERATION_UPSERT,
                    payload = "{}",
                    baseRevision = 7L,
                    localGeneration = 3L,
                ),
            )
        }

        val stored = AppDatabase(driver).positionQueries
            .getPositionByBookUuid("book-1")
            .executeAsOne()
        assertEquals(3L, stored.local_generation)
    }

    @Test
    fun remoteOriginatingDeviceSurvivesPositionDatabaseRoundTrip() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            booksDatabase.upsertRemotePosition(
                TestPosition(
                    localGeneration = 0L,
                    sourceDeviceId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                    deviceName = "Pixel Tablet",
                ),
            )
        }

        val stored = databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            booksDatabase.getRemotePositionByBookUuid("book-1")
        }
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", stored?.sourceDeviceId)
        assertEquals("Pixel Tablet", stored?.deviceName)
    }

    private data class TestPosition(
        override val localGeneration: Long,
        override val bookUuid: String = "book-1",
        override val libraryBookId: String? = "book-1",
        override val remoteRevision: Long? = 7L,
        override val timestamp: Long? = 100L,
        override val createdAt: String? = null,
        override val updatedAt: String? = "2026-09-22T10:00:00Z",
        override val locatorHref: String? = "chapter-1",
        override val locatorType: String? = "epub",
        override val locatorTitle: String? = "Chapter 1",
        override val locatorTarget: Int? = 10,
        override val audioTimestampMs: Long? = null,
        override val chapterIndex: Int? = 1,
        override val progression: Double? = 0.2,
        override val totalChapters: Int? = 10,
        override val totalDurationMs: Long? = null,
        override val totalProgression: Double? = 0.2,
        override val position: Int? = 10,
        override val sourceDeviceId: String? = null,
        override val deviceName: String? = null,
    ) : PositionEntity

    /** Never emits, so [DatabaseManager] only opens the database via [DatabaseManager.withProfile]. */
    private object FakeUserRegistry : UserRegistry {
        override fun observeAllProfiles(): Flow<List<UserProfile>> = emptyFlow()
        override suspend fun getAllProfiles(): List<UserProfile> = emptyList()
        override suspend fun createProfile(id: String?, name: String, avatarId: Int?): UserProfile =
            error("Not used")
        override suspend fun updateProfile(profile: UserProfile) = Unit
        override suspend fun deleteProfile(profileId: String) = Unit
        override suspend fun getProfile(profileId: String): UserProfile? = null
        override fun observeActiveProfile(): Flow<UserProfile?> = emptyFlow()
        override suspend fun getActiveProfile(): UserProfile? = null
        override fun getActiveProfileId(): String? = null
        override suspend fun setActiveProfile(profileId: String) = Unit
        override suspend fun clearActiveProfile() = Unit
        override suspend fun hasProfiles(): Boolean = false
        override fun isProfileActive(): Boolean = false
    }
}
