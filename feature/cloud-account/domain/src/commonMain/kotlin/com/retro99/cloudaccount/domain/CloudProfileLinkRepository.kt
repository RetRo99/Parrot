package com.retro99.cloudaccount.domain

import com.retro99.cloudaccount.domain.model.CloudProfileLink
import com.retro99.cloudaccount.domain.model.CloudProfileLinkResult

interface CloudProfileLinkRepository {
    suspend fun getForLocalProfile(localProfileId: String): CloudProfileLink?

    suspend fun getForCloudAccount(cloudUserId: String): CloudProfileLink?

    suspend fun link(localProfileId: String, cloudUserId: String): CloudProfileLinkResult

    suspend fun setSyncEnabled(localProfileId: String, enabled: Boolean)

    suspend fun markInitialMergeCompleted(localProfileId: String)
}
