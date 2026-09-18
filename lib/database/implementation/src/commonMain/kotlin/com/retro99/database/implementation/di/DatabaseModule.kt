package com.retro99.database.implementation.di

import com.retro99.database.api.DataClearable
import com.retro99.database.api.books.AuthorsDatabase
import com.retro99.database.api.books.BookmarksDatabase
import com.retro99.database.api.books.BooksDatabase
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.favorites.FavoritesDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.reader.ReaderSettingsDatabase
import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.dao.books.AuthorsDatabaseImpl
import com.retro99.database.implementation.dao.books.AuthorsSqlDelightDao
import com.retro99.database.implementation.dao.books.BookmarksDatabaseImpl
import com.retro99.database.implementation.dao.books.BookmarksSqlDelightDao
import com.retro99.database.implementation.dao.books.BooksDatabaseImpl
import com.retro99.database.implementation.dao.books.BooksSqlDelightDao
import com.retro99.database.implementation.dao.favorites.FavoritesDatabaseImpl
import com.retro99.database.implementation.dao.favorites.FavoritesSqlDelightDao
import com.retro99.database.implementation.dao.importedbooks.ImportedBooksDatabaseImpl
import com.retro99.database.implementation.dao.importedbooks.ImportedBooksSqlDelightDao
import com.retro99.database.implementation.dao.reader.ReaderSettingsDatabaseImpl
import com.retro99.database.implementation.dao.reader.ReaderSettingsSqlDelightDao
import com.retro99.database.implementation.dao.statistics.ReadingSessionDatabaseImpl
import com.retro99.database.implementation.dao.statistics.ReadingSessionSqlDelightDao
import com.retro99.database.implementation.dao.sync.SyncOutboxDatabaseImpl
import com.retro99.database.implementation.dao.sync.SyncOutboxSqlDelightDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * Database module that provides per-user database access.
 *
 * The DatabaseManager handles switching databases when the active user changes.
 * DAOs are singletons that delegate to DatabaseManager for getting the current database.
 * This ensures all components share the same DAO instance while still supporting user switching.
 */
@Module(
    includes = [
        PlatformDatabaseModule::class,
    ],
)
@Configuration
@ComponentScan("com.retro99.database.implementation")
class DatabaseModule {

    // Note: DatabaseManager is provided via @Single annotation on the class itself

    @Single
    internal fun provideBooksSqlDelightDao(databaseManager: DatabaseManager): BooksSqlDelightDao {
        return BooksSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideBooksDatabase(booksSqlDelightDao: BooksSqlDelightDao): BooksDatabase {
        return BooksDatabaseImpl(booksSqlDelightDao)
    }

    @Single
    internal fun providePositionDatabase(booksSqlDelightDao: BooksSqlDelightDao): PositionDatabase {
        return BooksDatabaseImpl(booksSqlDelightDao)
    }

    @Single
    internal fun provideBookmarksSqlDelightDao(databaseManager: DatabaseManager): BookmarksSqlDelightDao {
        return BookmarksSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideBookmarksDatabase(bookmarksSqlDelightDao: BookmarksSqlDelightDao): BookmarksDatabase {
        return BookmarksDatabaseImpl(bookmarksSqlDelightDao)
    }

    @Single
    internal fun provideAuthorsSqlDelightDao(databaseManager: DatabaseManager): AuthorsSqlDelightDao {
        return AuthorsSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideAuthorsDatabase(authorsSqlDelightDao: AuthorsSqlDelightDao): AuthorsDatabase {
        return AuthorsDatabaseImpl(authorsSqlDelightDao)
    }

    @Single
    internal fun provideFavoritesSqlDelightDao(databaseManager: DatabaseManager): FavoritesSqlDelightDao {
        return FavoritesSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideFavoritesDatabase(favoritesSqlDelightDao: FavoritesSqlDelightDao): FavoritesDatabase {
        return FavoritesDatabaseImpl(favoritesSqlDelightDao)
    }

    @Single
    internal fun provideReadingSessionSqlDelightDao(
        databaseManager: DatabaseManager,
    ): ReadingSessionSqlDelightDao {
        return ReadingSessionSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideReadingSessionDatabase(
        readingSessionSqlDelightDao: ReadingSessionSqlDelightDao,
    ): ReadingSessionDatabase {
        return ReadingSessionDatabaseImpl(readingSessionSqlDelightDao)
    }

    @Single
    internal fun provideImportedBooksSqlDelightDao(
        databaseManager: DatabaseManager,
    ): ImportedBooksSqlDelightDao {
        return ImportedBooksSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideImportedBooksDatabase(
        importedBooksSqlDelightDao: ImportedBooksSqlDelightDao,
    ): ImportedBooksDatabase {
        return ImportedBooksDatabaseImpl(importedBooksSqlDelightDao)
    }

    @Single
    internal fun provideSyncOutboxSqlDelightDao(
        databaseManager: DatabaseManager,
    ): SyncOutboxSqlDelightDao {
        return SyncOutboxSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideSyncOutboxDatabase(
        syncOutboxSqlDelightDao: SyncOutboxSqlDelightDao,
    ): SyncOutboxDatabase {
        return SyncOutboxDatabaseImpl(syncOutboxSqlDelightDao)
    }

    @Single
    internal fun provideReaderSettingsSqlDelightDao(
        databaseManager: DatabaseManager,
    ): ReaderSettingsSqlDelightDao {
        return ReaderSettingsSqlDelightDao(databaseManager)
    }

    @Single
    internal fun provideReaderSettingsDatabase(
        readerSettingsSqlDelightDao: ReaderSettingsSqlDelightDao,
    ): ReaderSettingsDatabase {
        return ReaderSettingsDatabaseImpl(readerSettingsSqlDelightDao)
    }

    @Single
    internal fun provideDataClearables(
        booksDatabase: BooksDatabase,
        bookmarksDatabase: BookmarksDatabase,
        favoritesDatabase: FavoritesDatabase,
        authorsDatabase: AuthorsDatabase,
        readingSessionDatabase: ReadingSessionDatabase,
        syncOutboxDatabase: SyncOutboxDatabase,
    ): List<DataClearable> {
        return listOf(
            booksDatabase,
            bookmarksDatabase,
            favoritesDatabase,
            authorsDatabase,
            readingSessionDatabase,
            syncOutboxDatabase,
        )
    }
}
