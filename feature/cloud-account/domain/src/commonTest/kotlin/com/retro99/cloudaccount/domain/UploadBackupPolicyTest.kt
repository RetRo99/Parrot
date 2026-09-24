package com.retro99.cloudaccount.domain

import kotlin.test.Test
import kotlin.test.assertTrue

class UploadBackupPolicyTest {
    @Test
    fun `policy versions use legal draft formats`() {
        assertTrue(CLOUD_BACKUP_TOS_VERSION.matches(Regex("parrot-cloud-backup-tos-\\d{4}-\\d{2}-\\d{2}\\.\\d+")))
        assertTrue(CLOUD_BACKUP_ATTESTATION_VERSION.matches(Regex("attest-rights-v\\d+")))
    }
}
