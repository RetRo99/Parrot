package com.retro99.reader.ui.tts

import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.books.domain.toBookFileUploadAttestation
import com.retro99.cloudaccount.domain.CloudAccountRepository
import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.UploadRightsAttestationRepository
import com.retro99.cloudaccount.domain.model.isActiveFor
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * The account facts a prepared-chapter backup needs, and nothing else. A port,
 * so the queue can be tested without four repositories and a user registry.
 */
internal interface PreparedBackupAccount {
    /** Null when there is no cloud account this device may upload for. */
    suspend fun snapshot(): PreparedBackupAccountSnapshot?
}

internal data class PreparedBackupAccountSnapshot(
    val localProfileId: String,
    val autoBackupEnabled: Boolean,
    /**
     * Null when the user has to attest again. Prepared audio then waits rather
     * than putting a rights dialog in front of someone who only asked for a
     * chapter to be read aloud.
     */
    val attestation: UploadRightsAttestation?,
)

@Single(binds = [PreparedBackupAccount::class])
internal class CloudPreparedBackupAccount(
    @Provided private val users: UserRegistry,
    @Provided private val profileLinks: CloudProfileLinkRepository,
    @Provided private val accounts: CloudAccountRepository,
    @Provided private val attestations: UploadRightsAttestationRepository,
) : PreparedBackupAccount {

    override suspend fun snapshot(): PreparedBackupAccountSnapshot? = try {
        val localProfileId = users.getActiveProfileIdOrDefault()
        val link = profileLinks.getForLocalProfile(localProfileId)
        if (!link.isActiveFor(accounts.currentAuthState())) {
            null
        } else {
            PreparedBackupAccountSnapshot(
                localProfileId = localProfileId,
                autoBackupEnabled = link?.autoBackupEnabled == true,
                attestation = if (attestations.requiresReattestation(localProfileId)) {
                    null
                } else {
                    attestations.current(localProfileId)?.toBookFileUploadAttestation()
                },
            )
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        // Reading aloud is a local feature. An unreachable cloud identity means
        // "no backup for now", never a failure the reader has to report.
        null
    }
}
