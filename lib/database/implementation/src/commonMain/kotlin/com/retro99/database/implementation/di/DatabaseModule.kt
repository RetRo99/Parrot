package com.retro99.database.implementation.di

import app.cash.sqldelight.db.SqlDriver
import com.retro99.database.api.DataClearable
import com.retro99.database.api.books.AuthorsDatabase
import com.retro99.database.api.books.BooksDatabase
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.favorites.FavoritesDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.statistics.ReadingSessionDatabase
import com.retro99.database.implementation.AppDatabase
import com.retro99.database.implementation.dao.books.AuthorsDatabaseImpl
import com.retro99.database.implementation.dao.books.AuthorsSqlDelightDao
import com.retro99.database.implementation.dao.books.BooksDatabaseImpl
import com.retro99.database.implementation.dao.books.BooksSqlDelightDao
import com.retro99.database.implementation.dao.favorites.FavoritesDatabaseImpl
import com.retro99.database.implementation.dao.favorites.FavoritesSqlDelightDao
import com.retro99.database.implementation.dao.importedbooks.ImportedBooksDatabaseImpl
import com.retro99.database.implementation.dao.importedbooks.ImportedBooksSqlDelightDao
import com.retro99.database.implementation.dao.statistics.ReadingSessionDatabaseImpl
import com.retro99.database.implementation.dao.statistics.ReadingSessionSqlDelightDao
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        PlatformDatabaseModule::class,
    ],
)
@Configuration
@ComponentScan("com.retro99.database.implementation")
class DatabaseModule {

    @Single
    internal fun provideAppDatabase(driver: SqlDriver): AppDatabase {
        return AppDatabase(driver)
    }

    @Single
    internal fun provideBooksSqlDelightDao(database: AppDatabase): BooksSqlDelightDao {
        return BooksSqlDelightDao(database)
    }

    @Single
    internal fun provideBooksDatabase(dao: BooksSqlDelightDao): BooksDatabase {
        return BooksDatabaseImpl(dao)
    }

    @Single
    internal fun providePositionDatabase(booksDatabase: BooksDatabase): PositionDatabase {
        return booksDatabase
    }

    @Single
    internal fun provideAuthorsSqlDelightDao(database: AppDatabase): AuthorsSqlDelightDao {
        return AuthorsSqlDelightDao(database)
    }

    @Single
    internal fun provideAuthorsDatabase(dao: AuthorsSqlDelightDao): AuthorsDatabase {
        return AuthorsDatabaseImpl(dao)
    }

    @Single
    internal fun provideFavoritesSqlDelightDao(database: AppDatabase): FavoritesSqlDelightDao {
        return FavoritesSqlDelightDao(database)
    }

    @Single
    internal fun provideFavoritesDatabase(dao: FavoritesSqlDelightDao): FavoritesDatabase {
        return FavoritesDatabaseImpl(dao)
    }

    @Single
    internal fun provideReadingSessionSqlDelightDao(
        database: AppDatabase,
    ): ReadingSessionSqlDelightDao {
        return ReadingSessionSqlDelightDao(database)
    }

    @Single
    internal fun provideReadingSessionDatabase(
        dao: ReadingSessionSqlDelightDao,
    ): ReadingSessionDatabase {
        return ReadingSessionDatabaseImpl(dao)
    }

    @Single
    internal fun provideImportedBooksSqlDelightDao(
        database: AppDatabase,
    ): ImportedBooksSqlDelightDao {
        return ImportedBooksSqlDelightDao(database)
    }

    @Single
    internal fun provideImportedBooksDatabase(
        dao: ImportedBooksSqlDelightDao,
    ): ImportedBooksDatabase {
        return ImportedBooksDatabaseImpl(dao)
    }

    @Single
    internal fun provideDataClearables(
        booksDatabase: BooksDatabase,
        favoritesDatabase: FavoritesDatabase,
        authorsDatabase: AuthorsDatabase,
        readingSessionDatabase: ReadingSessionDatabase,
    ): List<DataClearable> {
        return listOf(booksDatabase, favoritesDatabase, authorsDatabase, readingSessionDatabase)
    }
}
