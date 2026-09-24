package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.cloudaccount.domain.model.UploadAttestationRecord
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudProfileLinkDataRepositoryTest {
    private val preferences = FakePreferences()
    private val repository = CloudProfileLinkDataRepository(preferences)

    @Test
    fun `linking a local profile twice keeps the original account`() = runTest {
        val firstResult = repository.link("profile-a", "account-a")
        val secondResult = repository.link("profile-a", "account-b")

        assertIs<CloudProfileLinkResult.Linked>(firstResult)
        val conflict = assertIs<CloudProfileLinkResult.LocalProfileAlreadyLinked>(secondResult)
        assertEquals("account-a", conflict.link.cloudUserId)
    }

    @Test
    fun `linking an account to a second local profile is rejected`() = runTest {
        repository.link("profile-a", "account-a")

        val result = repository.link("profile-b", "account-a")

        val conflict = assertIs<CloudProfileLinkResult.CloudAccountAlreadyLinked>(result)
        assertEquals("profile-a", conflict.link.localProfileId)
    }

    @Test
    fun `sync state changes are persisted`() = runTest {
        repository.link("profile-a", "account-a")
        repository.setSyncEnabled("profile-a", enabled = true)

        val link = repository.getForLocalProfile("profile-a")

        assertEquals(true, link?.syncEnabled)
    }

    @Test
    fun `auto backup defaults to off and its changes are persisted`() = runTest {
        repository.link("profile-a", "account-a")
        assertEquals(false, repository.getForLocalProfile("profile-a")?.autoBackupEnabled)

        repository.setAutoBackupEnabled("profile-a", enabled = true)
        val restoredRepository = CloudProfileLinkDataRepository(preferences)

        assertEquals(true, restoredRepository.getForLocalProfile("profile-a")?.autoBackupEnabled)
    }

    @Test
    fun `conflicting account does not change the existing link state`() = runTest {
        repository.link("profile-a", "account-a")
        repository.setSyncEnabled("profile-a", enabled = true)

        val result = repository.link("profile-a", "account-b")

        assertIs<CloudProfileLinkResult.LocalProfileAlreadyLinked>(result)
        val link = repository.getForLocalProfile("profile-a")
        assertEquals("account-a", link?.cloudUserId)
        assertEquals(true, link?.syncEnabled)
    }

    @Test
    fun `upload attestation persists with its linked account and is reused while policy matches`() = runTest {
        repository.link("profile-a", "account-a")
        val attestationRepository = UploadRightsAttestationDataRepository(repository)

        val recorded = attestationRepository.record("profile-a")
        val restoredRepository = UploadRightsAttestationDataRepository(
            CloudProfileLinkDataRepository(preferences),
        )

        assertEquals(recorded, restoredRepository.current("profile-a"))
        assertFalse(restoredRepository.requiresReattestation("profile-a"))
        assertEquals("account-a", CloudProfileLinkDataRepository(preferences)
            .getForLocalProfile("profile-a")?.cloudUserId)
    }

    @Test
    fun `upload attestation requires acceptance when shipped policy versions advance`() = runTest {
        repository.link("profile-a", "account-a")
        repository.setUploadAttestation(
            localProfileId = "profile-a",
            cloudUserId = "account-a",
            attestation = UploadAttestationRecord(
                attestedAt = "2026-09-01T00:00:00Z",
                tosVersion = "parrot-cloud-backup-tos-2026-09-01.1",
                attestationVersion = "attest-rights-v1",
            ),
        )

        assertTrue(UploadRightsAttestationDataRepository(repository).requiresReattestation("profile-a"))
    }

    @Test
    fun `a previously accepted newer version remains valid after app downgrade`() = runTest {
        repository.link("profile-a", "account-a")
        repository.setUploadAttestation(
            localProfileId = "profile-a",
            cloudUserId = "account-a",
            attestation = UploadAttestationRecord(
                attestedAt = "2026-10-01T00:00:00Z",
                tosVersion = "parrot-cloud-backup-tos-2026-10-01.1",
                attestationVersion = "attest-rights-v2",
            ),
        )

        assertFalse(UploadRightsAttestationDataRepository(repository).requiresReattestation("profile-a"))
    }

    @Test
    fun `unlink removes the persisted upload attestation`() = runTest {
        repository.link("profile-a", "account-a")
        val attestationRepository = UploadRightsAttestationDataRepository(repository)
        attestationRepository.record("profile-a")

        repository.unlink("profile-a")

        assertEquals(null, attestationRepository.current("profile-a"))
        assertTrue(attestationRepository.requiresReattestation("profile-a"))
    }

    @Test
    fun `legacy cloud profile links without an attestation still load`() = runTest {
        preferences.putString(
            PreferencesKey.CloudProfileLinks,
            """[{"localProfileId":"profile-a","cloudUserId":"account-a","syncEnabled":false}]""",
        )

        val migratedRepository = CloudProfileLinkDataRepository(preferences)

        assertEquals(null, migratedRepository.getForLocalProfile("profile-a")?.uploadAttestation)
        assertEquals(false, migratedRepository.getForLocalProfile("profile-a")?.autoBackupEnabled)
        assertTrue(UploadRightsAttestationDataRepository(migratedRepository).requiresReattestation("profile-a"))
    }
}

private class FakePreferences : Preferences {
    private val values = mutableMapOf<String, Any>()

    override fun getStringOrNull(key: PreferencesKey): String? = values[key.name] as? String

    override fun putString(key: PreferencesKey, value: String) {
        values[key.name] = value
    }

    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> =
        flowOf(getStringOrNull(key))

    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean =
        values[key.name] as? Boolean ?: defaultValue

    override fun putBoolean(key: PreferencesKey, value: Boolean) {
        values[key.name] = value
    }

    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
        flowOf(getBoolean(key, defaultValue))

    override fun getLong(key: PreferencesKey, defaultValue: Long): Long =
        values[key.name] as? Long ?: defaultValue

    override fun putLong(key: PreferencesKey, value: Long) {
        values[key.name] = value
    }

    override fun remove(key: PreferencesKey) {
        values.remove(key.name)
    }
}
