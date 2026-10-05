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
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

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

    @Test
    fun acceptingRemoteAtomicallyClearsEveryProgressStateAndBaseline() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            val queries = AppDatabase(driver).syncOutboxQueries
            booksDatabase.upsertPosition(TestPosition(localGeneration = 3L))
            booksDatabase.upsertRemotePosition(TestPosition(localGeneration = 0L, remoteRevision = 12L))
            listOf("pending", "dispatched", "conflict_preserved").forEach { state ->
                queries.enqueueMutation("write-$state", "server", "reading_position", "book-1", "upsert", "{}", 7L, 3L, state, "now", 0L, null, null)
            }
            queries.enqueueMutation("other-book", "server", "reading_position", "book-2", "upsert", "{}", null, 1L, "pending", "now", 0L, null, null)
            queries.enqueueMutation("bookmark", "server", "saved_item", "book-1", "upsert", "{}", null, 1L, "pending", "now", 0L, null, null)

            assertTrue(booksDatabase.resolvePositionConflict(
                TestPosition(localGeneration = 4L, remoteRevision = 12L, totalProgression = 0.7),
                null, 3L, "server",
            ))
            assertEquals(setOf("other-book", "bookmark"), queries.getAllMutations().executeAsList().map { it.mutation_id }.toSet())
            assertNull(booksDatabase.getRemotePositionByBookUuid("book-1"))
            assertEquals(12L, booksDatabase.getPositionByBookUuid("book-1")?.remoteRevision)
            assertEquals(0.7, booksDatabase.getPositionByBookUuid("book-1")?.totalProgression)
        }
    }

    @Test
    fun choosingLocalAtomicallyQueuesCorrectRevisionAndKeepsOtherDestinations() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            val queries = AppDatabase(driver).syncOutboxQueries
            booksDatabase.upsertPosition(TestPosition(localGeneration = 3L))
            booksDatabase.upsertRemotePosition(TestPosition(localGeneration = 0L))
            queries.enqueueMutation("old", "server", "reading_position", "book-1", "upsert", "{}", 7L, 3L, "conflict_preserved", "now", 0L, null, null)
            queries.enqueueMutation("other-server", "abs", "reading_position", "book-1", "upsert", "{}", null, 1L, "pending", "now", 0L, null, null)
            val replacement = SyncOutboxEntry.new(
                "reading_position", "book-1", "upsert", "{}", cloudUserId = "server", baseRevision = 12L, localGeneration = 4L,
            )
            assertTrue(booksDatabase.resolvePositionConflict(TestPosition(localGeneration = 4L, remoteRevision = 12L), replacement, 3L, "server"))
            val entries = queries.getAllMutations().executeAsList()
            assertEquals(setOf("other-server", replacement.mutationId), entries.map { it.mutation_id }.toSet())
            assertEquals(12L, entries.last().base_revision)
            assertEquals(4L, entries.last().local_generation)
            assertNull(booksDatabase.getRemotePositionByBookUuid("book-1"))
        }
    }

    @Test
    fun staleChoiceLeavesPositionBaselineAndQueueUntouched() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            val write = SyncOutboxEntry.new("reading_position", "book-1", "upsert", "{}", localGeneration = 4L)
            booksDatabase.upsertPositionWithMutation(TestPosition(localGeneration = 4L), write)
            booksDatabase.upsertRemotePosition(TestPosition(localGeneration = 0L, remoteRevision = 12L))
            assertFalse(booksDatabase.resolvePositionConflict(TestPosition(localGeneration = 4L, totalProgression = 0.8), null, 3L, null))
            assertEquals(0.2, booksDatabase.getPositionByBookUuid("book-1")?.totalProgression)
            assertEquals(12L, booksDatabase.getRemotePositionByBookUuid("book-1")?.remoteRevision)
            assertEquals(write.mutationId, AppDatabase(driver).syncOutboxQueries.getAllMutations().executeAsOne().mutation_id)
        }
    }

    @Test
    fun libraryChoiceClearsBoundAndUnboundProgressButNotUnrelatedEntities() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            val queries = AppDatabase(driver).syncOutboxQueries
            booksDatabase.upsertPosition(TestPosition(localGeneration = 3L))
            queries.enqueueMutation("bound", "cloud-user", "reading_position", "book-1", "upsert", "{}", null, 3L, "pending", "now", 0L, null, null)
            queries.enqueueMutation("unbound", null, "reading_position", "book-1", "upsert", "{}", null, 3L, "dispatched", "now", 0L, null, null)
            queries.enqueueMutation("other", null, "reading_position", "book-2", "upsert", "{}", null, 3L, "pending", "now", 0L, null, null)
            assertTrue(booksDatabase.resolvePositionConflict(TestPosition(localGeneration = 4L), null, 3L, null))
            assertEquals("other", queries.getAllMutations().executeAsOne().mutation_id)
        }
    }

    @Test
    fun missingPositionCannotConsumePendingChanges() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            assertFalse(booksDatabase.resolvePositionConflict(TestPosition(localGeneration = 1L), null, 0L, null))
            assertNull(booksDatabase.getPositionByBookUuid("book-1"))
        }
    }

    @Test
    fun acceptingRemotePreservesDeviceMetadataAndAudioBookTime() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            booksDatabase.upsertPosition(TestPosition(localGeneration = 3L))
            assertTrue(booksDatabase.resolvePositionConflict(TestPosition(
                localGeneration = 4L, deviceName = "Other tablet", sourceDeviceId = "device", bookTimeMs = 90_000L,
            ), null, 3L, null))
            val stored = booksDatabase.getPositionByBookUuid("book-1")!!
            assertEquals("Other tablet", stored.deviceName)
            assertEquals("device", stored.sourceDeviceId)
            assertEquals(90_000L, stored.bookTimeMs)
        }
    }

    @Test
    fun failedReplacementRollsBackPositionQueueDeletionAndBaselineDeletion() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            val queries = AppDatabase(driver).syncOutboxQueries
            booksDatabase.upsertPosition(TestPosition(localGeneration = 3L))
            booksDatabase.upsertRemotePosition(TestPosition(localGeneration = 0L, remoteRevision = 12L))
            queries.enqueueMutation("old", "server", "reading_position", "book-1", "upsert", "{}", null, 3L, "conflict_preserved", "now", 0L, null, null)
            queries.enqueueMutation("duplicate", "server", "saved_item", "other", "upsert", "{}", null, 1L, "pending", "now", 0L, null, null)
            val invalid = SyncOutboxEntry.new("reading_position", "book-1", "upsert", "{}", cloudUserId = "server")
                .copy(mutationId = "duplicate")
            assertFailsWith<Exception> {
                booksDatabase.resolvePositionConflict(TestPosition(localGeneration = 4L, totalProgression = 0.8), invalid, 3L, "server")
            }
            assertEquals(0.2, booksDatabase.getPositionByBookUuid("book-1")?.totalProgression)
            assertEquals(3L, booksDatabase.getPositionByBookUuid("book-1")?.localGeneration)
            assertEquals(12L, booksDatabase.getRemotePositionByBookUuid("book-1")?.remoteRevision)
            assertEquals(setOf("old", "duplicate"), queries.getAllMutations().executeAsList().map { it.mutation_id }.toSet())
        }
    }

    @Test
    fun writersThatReadTheSameGenerationReceiveDistinctGenerationsAtCommit() = runBlocking {
        databaseManager.withProfile(UserRegistry.DEFAULT_USER_ID) {
            booksDatabase.upsertPosition(TestPosition(localGeneration = 3L))
            val first = SyncOutboxEntry.new("reading_position", "book-1", "upsert", "{}", localGeneration = 4L)
            val second = SyncOutboxEntry.new("reading_position", "book-1", "upsert", "{}", localGeneration = 4L)
            booksDatabase.upsertPositionWithMutation(TestPosition(localGeneration = 4L), first)
            booksDatabase.upsertPositionWithMutation(TestPosition(localGeneration = 4L, totalProgression = 0.8), second)
            assertEquals(5L, booksDatabase.getPositionByBookUuid("book-1")?.localGeneration)
            assertEquals(5L, AppDatabase(driver).syncOutboxQueries.getAllMutations().executeAsOne().local_generation)
            assertFalse(booksDatabase.resolvePositionConflict(TestPosition(localGeneration = 5L), null, 4L, null))
            assertEquals(0.8, booksDatabase.getPositionByBookUuid("book-1")?.totalProgression)
        }
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
        override val bookTimeMs: Long? = null,
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
