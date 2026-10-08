package com.retro99.books.data.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.annotation.Provided
import com.retro99.analytics.api.Analytics
import com.retro99.books.data.transfer.BookFileTransferEngine
import com.retro99.books.data.transfer.BookFileTransferFileStore
import com.retro99.books.data.transfer.DownloadTransferFinalizer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.BookFileTransferTransport
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.BookFileDeletionTransport
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.sync.domain.FileTransferStatusSource

@Module
@ComponentScan("com.retro99.books.data")
class BooksDataModule {
    // Require collections here: default-empty constructor collections are skipped by Koin.
    @Single(binds = [BookFileTransferManager::class, FileTransferStatusSource::class])
    fun fileTransferEngine(
        @Provided cloudFilesDatabase: CloudFilesDatabase,
        @Provided deviceFilesDatabase: DeviceFilesDatabase,
        @Provided libraryBooksDatabase: LibraryBooksDatabase,
        @Provided transports: List<BookFileTransferTransport>,
        @Provided downloadTransports: List<BookFileDownloadTransport>,
        @Provided deletionTransports: List<BookFileDeletionTransport>,
        @Provided downloadFinalizer: DownloadTransferFinalizer?,
        @Provided fileStore: BookFileTransferFileStore?,
        @Provided analytics: Analytics?,
    ): BookFileTransferEngine = BookFileTransferEngine(
        cloudFilesDatabase, deviceFilesDatabase, libraryBooksDatabase, transports,
        downloadTransports, deletionTransports, downloadFinalizer, fileStore, analytics,
    )
}
