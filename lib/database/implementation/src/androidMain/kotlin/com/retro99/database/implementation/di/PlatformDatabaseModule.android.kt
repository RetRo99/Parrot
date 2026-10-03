package com.retro99.database.implementation.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.retro99.analytics.api.Analytics
import com.retro99.database.api.DatabaseNameProvider
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.DatabaseFileOps
import com.retro99.database.implementation.SafeDatabaseOpener
import com.retro99.database.implementation.SqlDriverFactory
import com.retro99.preferences.api.Preferences
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Android implementation of platform-specific Database module.
 * Provides SqlDriverFactory for creating per-user database drivers.
 *
 * Context is automatically provided by Koin when using androidContext(this) in Koin initialization.
 */
@Module
actual class PlatformDatabaseModule {

    @Single
    fun providesSqlDriverFactory(
        context: Context,
        @Provided preferences: Preferences,
        @Provided analytics: Analytics,
    ): SqlDriverFactory {
        return AndroidSqlDriverFactory(context, preferences, analytics)
    }
}

private class AndroidSqlDriverFactory(
    private val context: Context,
    preferences: Preferences,
    analytics: Analytics,
) : SqlDriverFactory {
    private val opener = SafeDatabaseOpener(preferences, analytics)

    override fun createDriver(userId: String): SqlDriver {
        val databaseName = DatabaseNameProvider.buildDatabaseName(userId)
        return opener.open(userId, object : DatabaseFileOps {
            override fun createDriver(): SqlDriver = AndroidSqliteDriver(
                schema = AppDatabase.Schema,
                context = context,
                name = databaseName,
                // Recap excerpts are uncapped; the 2 MB default window would
                // make a long session's row unreadable on Android.
                windowSizeBytes = CURSOR_WINDOW_BYTES,
            )

            override fun deleteDatabaseFile(): Boolean = context.deleteDatabase(databaseName)
        })
    }

    override fun deleteUserDatabase(userId: String): Boolean {
        val databaseName = DatabaseNameProvider.buildDatabaseName(userId)
        return context.deleteDatabase(databaseName)
    }
}

// ~15M chars of read text; memory is only used as a row fills it.
private const val CURSOR_WINDOW_BYTES = 16L * 1024 * 1024
