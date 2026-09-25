package com.retro99.database.implementation

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class DatabaseManagerTest {
    @Test
    fun nestedSameProfileSessionCompletes(): Unit = runBlocking {
        // Given
        val userRegistry = TestUserRegistry("profile-a")
        val classUnderTest = DatabaseManager(userRegistry, InMemorySqlDriverFactory())

        // When
        val result = try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                classUnderTest.withProfile("profile-a") {
                    coroutineScope {
                        async {
                            classUnderTest.withProfile("profile-a") { "nested" }
                        }.await()
                    }
                }
            }
        } finally {
            classUnderTest.close()
        }

        // Then
        assertEquals("nested", result)
    }

    @Test
    fun sameProfileSessionsCanProgressConcurrently(): Unit = runBlocking {
        // Given
        val userRegistry = TestUserRegistry("profile-a")
        val classUnderTest = DatabaseManager(userRegistry, InMemorySqlDriverFactory())
        val firstSessionEntered = CompletableDeferred<Unit>()
        val releaseFirstSession = CompletableDeferred<Unit>()
        val secondSessionEntered = CompletableDeferred<Unit>()
        val firstSession = async {
            classUnderTest.withProfile("profile-a") {
                firstSessionEntered.complete(Unit)
                releaseFirstSession.await()
            }
        }

        try {
            firstSessionEntered.await()

            // When
            val secondSession = async {
                classUnderTest.withProfile("profile-a") {
                    secondSessionEntered.complete(Unit)
                }
            }
            withTimeout(TEST_TIMEOUT_MILLIS) {
                secondSessionEntered.await()
            }

            // Then
            secondSession.await()
        } finally {
            releaseFirstSession.complete(Unit)
            firstSession.await()
            classUnderTest.close()
        }
    }

    @Test
    fun profileSwitchWaitsForActiveSessionsToRelease(): Unit = runBlocking {
        // Given
        val userRegistry = TestUserRegistry("profile-a")
        val classUnderTest = DatabaseManager(userRegistry, InMemorySqlDriverFactory())
        val firstSessionEntered = CompletableDeferred<Unit>()
        val releaseFirstSession = CompletableDeferred<Unit>()
        try {
            supervisorScope {
                val firstSession = async {
                    classUnderTest.withProfile("profile-a") {
                        firstSessionEntered.complete(Unit)
                        releaseFirstSession.await()
                    }
                }
                try {
                    firstSessionEntered.await()
                    userRegistry.currentActiveProfileId = "profile-b"

                    // When
                    val secondSessionRequested = CompletableDeferred<Unit>()
                    val secondSession = async {
                        secondSessionRequested.complete(Unit)
                        classUnderTest.withProfile("profile-b") {
                            classUnderTest.getCurrentUserId()
                        }
                    }
                    secondSessionRequested.await()
                    yield()

                    // Then
                    assertFalse(secondSession.isCompleted)
                    assertEquals("profile-a", classUnderTest.getCurrentUserId())

                    releaseFirstSession.complete(Unit)
                    assertIs<IllegalStateException>(
                        runCatching { firstSession.await() }.exceptionOrNull(),
                    )
                    assertEquals(
                        "profile-b",
                        withTimeout(TEST_TIMEOUT_MILLIS) { secondSession.await() },
                    )
                } finally {
                    releaseFirstSession.complete(Unit)
                }
            }
        } finally {
            classUnderTest.close()
        }
    }

    private class InMemorySqlDriverFactory : SqlDriverFactory {
        override fun createDriver(userId: String): SqlDriver =
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        override fun deleteUserDatabase(userId: String): Boolean = true
    }

    private class TestUserRegistry(
        var currentActiveProfileId: String?,
    ) : UserRegistry {
        override fun observeAllProfiles(): Flow<List<UserProfile>> = emptyFlow()
        override suspend fun getAllProfiles(): List<UserProfile> = unused()
        override suspend fun createProfile(
            id: String?,
            name: String,
            avatarId: Int?,
        ): UserProfile = unused()

        override suspend fun updateProfile(profile: UserProfile): Unit = unused()
        override suspend fun deleteProfile(profileId: String): Unit = unused()
        override suspend fun getProfile(profileId: String): UserProfile? = unused()
        override fun observeActiveProfile(): Flow<UserProfile?> = emptyFlow()
        override suspend fun getActiveProfile(): UserProfile? = unused()
        override fun getActiveProfileId(): String? = currentActiveProfileId
        override suspend fun setActiveProfile(profileId: String): Unit = unused()
        override suspend fun clearActiveProfile(): Unit = unused()
        override suspend fun hasProfiles(): Boolean = unused()
        override fun isProfileActive(): Boolean = currentActiveProfileId != null

        private fun <T> unused(): T = error("This method is not used in the test")
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
    }
}
