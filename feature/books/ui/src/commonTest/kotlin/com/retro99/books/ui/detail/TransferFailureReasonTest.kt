package com.retro99.books.ui.detail

import com.retro99.translations.StringRes
import resources.translations.cloud_backup_reason_file_too_large
import resources.translations.cloud_backup_reason_generic
import resources.translations.cloud_backup_reason_quota_exceeded
import resources.translations.cloud_backup_reason_uploads_not_enabled
import resources.translations.cloud_download_reason_generic
import kotlin.test.Test
import kotlin.test.assertEquals

class TransferFailureReasonTest {
    @Test
    fun `server upload gates get their own message`() {
        assertEquals(
            StringRes.cloud_backup_reason_uploads_not_enabled,
            transferFailureReasonRes(download = false, reason = "uploads_not_enabled"),
        )
        assertEquals(
            StringRes.cloud_backup_reason_file_too_large,
            transferFailureReasonRes(download = false, reason = "file_too_large"),
        )
    }

    @Test
    fun `known and unknown reasons keep their existing mapping`() {
        assertEquals(
            StringRes.cloud_backup_reason_quota_exceeded,
            transferFailureReasonRes(download = false, reason = "quota_exceeded"),
        )
        assertEquals(
            StringRes.cloud_backup_reason_generic,
            transferFailureReasonRes(download = false, reason = "too_many_pending_uploads"),
        )
        assertEquals(
            StringRes.cloud_download_reason_generic,
            transferFailureReasonRes(download = true, reason = "uploads_not_enabled"),
        )
    }
}
