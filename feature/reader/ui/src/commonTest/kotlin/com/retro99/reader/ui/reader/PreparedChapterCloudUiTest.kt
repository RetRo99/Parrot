package com.retro99.reader.ui.reader

import com.retro99.translations.StringRes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
 * The one cloud line of the prepared-chapter row. Every backup state and every
 * download state has a case, so a state cannot be added without deciding what
 * the user is told; and the line is always one line with at most one button.
 */
class PreparedChapterCloudUiTest {

    @Test
    fun `every backup state says exactly one thing or says nothing`() {
        val expected = mapOf(
            PreparedChapterBackupState.NotApplicable to null,
            PreparedChapterBackupState.BackupOff to null,
            PreparedChapterBackupState.BackedUp to StringRes.reader_tts_prepared_cloud_backed_up,
            PreparedChapterBackupState.Queued to StringRes.reader_tts_prepared_cloud_queued,
            PreparedChapterBackupState.Uploading to StringRes.reader_tts_prepared_cloud_uploading,
            PreparedChapterBackupState.WaitingForWifi to StringRes.reader_tts_prepared_cloud_waiting_wifi,
            PreparedChapterBackupState.WaitingForBookBackup to StringRes.reader_tts_prepared_cloud_waiting_book,
            PreparedChapterBackupState.WaitingForBooks to StringRes.reader_tts_prepared_cloud_waiting_books,
            PreparedChapterBackupState.StorageFull to StringRes.reader_tts_prepared_cloud_storage_full,
            PreparedChapterBackupState.FailedWillRetry to StringRes.reader_tts_prepared_cloud_retrying,
            PreparedChapterBackupState.Failed to StringRes.reader_tts_prepared_cloud_failed,
            PreparedChapterBackupState.NotAllowed to StringRes.reader_tts_prepared_cloud_not_allowed,
        )
        assertEquals(
            PreparedChapterBackupState.entries.toSet(),
            expected.keys,
            "a new backup state needs a line here",
        )
        for ((backup, status) in expected) {
            val line = preparedChapterCloudUi(PreparedChapterCloudInputs(backup = backup))
            assertEquals(status, line?.status, backup.name)
        }
    }

    @Test
    fun `every download state says one thing or lets the backup line speak`() {
        val expected = mapOf(
            PreparedChapterDownloadState.NotInCloud to null,
            PreparedChapterDownloadState.Installed to null,
            PreparedChapterDownloadState.AvailableInCloud to StringRes.reader_tts_prepared_cloud_available,
            PreparedChapterDownloadState.Downloading to StringRes.reader_tts_prepared_cloud_downloading,
            PreparedChapterDownloadState.Installing to StringRes.reader_tts_prepared_cloud_installing,
            PreparedChapterDownloadState.FailedNoNetwork to
                StringRes.reader_tts_prepared_cloud_download_failed_network,
            PreparedChapterDownloadState.FailedDeviceFull to
                StringRes.reader_tts_prepared_cloud_download_failed_space,
            PreparedChapterDownloadState.FailedArchiveRejected to
                StringRes.reader_tts_prepared_cloud_download_failed_rejected,
            PreparedChapterDownloadState.FailedGoneFromCloud to
                StringRes.reader_tts_prepared_cloud_download_failed_gone,
        )
        assertEquals(
            PreparedChapterDownloadState.entries.toSet(),
            expected.keys,
            "a new download state needs a line here",
        )
        for ((download, status) in expected) {
            val line = preparedChapterCloudUi(
                PreparedChapterCloudInputs(
                    download = download,
                    cloudAudio = PreparedChapterCloudAudio(4_096, forCurrentSettings = true),
                ),
            )
            assertEquals(status, line?.status, download.name)
        }
    }

    @Test
    fun `cloud audio is offered with its size and Download is the only action`() {
        val line = assertNotNull(
            preparedChapterCloudUi(
                PreparedChapterCloudInputs(
                    download = PreparedChapterDownloadState.AvailableInCloud,
                    cloudAudio = PreparedChapterCloudAudio(2_097_152, forCurrentSettings = true),
                ),
            ),
        )

        assertEquals(StringRes.reader_tts_prepared_cloud_available, line.status)
        assertEquals(listOf(preparedChapterSizeLabel(2_097_152)), line.statusArgs)
        assertEquals(PreparedChapterAction.DOWNLOAD, line.action)
    }

    @Test
    fun `cloud audio made for other settings says so and still offers Download`() {
        val line = assertNotNull(
            preparedChapterCloudUi(
                PreparedChapterCloudInputs(
                    download = PreparedChapterDownloadState.AvailableInCloud,
                    cloudAudio = PreparedChapterCloudAudio(4_096, forCurrentSettings = false),
                ),
            ),
        )

        assertEquals(StringRes.reader_tts_prepared_cloud_available_other, line.status)
        assertEquals(PreparedChapterAction.DOWNLOAD, line.action)
    }

    @Test
    fun `a full allowance is the one backup state with a Manage storage button`() {
        val withButton = PreparedChapterBackupState.entries.filter { backup ->
            preparedChapterCloudUi(PreparedChapterCloudInputs(backup = backup))?.action != null
        }

        assertEquals(listOf(PreparedChapterBackupState.StorageFull), withButton)
        assertEquals(
            PreparedChapterAction.MANAGE_STORAGE,
            preparedChapterCloudUi(
                PreparedChapterCloudInputs(backup = PreparedChapterBackupState.StorageFull),
            )?.action,
        )
    }

    @Test
    fun `what the cloud holds beats what the backup is doing`() {
        val line = assertNotNull(
            preparedChapterCloudUi(
                PreparedChapterCloudInputs(
                    backup = PreparedChapterBackupState.WaitingForWifi,
                    download = PreparedChapterDownloadState.AvailableInCloud,
                    cloudAudio = PreparedChapterCloudAudio(4_096, forCurrentSettings = true),
                ),
            ),
        )

        assertEquals(StringRes.reader_tts_prepared_cloud_available, line.status)
    }

    @Test
    fun `audio already on this device lets the backup line speak`() {
        val line = assertNotNull(
            preparedChapterCloudUi(
                PreparedChapterCloudInputs(
                    backup = PreparedChapterBackupState.BackedUp,
                    download = PreparedChapterDownloadState.Installed,
                ),
            ),
        )

        assertEquals(StringRes.reader_tts_prepared_cloud_backed_up, line.status)
    }

    @Test
    fun `no account and backup off say nothing at all`() {
        assertNull(preparedChapterCloudUi(PreparedChapterCloudInputs()))
        assertNull(
            preparedChapterCloudUi(
                PreparedChapterCloudInputs(backup = PreparedChapterBackupState.BackupOff),
            ),
        )
    }

    @Test
    fun `no combination of states produces more than one line or an unexpected button`() {
        val allowed = setOf(PreparedChapterAction.DOWNLOAD, PreparedChapterAction.MANAGE_STORAGE)
        for (backup in PreparedChapterBackupState.entries) {
            for (download in PreparedChapterDownloadState.entries) {
                val line = preparedChapterCloudUi(
                    PreparedChapterCloudInputs(
                        backup = backup,
                        download = download,
                        cloudAudio = PreparedChapterCloudAudio(1, forCurrentSettings = true),
                    ),
                ) ?: continue
                val where = "$backup + $download"
                // One status with at most one argument, and a button only from
                // the two this row may offer: it cannot become a second card.
                assertTrue(line.statusArgs.size <= 1, where)
                assertTrue(line.action == null || line.action in allowed, where)
            }
        }
    }

    @Test
    fun `the delete confirmation mentions the cloud only when there is a copy there`() {
        for (backup in PreparedChapterBackupState.entries) {
            val mentions = preparedChapterDeleteRemovesCloudCopy(
                PreparedChapterCloudInputs(backup = backup),
            )
            val expected = backup == PreparedChapterBackupState.BackedUp ||
                backup == PreparedChapterBackupState.Uploading ||
                backup == PreparedChapterBackupState.Queued
            assertEquals(expected, mentions, backup.name)
        }
    }

    @Test
    fun `a chapter waiting for Wi-Fi has nothing in the cloud to promise about`() {
        assertFalse(
            preparedChapterDeleteRemovesCloudCopy(
                PreparedChapterCloudInputs(backup = PreparedChapterBackupState.WaitingForWifi),
            ),
        )
    }
}
