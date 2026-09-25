package com.retro99.database.implementation.di

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.retro99.database.api.DatabaseNameProvider
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.SqlDriverFactory
import org.koin.core.annotation.Module
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
    fun providesSqlDriverFactory(context: Context): SqlDriverFactory {
        return AndroidSqlDriverFactory(context)
    }
}

private class AndroidSqlDriverFactory(
    private val context: Context,
) : SqlDriverFactory {

    override fun createDriver(userId: String): SqlDriver {
        val databaseName = DatabaseNameProvider.buildDatabaseName(userId)
        return AndroidSqliteDriver(
            schema = AppDatabase.Schema,
            context = context,
            name = databaseName,
        )
    }

    override fun deleteUserDatabase(userId: String): Boolean {
        val databaseName = DatabaseNameProvider.buildDatabaseName(userId)
        return context.deleteDatabase(databaseName)
    }
}
