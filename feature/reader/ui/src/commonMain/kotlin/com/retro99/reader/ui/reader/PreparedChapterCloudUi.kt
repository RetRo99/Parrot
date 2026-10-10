package com.retro99.reader.ui.reader

import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import resources.translations.reader_tts_prepared_cloud_available
import resources.translations.reader_tts_prepared_cloud_available_other
import resources.translations.reader_tts_prepared_cloud_backed_up
import resources.translations.reader_tts_prepared_cloud_download_failed_gone
import resources.translations.reader_tts_prepared_cloud_download_failed_network
import resources.translations.reader_tts_prepared_cloud_download_failed_rejected
import resources.translations.reader_tts_prepared_cloud_download_failed_space
import resources.translations.reader_tts_prepared_cloud_downloading
import resources.translations.reader_tts_prepared_cloud_failed
import resources.translations.reader_tts_prepared_cloud_installing
import resources.translations.reader_tts_prepared_cloud_not_allowed
import resources.translations.reader_tts_prepared_cloud_queued
import resources.translations.reader_tts_prepared_cloud_retrying
import resources.translations.reader_tts_prepared_cloud_storage_full
import resources.translations.reader_tts_prepared_cloud_uploading
import resources.translations.reader_tts_prepared_cloud_waiting_book
import resources.translations.reader_tts_prepared_cloud_waiting_books
import resources.translations.reader_tts_prepared_cloud_waiting_wifi

/**
 * What Parrot Cloud adds to the prepared-chapter row: **one line of status**
 * under the existing content, and at most one button. Never a second card.
 *
 * Pure, and decided here rather than in the composable, because this project
 * has no Compose render-test harness.
 */
internal data class PreparedChapterCloudUi(
    val status: StringResource,
    val statusArgs: List<Any> = emptyList(),
    val action: PreparedChapterAction? = null,
)

/** Everything the cloud line needs. Defaults mean "no cloud at all here". */
data class PreparedChapterCloudInputs(
    val backup: PreparedChapterBackupState = PreparedChapterBackupState.NotApplicable,
    val download: PreparedChapterDownloadState = PreparedChapterDownloadState.NotInCloud,
    val cloudAudio: PreparedChapterCloudAudio? = null,
)

/**
 * One line, and the order is what the user can act on first.
 *
 * Audio this device does not have but the cloud does is the most useful thing
 * the row can say, so it comes first; `Installed` means the download half has
 * nothing to add and the backup half speaks instead. A backup state the user
 * has to resolve -- a full allowance, a refusal -- beats one that resolves
 * itself. `BackupOff` and `NotApplicable` say nothing at all: the user turned
 * backup off, or there is no account, and neither is news about this chapter.
 */
internal fun preparedChapterCloudUi(
    inputs: PreparedChapterCloudInputs,
): PreparedChapterCloudUi? {
    downloadLine(inputs)?.let { line -> return line }
    return backupLine(inputs.backup)
}

private fun downloadLine(inputs: PreparedChapterCloudInputs): PreparedChapterCloudUi? =
    when (inputs.download) {
        PreparedChapterDownloadState.AvailableInCloud -> {
            val audio = inputs.cloudAudio
            PreparedChapterCloudUi(
                status = if (audio?.forCurrentSettings == false) {
                    StringRes.reader_tts_prepared_cloud_available_other
                } else {
                    StringRes.reader_tts_prepared_cloud_available
                },
                statusArgs = listOf(preparedChapterSizeLabel(audio?.sizeBytes ?: 0L)),
                action = PreparedChapterAction.DOWNLOAD,
            )
        }

        PreparedChapterDownloadState.Downloading ->
            PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_downloading)

        PreparedChapterDownloadState.Installing ->
            PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_installing)

        PreparedChapterDownloadState.FailedNoNetwork -> PreparedChapterCloudUi(
            status = StringRes.reader_tts_prepared_cloud_download_failed_network,
            action = PreparedChapterAction.DOWNLOAD,
        )

        PreparedChapterDownloadState.FailedDeviceFull ->
            PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_download_failed_space)

        PreparedChapterDownloadState.FailedArchiveRejected ->
            PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_download_failed_rejected)

        PreparedChapterDownloadState.FailedGoneFromCloud ->
            PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_download_failed_gone)

        // Nothing there, or it is already here: the backup line speaks.
        PreparedChapterDownloadState.NotInCloud,
        PreparedChapterDownloadState.Installed,
        -> null
    }

private fun backupLine(backup: PreparedChapterBackupState): PreparedChapterCloudUi? = when (backup) {
    PreparedChapterBackupState.BackedUp ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_backed_up)

    PreparedChapterBackupState.StorageFull -> PreparedChapterCloudUi(
        status = StringRes.reader_tts_prepared_cloud_storage_full,
        action = PreparedChapterAction.MANAGE_STORAGE,
    )

    PreparedChapterBackupState.Uploading ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_uploading)

    PreparedChapterBackupState.Queued ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_queued)

    PreparedChapterBackupState.WaitingForWifi ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_waiting_wifi)

    PreparedChapterBackupState.WaitingForBookBackup ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_waiting_book)

    PreparedChapterBackupState.WaitingForBooks ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_waiting_books)

    PreparedChapterBackupState.FailedWillRetry ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_retrying)

    PreparedChapterBackupState.Failed ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_failed)

    PreparedChapterBackupState.NotAllowed ->
        PreparedChapterCloudUi(StringRes.reader_tts_prepared_cloud_not_allowed)

    // The user's own choice, and no account: neither is news about a chapter.
    PreparedChapterBackupState.BackupOff,
    PreparedChapterBackupState.NotApplicable,
    -> null
}

/**
 * Whether deleting this chapter also removes it from Parrot Cloud, which is
 * what the confirmation has to say.
 */
internal fun preparedChapterDeleteRemovesCloudCopy(
    inputs: PreparedChapterCloudInputs,
): Boolean = inputs.backup in CLOUD_COPY_EXISTS_OR_SOON

private val CLOUD_COPY_EXISTS_OR_SOON = setOf(
    PreparedChapterBackupState.BackedUp,
    PreparedChapterBackupState.Uploading,
    PreparedChapterBackupState.Queued,
)
