package com.retro99.catalogue.ui.downloads

import com.retro99.catalogue.domain.*
import kotlin.test.*

internal fun downloadFixture(state: AcquisitionState, id: String = "request", completedAt: Long? = null) = CatalogueAcquisition(
    id, "source", "book", "file", null, "Title", "Author", null, "Catalogue", state, 0,
    1_200_000, 500_000, null, null, if (state == AcquisitionState.Done) "library" else null,
    0, 0, completedAt, 0,
)

class DownloadsModelsTest {
    @Test fun everyStateHasExactlyItsAllowedAction() {
        val expected = mapOf(
            AcquisitionState.Waiting to DownloadAction.Cancel,
            AcquisitionState.Downloading to DownloadAction.Cancel,
            AcquisitionState.Checking to null,
            AcquisitionState.Adding to null,
            AcquisitionState.Done to DownloadAction.Open,
            AcquisitionState.Interrupted to DownloadAction.StartAgain,
        ) + AcquisitionFailureReason.entries.associate { reason ->
            AcquisitionState.Failed(reason) to when (reason) {
                AcquisitionFailureReason.TooLarge, AcquisitionFailureReason.Invalid, AcquisitionFailureReason.Protected -> DownloadAction.Dismiss
                AcquisitionFailureReason.SignIn -> DownloadAction.SignIn
                else -> DownloadAction.Retry
            }
        }
        expected.forEach { (state, action) -> assertEquals(action, downloadsRows(listOf(downloadFixture(state))).single().action, state.toString()) }
    }

    @Test fun onlyFirstFailureIsFilledEvenWhenItIsDismiss() {
        val rows = downloadsRows(listOf(downloadFixture(AcquisitionState.Done), downloadFixture(AcquisitionState.Failed(AcquisitionFailureReason.Invalid), "a"), downloadFixture(AcquisitionState.Interrupted, "b")))
        assertEquals(listOf(false, true, false), rows.map { it.filled })
    }
}
