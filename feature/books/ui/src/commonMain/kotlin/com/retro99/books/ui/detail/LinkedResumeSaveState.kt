package com.retro99.books.ui.detail

import com.retro99.base.result.AppError

/** A failed Continue keeps both the offer and intended book type available for retry. */
internal fun BookDetailViewState.linkedResumeSaveFailed(error: AppError): BookDetailViewState = copy(
    isResolvingConflict = false,
    conflictResolutionError = error,
)
