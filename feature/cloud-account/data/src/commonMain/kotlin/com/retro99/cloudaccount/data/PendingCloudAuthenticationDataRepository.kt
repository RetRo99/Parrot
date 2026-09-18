package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.PendingCloudAuthenticationRepository
import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.getObject
import com.retro99.preferences.api.putObject
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [PendingCloudAuthenticationRepository::class])
class PendingCloudAuthenticationDataRepository(
    @Provided private val preferences: Preferences,
) : PendingCloudAuthenticationRepository {
    override suspend fun get(localProfileId: String): PendingCloudAuthentication? {
        return preferences.getObject<PendingCloudAuthenticationRecord>(
            PreferencesKey.PendingCloudAuthentication(localProfileId),
        )?.toDomain()
    }

    override suspend fun save(authentication: PendingCloudAuthentication) {
        preferences.putObject(
            PreferencesKey.PendingCloudAuthentication(authentication.localProfileId),
            PendingCloudAuthenticationRecord(
                localProfileId = authentication.localProfileId,
                cloudUserId = authentication.cloudUserId,
                email = authentication.email,
                createdAt = authentication.createdAt,
            ),
        )
    }

    override suspend fun clear(localProfileId: String) {
        preferences.remove(PreferencesKey.PendingCloudAuthentication(localProfileId))
    }
}

@Serializable
private data class PendingCloudAuthenticationRecord(
    val localProfileId: String,
    val cloudUserId: String? = null,
    val email: String? = null,
    val createdAt: Long,
)

private fun PendingCloudAuthenticationRecord.toDomain(): PendingCloudAuthentication {
    return PendingCloudAuthentication(
        localProfileId = localProfileId,
        cloudUserId = cloudUserId,
        email = email,
        createdAt = createdAt,
    )
}
