package com.retro99.books.domain

import com.retro99.base.result.CompletableResult

/** Changes to the copies of your library's books that live on this device. */
interface DeviceLibraryRepository {
    /** Deletes the book's device files and its library row. Parrot Cloud is not touched. */
    suspend fun deleteBookFromDevice(libraryBookId: String): CompletableResult

    /** Marks the book's device files as imports, so a Parrot Cloud removal keeps them. */
    suspend fun keepDeviceFilesAsImports(libraryBookId: String): CompletableResult
}
