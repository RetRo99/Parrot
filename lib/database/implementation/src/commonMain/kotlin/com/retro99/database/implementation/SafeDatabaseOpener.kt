package com.retro99.database.implementation

import app.cash.sqldelight.db.SqlDriver
import com.retro99.analytics.api.Analytics
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey

internal interface DatabaseFileOps {
    fun createDriver(): SqlDriver
    fun deleteDatabaseFile(): Boolean
}

internal class SafeDatabaseOpener(
    private val preferences: Preferences,
    private val analytics: Analytics,
) {
    /** Records the version only after the driver has successfully opened and migrated. */
    fun open(userId: String, fileOps: DatabaseFileOps): SqlDriver {
        val versionKey = PreferencesKey.UserScoped(userId, "DatabaseSchemaVersion")
        val currentVersion = AppDatabase.Schema.version
        val action = decideDatabaseOpenAction(preferences.getLong(versionKey), currentVersion)
        if (action == DatabaseOpenAction.Recreate) {
            fileOps.deleteDatabaseFile()
        }

        val driver = try {
            createAndOpen(fileOps)
        } catch (exception: Exception) {
            analytics.logException(exception, "Database migration failed; recreating")
            fileOps.deleteDatabaseFile()
            createAndOpen(fileOps)
        }
        try {
            preferences.putLong(versionKey, currentVersion)
        } catch (exception: Exception) {
            closeAfterFailure(driver)
            throw exception
        }
        return driver
    }

    private fun createAndOpen(fileOps: DatabaseFileOps): SqlDriver {
        val driver = fileOps.createDriver()
        try {
            // Android opens lazily; this triggers onCreate/onUpgrade before recording the version.
            driver.execute(null, "PRAGMA user_version", 0).value
        } catch (exception: Exception) {
            closeAfterFailure(driver)
            throw exception
        }
        return driver
    }

    private fun closeAfterFailure(driver: SqlDriver) {
        // A close failure must not hide the original migration/open failure or prevent recovery.
        runCatching { driver.close() }
    }
}
