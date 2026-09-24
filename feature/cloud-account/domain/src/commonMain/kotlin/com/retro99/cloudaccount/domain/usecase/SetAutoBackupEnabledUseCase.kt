package com.retro99.cloudaccount.domain.usecase

import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.user.api.UserRegistry
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

@Factory
class SetAutoBackupEnabledUseCase(
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
    @Provided private val uploadRightsAttestationRepository: UploadRightsAttestationRepository,
    @Provided private val userRegistry: UserRegistry,
) {
    suspend operator fun invoke(
        enabled: Boolean,
        rightsAttested: Boolean = false,
    ): CloudProfileLink {
        val localProfileId = userRegistry.getActiveProfileIdOrDefault()
        val profileLink = profileLinkRepository.getForLocalProfile(localProfileId)
            ?: error("A linked cloud profile is required to enable automatic backups")

        if (enabled) {
            check(rightsAttested) {
                "Upload-rights acceptance is required before enabling automatic backups"
            }
            uploadRightsAttestationRepository.record(localProfileId)
        }

        profileLinkRepository.setAutoBackupEnabled(localProfileId, enabled)
        return profileLinkRepository.getForLocalProfile(localProfileId)
            ?: profileLink.copy(autoBackupEnabled = enabled)
    }
}
