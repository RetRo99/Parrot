package com.retro99.books.ui.links

import com.retro99.base.ui.BaseIntent

sealed interface LinkReviewIntent : BaseIntent {
    data object OnBackClicked : LinkReviewIntent
    data class OnLinkClicked(val pairKey: String) : LinkReviewIntent
    data class OnNotSameBookClicked(val pairKey: String) : LinkReviewIntent
    data class OnSkipClicked(val pairKey: String) : LinkReviewIntent
    data object OnLinkAllConfidentClicked : LinkReviewIntent
    data object OnMessageDismissed : LinkReviewIntent
}
