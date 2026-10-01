package com.retro99.books.ui.links

import com.retro99.base.ui.BaseIntent

sealed interface LinkPickerIntent : BaseIntent {
    data object OnBackClicked : LinkPickerIntent
    data class OnSearchQueryChanged(val query: String) : LinkPickerIntent
    data class OnBookPicked(val serverId: String, val bookUuid: String) : LinkPickerIntent
    data object OnErrorDismissed : LinkPickerIntent
}
