package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.CloudProfileLinkRepository
import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.getObject
import com.retro99.preferences.api.putObject
import com.retro99.user.api.UserProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CloudProfileLinkRepository::class])
class CloudProfileLinkDataRepository(
    @Provided private val preferences: Preferences,
) : CloudProfileLinkRepository {
    private val mutex = Mutex()

    override suspend fun getForLocalProfile(localProfileId: String): CloudProfileLink? = mutex.withLock {
        readLinks().firstOrNull { record -> record.localProfileId == localProfileId }
            ?.toDomain()
    }

    override suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink? = mutex.withLock {
        readLinks().firstOrNull { record -> record.cloudUserId == cloudUserId }
            ?.toDomain()
    }

    override suspend fun link(
        localProfileId: String,
        cloudUserId: String,
    ): CloudProfileLinkResult = mutex.withLock {
        val links = removeOrphanedLinks(readLinks())
        writeLinksIfChanged(readLinks(), links)
        val localLink = links.firstOrNull { record -> record.localProfileId == localProfileId }
        if (localLink != null) {
            return@withLock if (localLink.cloudUserId == cloudUserId) {
                CloudProfileLinkResult.Linked(localLink.toDomain())
            } else {
                CloudProfileLinkResult.LocalProfileAlreadyLinked(localLink.toDomain())
            }
        }

        val accountLink = links.firstOrNull { record -> record.cloudUserId == cloudUserId }
        if (accountLink != null) {
            return@withLock CloudProfileLinkResult.CloudAccountAlreadyLinked(accountLink.toDomain())
        }

        val link = CloudProfileLinkRecord(
            localProfileId = localProfileId,
            cloudUserId = cloudUserId,
            syncEnabled = false,
            initialMergeCompleted = false,
        )
        writeLinks(links + link)
        CloudProfileLinkResult.Linked(link.toDomain())
    }

    override suspend fun setSyncEnabled(localProfileId: String, enabled: Boolean) = mutex.withLock {
        updateLink(localProfileId) { link -> link.copy(syncEnabled = enabled) }
    }

    override suspend fun markInitialMergeCompleted(localProfileId: String) = mutex.withLock {
        updateLink(localProfileId) { link -> link.copy(initialMergeCompleted = true) }
    }

    private fun updateLink(
        localProfileId: String,
        transform: (CloudProfileLinkRecord) -> CloudProfileLinkRecord,
    ) {
        val links = readLinks()
        val index = links.indexOfFirst { record -> record.localProfileId == localProfileId }
        if (index < 0) return
        writeLinks(links.toMutableList().apply { this[index] = transform(this[index]) })
    }

    private fun readLinks(): List<CloudProfileLinkRecord> {
        return preferences.getObject<List<CloudProfileLinkRecord>>(
            PreferencesKey.CloudProfileLinks,
        ).orEmpty()
    }

    private fun writeLinks(links: List<CloudProfileLinkRecord>) {
        preferences.putObject(PreferencesKey.CloudProfileLinks, links)
    }

    private fun writeLinksIfChanged(
        originalLinks: List<CloudProfileLinkRecord>,
        links: List<CloudProfileLinkRecord>,
    ) {
        if (links != originalLinks) writeLinks(links)
    }

    private fun removeOrphanedLinks(
        links: List<CloudProfileLinkRecord>,
    ): List<CloudProfileLinkRecord> {
        val profiles = preferences.getObject<List<UserProfile>>(
            PreferencesKey.UserProfiles,
        ) ?: return links
        val profileIds = profiles.map { profile -> profile.id }.toSet()
        return links.filter { link -> link.localProfileId in profileIds }
    }
}

@Serializable
private data class CloudProfileLinkRecord(
    val localProfileId: String,
    val cloudUserId: String,
    val syncEnabled: Boolean,
    val initialMergeCompleted: Boolean,
)

private fun CloudProfileLinkRecord.toDomain(): CloudProfileLink {
    return CloudProfileLink(
        localProfileId = localProfileId,
        cloudUserId = cloudUserId,
        syncEnabled = syncEnabled,
        initialMergeCompleted = initialMergeCompleted,
    )
}
