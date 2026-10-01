package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookHome

/**
 * The home badges show only when the list has books from more than one home. A linked book
 * counts with every home it has a copy in.
 */
fun List<BookUiModel>.showHomeBadge(): Boolean = availableHomes().size > 1

/** Every home in the list, linked copies included. */
fun List<BookUiModel>.availableHomes(): List<BookHome> =
    flatMap { book -> book.homes }.distinct()

/**
 * Library books show the downloaded icon when they live in Parrot Cloud and have a device
 * copy. Server books keep today's rule: a cached file. A linked book also shows it when any
 * of its other copies is downloaded.
 */
fun BookUiModel.showDownloadedIcon(progressInfo: BookProgressInfoUiModel?): Boolean =
    hasDownloadedLinkedCopy() || when (this) {
        is BookUiModel.LibraryBook -> hasDeviceCopy && home == BookHome.ParrotCloud
        is BookUiModel.StorytellerBook -> progressInfo?.hasAnyCached == true
    }

/**
 * For the "Downloaded" quick filter: a device copy, or a cached server file, of this copy
 * or of any copy it is linked to.
 */
fun BookUiModel.isOnThisDevice(progressInfo: BookProgressInfoUiModel?): Boolean =
    hasDownloadedLinkedCopy() || when (this) {
        is BookUiModel.LibraryBook -> hasDeviceCopy
        is BookUiModel.StorytellerBook -> progressInfo?.hasAnyCached == true
    }

/** A linked book matches when any of its copies has that home. */
fun List<BookUiModel>.filterByHome(home: BookHome?): List<BookUiModel> =
    if (home == null) this else filter { book -> home in book.homes }

private fun BookUiModel.hasDownloadedLinkedCopy(): Boolean =
    linkedCopies.any { copy -> copy.isDownloaded }
