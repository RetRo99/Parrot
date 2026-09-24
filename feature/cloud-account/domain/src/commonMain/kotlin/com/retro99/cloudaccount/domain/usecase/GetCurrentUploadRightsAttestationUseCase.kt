package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single
class GetCurrentUploadRightsAttestationUseCase(
    @Provided private val repository: UploadRightsAttestationRepository,
) {
    suspend operator fun invoke(localProfileId: String): UploadAttestationRecord {
        check(!repository.requiresReattestation(localProfileId)) {
            "Current upload rights attestation is required before backing up books"
        }
        return requireNotNull(repository.current(localProfileId)) {
            "Upload rights attestation is missing for this profile"
        }
    }
}
