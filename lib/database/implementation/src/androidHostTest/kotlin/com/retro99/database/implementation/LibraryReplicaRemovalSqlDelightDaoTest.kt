package com.retro99.database.implementation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.library.LibraryReplicaRemovalDatabase
import com.retro99.database.api.library.LibraryReplicaRemovalIntent
import com.retro99.database.api.library.LibraryReplicaRemovalPhase
import com.retro99.database.implementation.dao.library.LibraryReplicaRemovalSqlDelightDao
import com.retro99.server.api.library.DeviceStorageRef
import com.retro99.server.api.library.LibraryAdapterId
import com.retro99.server.api.library.LibraryGroupId
import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.MediaAssetId
import com.retro99.server.api.library.NativeBookId
import com.retro99.server.api.library.SourceAccountIdentity
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceBookRef
import com.retro99.server.api.library.SourceConnectionId
import com.retro99.server.api.library.SourceResourceRef
import com.retro99.server.api.library.StorageReplicaId
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LibraryReplicaRemovalSqlDelightDaoTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: AppDatabase
    private lateinit var session: ActiveProfileSession
    private lateinit var classUnderTest: LibraryReplicaRemovalDatabase

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        database = AppDatabase(driver)
        session = ActiveProfileSession("profile-a")
        classUnderTest = LibraryReplicaRemovalSqlDelightDao(session) { database }
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun intentRoundTripsAndReplaysMonotonicPhases() = runBlocking {
        // Given
        val intent = intent()

        // When
        val first = classUnderTest.beginRemoval(intent)
        val replay = classUnderTest.beginRemoval(intent.copy(operationId = "operation-replay"))
        classUnderTest.markRemovalEffectApplied(first.profileId, first.operationId)
        val afterEffectApplied = classUnderTest.getRemoval(first.profileId, first.operationId)
        classUnderTest.markRemovalEffectApplied(first.profileId, first.operationId)
        classUnderTest.completeRemoval(first.profileId, first.operationId)
        classUnderTest.completeRemoval(first.profileId, first.operationId)

        // Then
        assertEquals(intent, first)
        assertEquals(first, replay)
        assertEquals(LibraryReplicaRemovalPhase.EffectApplied, afterEffectApplied?.phase)
        assertEquals(
            LibraryReplicaRemovalPhase.Completed,
            classUnderTest.getRemoval(first.profileId, first.operationId)?.phase,
        )
        assertEquals(emptyList(), classUnderTest.getPendingRemovals(first.profileId))
        assertNull(classUnderTest.getRemoval(first.profileId, "operation-replay"))
    }

    @Test
    fun operationIdCannotBeReusedForADifferentTarget() = runBlocking {
        // Given
        val intent = classUnderTest.beginRemoval(intent())

        // When / Then
        assertFailsWith<IllegalStateException> {
            classUnderTest.beginRemoval(
                intent.copy(storageRef = DeviceStorageRef("different-path")),
            )
        }
        Unit
    }

    @Test
    fun removalIntentsRemainScopedToTheActiveProfile() = runBlocking {
        // Given
        val intent = intent()
        classUnderTest.beginRemoval(intent)

        // When / Then
        assertFailsWith<IllegalStateException> {
            classUnderTest.getRemoval(LibraryProfileId("profile-b"), intent.operationId)
        }
        assertFailsWith<IllegalStateException> {
            classUnderTest.getPendingRemovals(LibraryProfileId("profile-b"))
        }
        Unit
    }

    private fun intent() = LibraryReplicaRemovalIntent(
        operationId = "operation-1",
        groupId = LibraryGroupId("group-1"),
        source = SourceBookRef(
            key = SourceBookKey(
                profileId = LibraryProfileId("profile-a"),
                adapterId = LibraryAdapterId("local"),
                accountIdentity = SourceAccountIdentity.Unresolved(
                    SourceConnectionId("connection-local"),
                ),
                nativeBookId = NativeBookId("import-1"),
            ),
            connectionId = SourceConnectionId("connection-local"),
        ),
        resource = SourceResourceRef(
            book = sourceKey(),
            nativeResourceId = "resource-1",
            revision = null,
        ),
        assetId = MediaAssetId("asset-1"),
        replicaId = StorageReplicaId("replica-1"),
        storageRef = DeviceStorageRef("/device/import/import-1.epub"),
        requestedAt = "2026-09-24T00:00:00Z",
    )

    private fun sourceKey() = SourceBookKey(
        profileId = LibraryProfileId("profile-a"),
        adapterId = LibraryAdapterId("local"),
        accountIdentity = SourceAccountIdentity.Unresolved(
            SourceConnectionId("connection-local"),
        ),
        nativeBookId = NativeBookId("import-1"),
    )

    private class ActiveProfileSession(
        private val activeProfileId: String,
    ) : ProfileDatabaseSession {
        override suspend fun <T> withProfile(
            localProfileId: String,
            operation: suspend () -> T,
        ): T {
            check(localProfileId == activeProfileId)
            return operation()
        }
    }
}
