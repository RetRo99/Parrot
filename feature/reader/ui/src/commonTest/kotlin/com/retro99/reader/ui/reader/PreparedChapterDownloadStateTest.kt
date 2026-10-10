package com.retro99.reader.ui.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The decision behind the row's Download action. Every state the brief names has
 * a case, and exactly which states would start a download is pinned, so a new
 * state cannot quietly become one that queues work.
 */
class PreparedChapterDownloadStateTest {

    @Test
    fun `nothing in the cloud and nothing to say`() {
        assertEquals(
            PreparedChapterDownloadState.NotInCloud,
            preparedChapterDownloadState(PreparedChapterDownloadInputs()),
        )
    }

    @Test
    fun `audio already on this device needs no download whatever the cloud says`() {
        assertEquals(
            PreparedChapterDownloadState.Installed,
            preparedChapterDownloadState(
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(),
                    installedLocally = true,
                    // Even a stale failure does not change what is true now.
                    transfer = PreparedChapterTransfer(state = "failed", lastError = "verify_failed"),
                ),
            ),
        )
    }

    @Test
    fun `in the cloud with room for it is an offer`() {
        assertEquals(
            PreparedChapterDownloadState.AvailableInCloud,
            preparedChapterDownloadState(PreparedChapterDownloadInputs(cloudAudio = cloudAudio())),
        )
    }

    @Test
    fun `made for other settings is still an offer because the row says what it is for`() {
        val state = preparedChapterDownloadState(
            PreparedChapterDownloadInputs(cloudAudio = cloudAudio(forCurrentSettings = false)),
        )
        assertEquals(PreparedChapterDownloadState.AvailableInCloud, state)
    }

    @Test
    fun `a transfer in flight reads as downloading and then as installing`() {
        assertEquals(
            PreparedChapterDownloadState.Downloading,
            state(transfer = PreparedChapterTransfer(state = "transferring")),
        )
        assertEquals(
            PreparedChapterDownloadState.Downloading,
            state(transfer = PreparedChapterTransfer(state = "pending")),
        )
        assertEquals(
            PreparedChapterDownloadState.Installing,
            state(transfer = PreparedChapterTransfer(state = "verifying")),
        )
        assertEquals(
            PreparedChapterDownloadState.Installing,
            state(transfer = PreparedChapterTransfer(state = "finalizing")),
        )
    }

    @Test
    fun `no network is the retryable failure`() {
        assertEquals(
            PreparedChapterDownloadState.FailedNoNetwork,
            state(transfer = PreparedChapterTransfer(state = "failed", lastError = "no network")),
        )
        // A waiting retry is reported the same way: pressing Download is the
        // right thing to do and does no harm.
        assertEquals(
            PreparedChapterDownloadState.FailedNoNetwork,
            state(
                transfer = PreparedChapterTransfer(
                    state = "pending",
                    willRetry = true,
                    lastError = "connection reset",
                ),
            ),
        )
    }

    @Test
    fun `a file gone from the cloud says so and is not a network problem`() {
        for (reason in listOf("cloud_file_unavailable", "cloud_file_changed", "unsupported_hash_algorithm")) {
            assertEquals(
                PreparedChapterDownloadState.FailedGoneFromCloud,
                state(transfer = PreparedChapterTransfer(state = "failed", lastError = reason)),
                reason,
            )
        }
    }

    @Test
    fun `a refused archive says so from the engine and from the unpack`() {
        assertEquals(
            PreparedChapterDownloadState.FailedArchiveRejected,
            state(transfer = PreparedChapterTransfer(state = "failed", lastError = "verify_failed")),
        )
        // The unpack refused it after a transfer that itself completed, so
        // only archiveRejected can say so.
        assertEquals(
            PreparedChapterDownloadState.FailedArchiveRejected,
            preparedChapterDownloadState(
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(),
                    transfer = PreparedChapterTransfer(state = "completed"),
                    archiveRejected = true,
                ),
            ),
        )
    }

    @Test
    fun `no room on the device is reported before anything is fetched`() {
        assertEquals(
            PreparedChapterDownloadState.FailedDeviceFull,
            preparedChapterDownloadState(
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(sizeBytes = 1_000),
                    // Room for the archive, but not for it and its contents.
                    freeBytesOnDevice = 1_500,
                ),
            ),
        )
        assertEquals(
            PreparedChapterDownloadState.AvailableInCloud,
            preparedChapterDownloadState(
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(sizeBytes = 1_000),
                    freeBytesOnDevice = 3_000,
                ),
            ),
        )
    }

    @Test
    fun `the server saying the device is full is a device problem and not a retry`() {
        assertEquals(
            PreparedChapterDownloadState.FailedDeviceFull,
            state(transfer = PreparedChapterTransfer(state = "failed", lastError = "device_storage_full")),
        )
    }

    @Test
    fun `only two states would start a download and a table says which`() {
        val cases = mapOf(
            PreparedChapterDownloadState.NotInCloud to PreparedChapterDownloadInputs(),
            PreparedChapterDownloadState.Installed to
                PreparedChapterDownloadInputs(cloudAudio = cloudAudio(), installedLocally = true),
            PreparedChapterDownloadState.AvailableInCloud to
                PreparedChapterDownloadInputs(cloudAudio = cloudAudio()),
            PreparedChapterDownloadState.Downloading to
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(),
                    transfer = PreparedChapterTransfer(state = "transferring"),
                ),
            PreparedChapterDownloadState.Installing to
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(),
                    transfer = PreparedChapterTransfer(state = "verifying"),
                ),
            PreparedChapterDownloadState.FailedDeviceFull to
                PreparedChapterDownloadInputs(cloudAudio = cloudAudio(1_000), freeBytesOnDevice = 10),
            PreparedChapterDownloadState.FailedNoNetwork to
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(),
                    transfer = PreparedChapterTransfer(state = "failed", lastError = "timeout"),
                ),
            PreparedChapterDownloadState.FailedArchiveRejected to
                PreparedChapterDownloadInputs(cloudAudio = cloudAudio(), archiveRejected = true),
            PreparedChapterDownloadState.FailedGoneFromCloud to
                PreparedChapterDownloadInputs(
                    cloudAudio = cloudAudio(),
                    transfer = PreparedChapterTransfer(state = "failed", lastError = "cloud_file_unavailable"),
                ),
        )
        // Every state the enum has is covered.
        assertEquals(
            PreparedChapterDownloadState.entries.toSet(),
            cases.keys,
            "a new state needs a case here",
        )
        for ((expected, inputs) in cases) {
            assertEquals(expected, preparedChapterDownloadState(inputs), expected.name)
            val shouldStart = expected == PreparedChapterDownloadState.AvailableInCloud ||
                expected == PreparedChapterDownloadState.FailedNoNetwork
            assertEquals(shouldStart, shouldStartPreparedChapterDownload(inputs), expected.name)
        }
    }

    @Test
    fun `a refused archive is never fetched again by itself`() {
        assertFalse(
            shouldStartPreparedChapterDownload(
                PreparedChapterDownloadInputs(cloudAudio = cloudAudio(), archiveRejected = true),
            ),
        )
        assertTrue(
            shouldStartPreparedChapterDownload(
                PreparedChapterDownloadInputs(cloudAudio = cloudAudio()),
            ),
        )
    }

    private fun state(transfer: PreparedChapterTransfer) = preparedChapterDownloadState(
        PreparedChapterDownloadInputs(cloudAudio = cloudAudio(), transfer = transfer),
    )

    private fun cloudAudio(sizeBytes: Long = 4_096, forCurrentSettings: Boolean = true) =
        PreparedChapterCloudAudio(sizeBytes = sizeBytes, forCurrentSettings = forCurrentSettings)
}
