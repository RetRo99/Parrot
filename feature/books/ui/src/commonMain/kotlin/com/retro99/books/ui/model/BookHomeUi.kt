package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookHome

/** The home badge shows only when the list has books from more than one home. */
fun List<BookUiModel>.showHomeBadge(): Boolean =
    map { book -> book.home }.distinct().size > 1

/**
 * Library books show the downloaded icon when they live in Parrot Cloud and have a device
 * copy. Server books keep today's rule: a cached file.
 */
fun BookUiModel.showDownloadedIcon(progressInfo: BookProgressInfoUiModel?): Boolean =
    when (this) {
        is BookUiModel.LibraryBook -> hasDeviceCopy && home == BookHome.ParrotCloud
        is BookUiModel.StorytellerBook -> progressInfo?.hasAnyCached == true
    }

/** For the "Downloaded" quick filter: a device copy, or a cached server file. */
fun BookUiModel.isOnThisDevice(progressInfo: BookProgressInfoUiModel?): Boolean =
    when (this) {
        is BookUiModel.LibraryBook -> hasDeviceCopy
        is BookUiModel.StorytellerBook -> progressInfo?.hasAnyCached == true
    }

fun List<BookUiModel>.filterByHome(home: BookHome?): List<BookUiModel> =
    if (home == null) this else filter { book -> book.home == home }
