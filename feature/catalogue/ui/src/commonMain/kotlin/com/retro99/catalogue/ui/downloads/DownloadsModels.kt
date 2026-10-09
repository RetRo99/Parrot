package com.retro99.catalogue.ui.downloads

import com.retro99.catalogue.domain.*

enum class DownloadAction { Cancel, Retry, Dismiss, StartAgain, SignIn, Open }
data class DownloadRow(val acquisition: CatalogueAcquisition, val action: DownloadAction?, val filled: Boolean)

fun downloadsRows(acquisitions: List<CatalogueAcquisition>): List<DownloadRow> {
    var firstFailure = true
    return acquisitions.map { row ->
        val failure = row.state is AcquisitionState.Failed || row.state == AcquisitionState.Interrupted
        val filled = failure && firstFailure
        if (failure) firstFailure = false
        val action = when (val state = row.state) {
            AcquisitionState.Waiting, AcquisitionState.Downloading -> DownloadAction.Cancel
            AcquisitionState.Checking, AcquisitionState.Adding -> null
            AcquisitionState.Done -> DownloadAction.Open.takeIf { row.libraryBookId != null }
            AcquisitionState.Interrupted -> DownloadAction.StartAgain
            is AcquisitionState.Failed -> when (state.reason) {
                AcquisitionFailureReason.TooLarge, AcquisitionFailureReason.Invalid, AcquisitionFailureReason.Protected -> DownloadAction.Dismiss
                AcquisitionFailureReason.SignIn -> DownloadAction.SignIn
                else -> DownloadAction.Retry
            }
        }
        DownloadRow(row, action, filled)
    }
}

sealed interface ListDownloadState {
    data object Available : ListDownloadState
    data object GettingReady : ListDownloadState
    data object Waiting : ListDownloadState
    data class Downloading(val bytes: Long, val total: Long?) : ListDownloadState
    data object Adding : ListDownloadState
    data object InLibrary : ListDownloadState
}
