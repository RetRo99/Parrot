package com.retro99.parrot.fixtures

import androidx.compose.runtime.Composable
import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.downloads.*

private fun row(title: String, state: AcquisitionState, size: Long? = null, bytes: Long = 0, catalogue: String = "Project Gutenberg", needed: Long? = null) = CatalogueAcquisition(
    title, "fixture", title, "file", null, title, null, null, catalogue, state, 0, size, bytes, null, null,
    if (state == AcquisitionState.Done) "library" else null, 0, 0, null, 0, needed,
)

@Composable
fun CatalogueDownloadsBoard(view: String) {
    val rows = when (view) {
        "downloadsEmpty" -> emptyList()
        "downloadsFailed" -> listOf(
            row("Pride and Prejudice", AcquisitionState.Failed(AcquisitionFailureReason.Connection)),
            row("The Complete Works of…", AcquisitionState.Failed(AcquisitionFailureReason.TooLarge), 620_000_000),
            row("Middlemarch", AcquisitionState.Failed(AcquisitionFailureReason.Storage), needed = 48_000_000),
            row("Ulysses", AcquisitionState.Failed(AcquisitionFailureReason.Invalid)),
            row("The Hobbit", AcquisitionState.Failed(AcquisitionFailureReason.Protected)),
            row("Emma", AcquisitionState.Failed(AcquisitionFailureReason.Refused)),
        )
        else -> listOf(
            row("The War of the Worlds", AcquisitionState.Downloading, 1_200_000, 500_000),
            row("Middlemarch", AcquisitionState.Downloading, bytes = 2_100_000),
            row("Emma", AcquisitionState.Waiting),
            row("Dracula", AcquisitionState.Adding),
            row("Pride and Prejudice", AcquisitionState.Interrupted),
            row("The Hound of the Baskervilles", AcquisitionState.Failed(AcquisitionFailureReason.SignIn), catalogue = "Home Calibre"),
            row("Treasure Island", AcquisitionState.Done),
        )
    }
    // The board illustrates a 500 MB limit. Live UI always names the actual queue limit.
    CatalogueDownloadsContent(downloadsRows(rows), deviceName = "this phone", limit = 500_000_000)
}
