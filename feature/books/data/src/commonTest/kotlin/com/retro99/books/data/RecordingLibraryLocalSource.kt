package com.retro99.books.data

import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.books.data.source.AddedLibraryFile
import com.retro99.books.data.source.ImportedFileCandidate
import com.retro99.books.data.source.LibraryBookRecord
import com.retro99.books.data.source.LibraryLocalSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Records the candidates it is given and answers with [result]. */
internal class RecordingLibraryLocalSource(
    var result: AppResult<AddedLibraryFile> = Ok(AddedLibraryFile("book-id", isNewBook = true)),
) : LibraryLocalSource {
    val candidates = mutableListOf<ImportedFileCandidate>()

    override suspend fun addStagedFile(file: ImportedFileCandidate): AppResult<AddedLibraryFile> {
        candidates += file
        return result
    }

    override fun observeLibrary(): Flow<List<LibraryBookRecord>> = emptyFlow()

    override suspend fun getLibraryBook(libraryBookId: String): LibraryBookRecord? = null

    override suspend fun deleteBookFromDevice(libraryBookId: String): CompletableResult = Ok(Unit)
}
