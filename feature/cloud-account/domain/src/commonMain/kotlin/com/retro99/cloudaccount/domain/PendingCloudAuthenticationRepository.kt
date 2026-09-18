package com.retro99.cloudaccount.domain

import com.retro99.cloudaccount.domain.model.PendingCloudAuthentication

interface PendingCloudAuthenticationRepository {
    suspend fun get(localProfileId: String): PendingCloudAuthentication?

    suspend fun save(authentication: PendingCloudAuthentication)

    suspend fun clear(localProfileId: String)
}
