package com.retro99.cloudaccount.domain

import com.retro99.cloudaccount.domain.model.UploadAttestationRecord

interface UploadRightsAttestationRepository {
    suspend fun current(localProfileId: String): UploadAttestationRecord?

    suspend fun record(localProfileId: String): UploadAttestationRecord

    suspend fun requiresReattestation(localProfileId: String): Boolean
}
