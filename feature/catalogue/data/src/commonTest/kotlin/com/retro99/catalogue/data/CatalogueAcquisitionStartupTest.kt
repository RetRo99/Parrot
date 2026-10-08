package com.retro99.catalogue.data

import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.user.api.UserProfile
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueAcquisitionStartupTest {

    @Test
    fun `downloads are settled for the profile open at start and for each one opened later`() = runTest {
        // Given: no profile yet, as on a first launch
        val users = FakeUsers()
        val manager = RecordingManager(users)
        settleDownloadsWhenProfileOpens(manager, users, backgroundScope)
        runCurrent()
        assertEquals(emptyList(), manager.restoredFor)

        // When
        users.active.value = profile("p1")
        runCurrent()
        users.active.value = profile("p1").copy(name = "Renamed")
        runCurrent()
        users.active.value = profile("p2")
        runCurrent()

        // Then: once per profile, not once per change of its details
        assertEquals(listOf("p1", "p2"), manager.restoredFor)
    }

    @Test
    fun `a profile that cannot be settled does not stop the next one`() = runTest {
        // Given
        val users = FakeUsers()
        val manager = RecordingManager(users)
        manager.failFor = "p1"
        settleDownloadsWhenProfileOpens(manager, users, backgroundScope)

        // When
        users.active.value = profile("p1")
        runCurrent()
        users.active.value = profile("p2")
        runCurrent()

        // Then
        assertEquals(listOf("p1", "p2"), manager.restoredFor)
    }

    @Test
    fun `a deleted profile's staging folder is removed, and nothing is removed before the profiles are known`() = runTest {
        // Given: two profiles with a staged file each, and a folder left by a profile deleted earlier
        val users = FakeUsers()
        val files = FakeStagingFiles(TestWorld())
        val staged = listOf("p1", "p2", "long-gone").map { profileId ->
            files.newPartPath(profileId).also { path -> files.openForWriting(path).close() }
        }
        removeStagingOfDeletedProfiles(files, users, backgroundScope)
        runCurrent()
        assertEquals(staged.toSet(), files.files.keys)

        // When the profiles are loaded
        users.all.value = listOf(profile("p1"), profile("p2"))
        runCurrent()

        // Then only the leftover folder is gone
        assertEquals(staged.take(2).toSet(), files.files.keys)

        // When a profile is deleted
        users.all.value = listOf(profile("p1"))
        runCurrent()

        // Then
        assertEquals(setOf(staged.first()), files.files.keys)
    }

    private fun profile(id: String) = UserProfile(id = id, name = "Reader", createdAt = 0)

    private class RecordingManager(private val users: FakeUsers) : CatalogueAcquisitionManager {
        val restoredFor = mutableListOf<String>()
        var failFor: String? = null
        override suspend fun restoreAfterRestart() {
            val profileId = users.active.value!!.id
            restoredFor += profileId
            if (profileId == failFor) error("This profile's database cannot be read")
        }

        override fun observeAcquisitions(): Flow<List<CatalogueAcquisition>> = emptyFlow()
        override suspend fun request(request: CatalogueAcquisitionRequest): CatalogueRequestOutcome = error("unused")
        override suspend fun cancel(requestId: String) = error("unused")
        override suspend fun retry(requestId: String) = error("unused")
        override suspend fun dismiss(requestId: String) = error("unused")
        override suspend fun startAgain(requestId: String) = error("unused")
        override suspend fun signedIn(sourceId: String) = error("unused")
        override suspend fun start() = error("unused")
        override suspend fun finishedInLast24Hours() = error("unused")
        override suspend fun purgeFinished() = error("unused")
        override suspend fun purgeExpired() = error("unused")
    }

    private class FakeUsers : UserRegistry {
        val active = MutableStateFlow<UserProfile?>(null)

        override fun observeActiveProfile(): Flow<UserProfile?> = active
        override fun getActiveProfileId(): String? = active.value?.id
        override suspend fun getActiveProfile(): UserProfile? = active.value
        override fun isProfileActive() = active.value != null
        val all = MutableStateFlow<List<UserProfile>>(emptyList())
        override fun observeAllProfiles(): Flow<List<UserProfile>> = all
        override suspend fun getAllProfiles() = error("unused")
        override suspend fun createProfile(id: String?, name: String, avatarId: Int?) = error("unused")
        override suspend fun updateProfile(profile: UserProfile) = error("unused")
        override suspend fun deleteProfile(profileId: String) = error("unused")
        override suspend fun getProfile(profileId: String) = error("unused")
        override suspend fun setActiveProfile(profileId: String) = error("unused")
        override suspend fun clearActiveProfile() = error("unused")
        override suspend fun hasProfiles() = error("unused")
    }
}
