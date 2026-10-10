package com.retro99.reader.ui.reader

/**
 * What one chapter's prepared audio is doing in the other direction: what the
 * cloud holds for it, and what fetching it is doing. Pure, so the row, the
 * download queue and the tests all read the same decision.
 */
enum class PreparedChapterDownloadState {
    /** The cloud holds nothing for this chapter, or there is no account. */
    NotInCloud,

    /** It is already on this device. There is nothing to fetch. */
    Installed,

    /** In the cloud, and this device can fetch it. */
    AvailableInCloud,

    Downloading,

    /** Verifying and installing, after the bytes have all arrived. */
    Installing,

    /** The device has no room for it. Permanent until the user makes room. */
    FailedDeviceFull,

    /** Nothing arrived. Pressing Download again is the right thing to do. */
    FailedNoNetwork,

    /**
     * The bytes arrived and were refused. Nothing was installed. Retrying will
     * not help while the cloud holds the same file.
     */
    FailedArchiveRejected,

    /** It is not in the cloud any more: deleted, taken down, or its book went. */
    FailedGoneFromCloud,
}

/** What the cloud holds for one chapter. */
data class PreparedChapterCloudAudio(
    val sizeBytes: Long,
    /**
     * The archive was made for the settings now selected, so the chapter will
     * play instantly once it is installed. When false the row says it was made
     * for other settings, exactly as it already does for local audio -- which
     * settings cannot be told from the cloud, because the path carries only a
     * hash of them.
     */
    val forCurrentSettings: Boolean,
)

data class PreparedChapterDownloadInputs(
    val cloudAudio: PreparedChapterCloudAudio? = null,
    /** A complete chapter is already in the prepared store for this chapter. */
    val installedLocally: Boolean = false,
    val transfer: PreparedChapterTransfer? = null,
    val freeBytesOnDevice: Long = Long.MAX_VALUE,
    /** Set when the bytes arrived and the archive was refused. */
    val archiveRejected: Boolean = false,
)

/** Reasons the cloud file is simply not there any more. */
private val GONE_REASONS = setOf(
    "cloud_file_unavailable",
    "cloud_file_changed",
    "unsupported_hash_algorithm",
)

/** Reasons the bytes arrived and were not what they claimed to be. */
private val REJECTED_REASONS = setOf("verify_failed", "archive_rejected")

/**
 * An archive is unpacked into a staging folder beside the target and the old
 * folder is only removed at the end, so for a moment both exist. Asking for
 * room for the archive and twice its unpacked size is the honest bound.
 */
internal const val PREPARED_DOWNLOAD_HEADROOM = 3

/**
 * Order matters. What is already true beats what might be: a chapter on the
 * device needs no download whatever the cloud says, and a transfer in flight
 * beats a stale failure. Then the things that stop a download before it starts,
 * then the offer.
 */
fun preparedChapterDownloadState(
    inputs: PreparedChapterDownloadInputs,
): PreparedChapterDownloadState {
    if (inputs.installedLocally) return PreparedChapterDownloadState.Installed
    val cloud = inputs.cloudAudio ?: return PreparedChapterDownloadState.NotInCloud

    val transfer = inputs.transfer
    when (transfer?.state) {
        "transferring" -> return PreparedChapterDownloadState.Downloading
        "verifying", "finalizing" -> return PreparedChapterDownloadState.Installing
        "pending" -> if (!transfer.willRetry) return PreparedChapterDownloadState.Downloading
    }

    // The bytes arrived and the archive was refused. The transfer itself
    // reads completed, so only this says so.
    if (inputs.archiveRejected) return PreparedChapterDownloadState.FailedArchiveRejected

    if (transfer != null && (transfer.state == "failed" || transfer.willRetry)) {
        val reason = transfer.lastError
        return when {
            reason in GONE_REASONS -> PreparedChapterDownloadState.FailedGoneFromCloud
            reason in REJECTED_REASONS -> PreparedChapterDownloadState.FailedArchiveRejected
            reason == "device_storage_full" -> PreparedChapterDownloadState.FailedDeviceFull
            else -> PreparedChapterDownloadState.FailedNoNetwork
        }
    }

    if (inputs.freeBytesOnDevice < cloud.sizeBytes * PREPARED_DOWNLOAD_HEADROOM) {
        return PreparedChapterDownloadState.FailedDeviceFull
    }
    return PreparedChapterDownloadState.AvailableInCloud
}

/** Whether pressing Download now would actually start one. */
fun shouldStartPreparedChapterDownload(inputs: PreparedChapterDownloadInputs): Boolean =
    when (preparedChapterDownloadState(inputs)) {
        PreparedChapterDownloadState.AvailableInCloud,
        PreparedChapterDownloadState.FailedNoNetwork,
        -> true

        else -> false
    }
