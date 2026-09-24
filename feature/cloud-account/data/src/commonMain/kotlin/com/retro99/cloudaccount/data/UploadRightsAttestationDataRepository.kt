package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.CLOUD_BACKUP_ATTESTATION_VERSION
import com.retro99.cloudaccount.domain.CLOUD_BACKUP_TOS_VERSION
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [UploadRightsAttestationRepository::class])
class UploadRightsAttestationDataRepository(
    @Provided private val profileLinkRepository: CloudProfileLinkRepository,
) : UploadRightsAttestationRepository {
    override suspend fun current(localProfileId: String): UploadAttestationRecord? =
        profileLinkRepository.getForLocalProfile(localProfileId)?.uploadAttestation

    override suspend fun record(localProfileId: String): UploadAttestationRecord {
        val profileLink = profileLinkRepository.getForLocalProfile(localProfileId)
        check(profileLink != null) {
            "A cloud account must be linked before recording upload rights attestation"
        }
        val attestation = UploadAttestationRecord(
            attestedAt = Clock.System.now().toString(),
            tosVersion = CLOUD_BACKUP_TOS_VERSION,
            attestationVersion = CLOUD_BACKUP_ATTESTATION_VERSION,
        )
        profileLinkRepository.setUploadAttestation(localProfileId, profileLink.cloudUserId, attestation)
        return attestation
    }

    override suspend fun requiresReattestation(localProfileId: String): Boolean {
        val attestation = current(localProfileId) ?: return true
        val acceptedTosVersion = parseDatedVersion(attestation.tosVersion, TOS_VERSION_PATTERN)
            ?: return true
        val currentTosVersion = requireNotNull(parseDatedVersion(CLOUD_BACKUP_TOS_VERSION, TOS_VERSION_PATTERN))
        val acceptedRightsVersion = parseNumberedVersion(attestation.attestationVersion, RIGHTS_VERSION_PATTERN)
            ?: return true
        val currentRightsVersion = requireNotNull(
            parseNumberedVersion(CLOUD_BACKUP_ATTESTATION_VERSION, RIGHTS_VERSION_PATTERN),
        )

        return acceptedTosVersion.isOlderThan(currentTosVersion) || acceptedRightsVersion < currentRightsVersion
    }

    private fun parseDatedVersion(value: String, pattern: Regex): Pair<String, Int>? {
        val match = pattern.matchEntire(value) ?: return null
        val date = match.groupValues[1]
        val revision = match.groupValues[2].toIntOrNull() ?: return null
        return date to revision
    }

    private fun parseNumberedVersion(value: String, pattern: Regex): Int? =
        pattern.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()

    private fun Pair<String, Int>.isOlderThan(other: Pair<String, Int>): Boolean =
        first < other.first || (first == other.first && second < other.second)

    private companion object {
        val TOS_VERSION_PATTERN = Regex("parrot-cloud-backup-tos-(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)")
        val RIGHTS_VERSION_PATTERN = Regex("attest-rights-v(\\d+)")
    }
}
