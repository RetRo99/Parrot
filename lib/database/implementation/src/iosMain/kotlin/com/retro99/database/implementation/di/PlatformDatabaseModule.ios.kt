@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.retro99.database.implementation.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.retro99.analytics.api.Analytics
import com.retro99.database.api.DatabaseNameProvider
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.SqlDriverFactory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSUserDomainMask

/**
 * iOS implementation of platform-specific Database module.
 * Provides SqlDriverFactory for creating per-user database drivers.
 */
@Module
actual class PlatformDatabaseModule {

    @Single
    fun providesSqlDriverFactory(
        analytics: Analytics,
    ): SqlDriverFactory {
        return IosSqlDriverFactory(analytics)
    }
}

private class IosSqlDriverFactory(
    private val analytics: Analytics,
) : SqlDriverFactory {

    override fun createDriver(userId: String): SqlDriver {
        val databaseName = DatabaseNameProvider.buildDatabaseName(userId)
        return NativeSqliteDriver(
            schema = AppDatabase.Schema,
            name = databaseName,
        )
    }

    override fun deleteUserDatabase(userId: String): Boolean {
        val databaseName = DatabaseNameProvider.buildDatabaseName(userId)
        return deleteDatabaseFile(databaseName)
    }

    private fun deleteDatabaseFile(databaseName: String): Boolean {
        val fileManager = NSFileManager.defaultManager
        val libraryPaths = fileManager.URLsForDirectory(
            NSLibraryDirectory,
            NSUserDomainMask,
        )
        val libraryUrl = libraryPaths.firstOrNull() ?: return true

        @Suppress("UNCHECKED_CAST")
        val libraryPath = (libraryUrl as platform.Foundation.NSURL).path ?: return true
        val dbPath = "$libraryPath/$databaseName"

        if (fileManager.fileExistsAtPath(dbPath)) {
            return runCatching { fileManager.removeItemAtPath(dbPath, null) }
                .onFailure { e ->
                    // Don't log full path for privacy - only log database name
                    analytics.logException(e, "Failed to delete database file: $databaseName")
                }
                .isSuccess
        }
        return true
    }
}
