package com.retro99.reader.ui.reader

/**
 * What the backup of one prepared chapter is doing, and nothing else. Pure, so
 * the row, the queue and the tests all read the same decision.
 */
enum class PreparedChapterBackupState {
    /** Nothing to say: no account, or no finished chapter to back up. */
    NotApplicable,

    /** The account is not allowed to upload. One message, no retrying. */
    NotAllowed,

    /** Auto-backup is off. The user's own choice, not a problem to report. */
    BackupOff,

    /** Books are uploaded before audio, so this waits its turn. */
    WaitingForBooks,

    /** Audio is never uploaded for a book that has no backup in the cloud. */
    WaitingForBookBackup,

    /** On Wi-Fi only. */
    WaitingForWifi,

    /** Every condition is met and it has not gone up yet. */
    Queued,

    Uploading,

    BackedUp,

    /**
     * The allowance is full. Permanent until something changes: there is no
     * retry loop behind this, only the one message and "Manage storage".
     */
    StorageFull,

    FailedWillRetry,

    /** Out of attempts, or refused for a reason retrying cannot fix. */
    Failed,
}

/** The transfer the engine persisted for this chapter, if there is one. */
data class PreparedChapterTransfer(
    val state: String,
    val willRetry: Boolean = false,
    val lastError: String? = null,
)

data class PreparedChapterBackupInputs(
    val signedIn: Boolean = false,
    val uploadsAllowed: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val onUnmeteredNetwork: Boolean = false,
    /** The book's own file is available in the cloud. */
    val bookBackedUp: Boolean = false,
    /** Books of this account still waiting to upload. Audio goes after them. */
    val booksWaitingToUpload: Int = 0,
    /** A partly prepared chapter is never packed or uploaded. */
    val chapterComplete: Boolean = false,
    /** The chapter's archive is already available in the cloud. */
    val alreadyBackedUp: Boolean = false,
    val transfer: PreparedChapterTransfer? = null,
)

/** Reasons the server gives that no amount of retrying will fix. */
private val PERMANENT_REJECTIONS = setOf(
    "uploads_not_enabled",
    "book_backup_unavailable",
    "invalid_upload_metadata",
    "file_too_large",
    "content_blocked",
    "cloud_book_not_owned",
)

private const val QUOTA_EXCEEDED = "quota_exceeded"

/**
 * Order matters, and it is the order of what the user needs to know first.
 *
 * What has already happened beats what might: a chapter that is up is backed up
 * whatever the switches now say, and a full allowance is reported even when the
 * device has since left Wi-Fi, because the user has to act on it. Only then do
 * the gates speak, outermost first -- no account, not allowed, switched off --
 * and only then the things that will resolve on their own.
 */
fun preparedChapterBackupState(
    inputs: PreparedChapterBackupInputs,
): PreparedChapterBackupState {
    val transfer = inputs.transfer
    if (inputs.alreadyBackedUp || transfer?.state == "completed") {
        return PreparedChapterBackupState.BackedUp
    }
    if (transfer?.lastError == QUOTA_EXCEEDED) return PreparedChapterBackupState.StorageFull

    if (!inputs.signedIn) return PreparedChapterBackupState.NotApplicable
    if (!inputs.uploadsAllowed) return PreparedChapterBackupState.NotAllowed
    if (!inputs.autoBackupEnabled) return PreparedChapterBackupState.BackupOff
    // Nothing to upload yet is not a backup problem.
    if (!inputs.chapterComplete) return PreparedChapterBackupState.NotApplicable

    if (transfer != null && transfer.lastError in PERMANENT_REJECTIONS) {
        // The server refusing for want of a book backup is the same fact the
        // gate below reports, and the gate says it better.
        return if (transfer.lastError == "book_backup_unavailable") {
            PreparedChapterBackupState.WaitingForBookBackup
        } else {
            PreparedChapterBackupState.Failed
        }
    }

    if (!inputs.bookBackedUp) return PreparedChapterBackupState.WaitingForBookBackup
    if (inputs.booksWaitingToUpload > 0) return PreparedChapterBackupState.WaitingForBooks
    if (!inputs.onUnmeteredNetwork) return PreparedChapterBackupState.WaitingForWifi

    return when {
        transfer == null -> PreparedChapterBackupState.Queued
        transfer.state == "transferring" || transfer.state == "finalizing" ->
            PreparedChapterBackupState.Uploading
        transfer.state == "failed" -> PreparedChapterBackupState.Failed
        transfer.willRetry -> PreparedChapterBackupState.FailedWillRetry
        else -> PreparedChapterBackupState.Queued
    }
}

/**
 * Whether to hand this chapter to the transfer engine now. Only one state means
 * yes; everything else is either already handled, waiting on something, or a
 * thing the user has to resolve.
 */
fun shouldQueuePreparedChapterUpload(inputs: PreparedChapterBackupInputs): Boolean =
    preparedChapterBackupState(inputs) == PreparedChapterBackupState.Queued
