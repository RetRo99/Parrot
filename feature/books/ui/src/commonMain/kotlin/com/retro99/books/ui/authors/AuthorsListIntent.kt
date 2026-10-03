package com.retro99.books.ui.authors

import com.retro99.base.ui.BaseIntent
import com.retro99.books.ui.authors.model.AuthorListUiModel

sealed interface AuthorsListIntent : BaseIntent {
    data object OnScreenVisible : AuthorsListIntent
    data object OnRefresh : AuthorsListIntent
    data class OnAuthorClicked(val author: AuthorListUiModel) : AuthorsListIntent
}
