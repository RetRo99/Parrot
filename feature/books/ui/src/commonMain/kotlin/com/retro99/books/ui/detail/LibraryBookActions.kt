package com.retro99.books.ui.detail

import com.retro99.books.ui.model.BookUiModel
import com.retro99.books.ui.model.MediaResourceUiModel

data class LibraryMediaActions(
    val open: Boolean,
    val download: Boolean,
    val removeDownload: Boolean,
    val addToParrot: Boolean,
    val retryUpload: Boolean,
)

data class LibraryBookActions(
    val removeFromParrot: Boolean,
    val deleteFromDevice: Boolean,
)

/**
 * What the detail screen offers for one media type of a library book. "Device" is a device
 * copy; "Parrot" is the Parrot Cloud copy's availability (plan §1.5, detail table).
 */
fun MediaResourceUiModel.libraryActions(parrotActive: Boolean): LibraryMediaActions {
    val onDevice = localPath != null
    val parrot = remoteAvailability
    return LibraryMediaActions(
        open = onDevice,
        download = !onDevice && parrot == AVAILABLE,
        removeDownload = onDevice && parrot == AVAILABLE,
        addToParrot = onDevice && parrot == NONE && parrotActive,
        retryUpload = onDevice && parrot == UPLOAD_FAILED && parrotActive,
    )
}

/** What the detail screen offers for the whole library book (plan §1.5). */
fun BookUiModel.LibraryBook.bookActions(): LibraryBookActions {
    val availabilities = mediaResources.map { resource -> resource.remoteAvailability }
    return LibraryBookActions(
        removeFromParrot = AVAILABLE in availabilities,
        deleteFromDevice = hasDeviceCopy && availabilities.none { availability ->
            availability in PARROT_HOME_STATES
        },
    )
}

// MediaResourceUiModel.remoteAvailability holds RemoteFileAvailability names.
private const val NONE = "None"
private const val AVAILABLE = "Available"
private const val UPLOAD_FAILED = "UploadFailed"
private val PARROT_HOME_STATES = setOf(AVAILABLE, "UploadPending", "Uploading")
