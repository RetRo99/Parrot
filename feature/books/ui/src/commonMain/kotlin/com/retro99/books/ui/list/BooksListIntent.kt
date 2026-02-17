package com.retro99.books.ui.list

import com.retro99.base.ui.BaseIntent
import com.retro99.books.ui.model.BookUiModel

sealed interface BooksListIntent : BaseIntent {
    data object OnRefresh : BooksListIntent
    data object OnSearchToggled : BooksListIntent
    data class OnBookClicked(val book: BookUiModel) : BooksListIntent
    data class OnFavoriteClicked(val bookUuid: String) : BooksListIntent
    data object OnImportClicked : BooksListIntent
    data class OnFileSelected(val fileBytes: ByteArray, val fileName: String) : BooksListIntent
    data class OnImportedBookClicked(val bookUuid: String) : BooksListIntent
    data class OnDeleteImportedBookClicked(val bookUuid: String) : BooksListIntent
}

