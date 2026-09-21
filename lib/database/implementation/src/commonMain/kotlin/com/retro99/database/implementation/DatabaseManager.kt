 package com.retro99.database.implementation

import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Manages database instances per user profile.
 * 
 * Each user has their own isolated database. When the active user changes,
 * the manager closes the old database and opens the new one.
 */
@Single
class DatabaseManager(
    @Provided private val userRegistry: UserRegistry,
    private val driverFactory: SqlDriverFactory,
) : ProfileDatabaseSession {
    private val logger = Logger.withTag("čič")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private var currentUserId: String? = null
    private var currentDriver: SqlDriver? = null
    private var currentDatabase: AppDatabase? = null

    init {
        // React to user profile changes
        userRegistry.observeActiveProfile()
            .map { it?.id }
            .distinctUntilChanged()
            .onEach { userId ->
                if (userId != currentUserId) {
                    logger.d { "User changed from $currentUserId to $userId, switching database" }
                    switchToUser(userId)
                }
            }
            .launchIn(scope)
    }

    private suspend fun switchToUser(userId: String?) {
        mutex.withLock {
            switchToUserLocked(userId)
        }
    }

    private suspend fun switchToUserLocked(userId: String?) {
        currentDriver?.close()
        currentDriver = null
        currentDatabase = null
        currentUserId = userId

        if (userId != null) {
            val driver = driverFactory.createDriver(userId)
            currentDriver = driver
            currentDatabase = AppDatabase(driver)
            logger.d { "Opened database for user $userId" }
        } else {
            logger.d { "No active user, database closed" }
        }
    }

    /**
     * Get the current user's database.
     * @throws IllegalStateException if no user is active
     */
    fun getDatabase(): AppDatabase {
        return currentDatabase
            ?: throw IllegalStateException("No active user profile. Cannot access database.")
    }

    /**
     * Check if a database is currently available.
     */
    fun isDatabaseAvailable(): Boolean {
        return currentDatabase != null
    }

    /**
     * Get the current user ID, or null if no user is active.
     */
    fun getCurrentUserId(): String? {
        return currentUserId
    }

    override suspend fun <T> withProfile(
        localProfileId: String,
        operation: suspend () -> T,
    ): T {
        return mutex.withLock {
            check(userRegistry.getActiveProfileIdOrDefault() == localProfileId) {
                "Database operation started for an inactive profile"
            }
            if (currentUserId != localProfileId || currentDatabase == null) {
                switchToUserLocked(localProfileId)
            }
            val result = operation()
            check(userRegistry.getActiveProfileIdOrDefault() == localProfileId) {
                "Database operation completed for an inactive profile"
            }
            result
        }
    }

    /**
     * Close the current database. Called when the app is shutting down.
     */
    suspend fun close() {
        mutex.withLock {
            currentDriver?.close()
            currentDriver = null
            currentDatabase = null
            currentUserId = null
        }
    }
}
