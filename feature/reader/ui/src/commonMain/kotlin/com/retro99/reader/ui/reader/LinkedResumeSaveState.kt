package com.retro99.reader.ui.reader

import com.retro99.base.result.AppError

/** A failed Continue must not dismiss the prompt or move the audio checkpoint. */
internal fun ReaderViewState.linkedResumeSaveFailed(error: AppError): ReaderViewState = copy(
    isResolvingConflict = false,
    conflictResolutionError = error,
)
