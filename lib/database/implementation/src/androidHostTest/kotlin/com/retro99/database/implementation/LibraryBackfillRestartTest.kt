package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibrarySourceSnapshotsDatabase
import com.retro99.database.implementation.dao.library.LibrarySourceKeyCodec
import com.retro99.database.implementation.dao.library.LibrarySourceSnapshotsSqlDelightDao
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LibraryBackfillRestartTest {
    @Test
    fun backfillResumesAfterDatabaseReopenWithoutLosingSnapshotsOrMemberships() = runBlocking {
        // Given
        val databaseFile = File.createTempFile("library-backfill-restart", ".db")
        var firstDriver: JdbcSqliteDriver? = null
        var restartedDriver: JdbcSqliteDriver? = null
        try {
            databaseFile.delete()
            firstDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
            AppDatabase.Schema.create(firstDriver)
            val firstDatabase = AppDatabase(firstDriver)
            val firstDao = sourceSnapshotsDao(firstDatabase)
            val legacyIds = (0..100).associate { index ->
                val nativeBookId = "book-${index.toString().padStart(3, '0')}"
                val legacyId = "legacy-$nativeBookId"
                insertSnapshot(firstDatabase, nativeBookId, legacyId, index.toLong())
                nativeBookId to legacyId
            }

            // When
            val firstBatch = firstDao.backfillGroups(
                profileId = LibraryProfileId(PROFILE_ID),
                migrationId = MIGRATION_ID,
                startedAt = STARTED_AT,
                completedAt = COMPLETED_AT,
            )
            val checkpointedMemberships = firstDatabase.libraryGroupQueries
                .getAllLibraryGroupMemberships(PROFILE_ID)
                .executeAsList()
                .associate { membership -> membership.native_book_id to membership.group_id }
            assertEquals(100, firstBatch.processedSnapshots)
            assertFalse(firstBatch.isComplete)
            assertEquals(100, checkpointedMemberships.size)

            // Simulate process death and reopen the same on-disk database.
            firstDriver.close()
            firstDriver = null
            restartedDriver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")
            val restartedDatabase = AppDatabase(restartedDriver)
            val restartedDao = sourceSnapshotsDao(restartedDatabase)

            val resumedBatch = restartedDao.backfillGroups(
                profileId = LibraryProfileId(PROFILE_ID),
                migrationId = MIGRATION_ID,
                startedAt = "2026-09-25T00:00:00Z",
                completedAt = RESUMED_COMPLETED_AT,
            )
            val replay = restartedDao.backfillGroups(
                profileId = LibraryProfileId(PROFILE_ID),
                migrationId = MIGRATION_ID,
                startedAt = "2026-09-25T00:02:00Z",
                completedAt = "2026-09-25T00:03:00Z",
            )

            // Then
            assertEquals(1, resumedBatch.processedSnapshots)
            assertTrue(resumedBatch.isComplete)
            assertEquals(0, replay.processedSnapshots)
            assertTrue(replay.isComplete)

            val persistedSnapshots = restartedDatabase.librarySourceSnapshotQueries
                .getLibrarySourceSnapshots(PROFILE_ID)
                .executeAsList()
            val persistedMemberships = restartedDatabase.libraryGroupQueries
                .getAllLibraryGroupMemberships(PROFILE_ID)
                .executeAsList()
            val finalMemberships = persistedMemberships.associate { membership ->
                membership.native_book_id to membership.group_id
            }
            val migrationState = restartedDatabase.librarySourceSnapshotQueries
                .getLibraryMigrationState(PROFILE_ID, MIGRATION_ID)
                .executeAsOne()

            assertEquals(101, persistedSnapshots.size)
            assertEquals(101, persistedMemberships.size)
            assertEquals(101, restartedDatabase.libraryGroupQueries
                .getLibraryGroupsForProfile(PROFILE_ID).executeAsList().size)
            assertEquals(checkpointedMemberships, finalMemberships.filterKeys { bookId ->
                bookId != "book-100"
            })
            assertEquals(legacyIds, persistedMemberships.associate { membership ->
                membership.native_book_id to membership.legacy_library_book_id
            })
            assertNotNull(migrationState.last_source_key)
            assertEquals(RESUMED_COMPLETED_AT, migrationState.completed_at)
        } finally {
            firstDriver?.close()
            restartedDriver?.close()
            databaseFile.delete()
            File("${databaseFile.absolutePath}-journal").delete()
        }
    }

    private fun sourceSnapshotsDao(database: AppDatabase): LibrarySourceSnapshotsDatabase =
        LibrarySourceSnapshotsSqlDelightDao(ActiveProfileSession(PROFILE_ID)) { database }

    private fun insertSnapshot(
        database: AppDatabase,
        nativeBookId: String,
        legacyId: String,
        observedAtEpochMs: Long,
    ) {
        val sourceKey = LibrarySourceKeyCodec.encode(
            SourceBookKey(
                profileId = LibraryProfileId(PROFILE_ID),
                adapterId = LibraryAdapterId("test-adapter"),
                accountIdentity = SourceAccountIdentity.Portable("backend-a", "account-a"),
                nativeBookId = NativeBookId(nativeBookId),
            ),
        )
        database.librarySourceSnapshotQueries.insertLibrarySourceSnapshot(
            profile_id = PROFILE_ID,
            source_key = sourceKey,
            execution_connection_id = "connection-a",
            legacy_library_book_id = legacyId,
            title = "Title $nativeBookId",
            description = null,
            cover_reference = null,
            publication_date = null,
            observed_at_epoch_ms = observedAtEpochMs,
            observed_at_submillisecond_ns = 0,
            presence = "Present",
            is_authoritative = 1L,
            revision = null,
        )
    }

    private class ActiveProfileSession(
        private val profileId: String,
    ) : ProfileDatabaseSession {
        override suspend fun <T> withProfile(
            localProfileId: String,
            operation: suspend () -> T,
        ): T {
            check(localProfileId == profileId)
            return operation()
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-a"
        const val MIGRATION_ID = "group-backfill-v1"
        const val STARTED_AT = "2026-09-24T00:03:00Z"
        const val COMPLETED_AT = "2026-09-24T00:04:00Z"
        const val RESUMED_COMPLETED_AT = "2026-09-25T00:01:00Z"
    }
}
