package com.retro99.books.domain

import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import kotlinx.coroutines.flow.Flow

interface BooksRepository {

    fun getBooks(): Flow<AppResult<List<BookDomainModel.StorytellerBook>>>

    fun getBook(uuid: String): Flow<AppResult<BookDomainModel.StorytellerBook>>
}

