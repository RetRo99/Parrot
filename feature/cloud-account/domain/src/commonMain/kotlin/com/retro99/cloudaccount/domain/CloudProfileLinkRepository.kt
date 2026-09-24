package com.retro99.cloudaccount.domain

import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import kotlinx.coroutines.flow.Flow

interface CloudProfileLinkRepository {
    suspend fun getForLocalProfile(localProfileId: String): CloudProfileLink?

    suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink?

    fun observeForLocalProfile(localProfileId: String): Flow<CloudProfileLink?>

    suspend fun link(localProfileId: String, cloudUserId: String): CloudProfileLinkResult

    suspend fun setSyncEnabled(localProfileId: String, enabled: Boolean)

    suspend fun setAutoBackupEnabled(localProfileId: String, enabled: Boolean)

    suspend fun setUploadAttestation(
        localProfileId: String,
        cloudUserId: String,
        attestation: UploadAttestationRecord,
    )

    suspend fun deactivate(localProfileId: String)

    suspend fun unlink(localProfileId: String)
}
