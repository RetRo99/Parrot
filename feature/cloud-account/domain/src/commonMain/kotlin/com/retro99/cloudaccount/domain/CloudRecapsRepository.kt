package com.retro99.cloudaccount.domain

/**
 * Recap data kept on this device for a cloud account: cached recaps, queued
 * read text and the stored consent decision.
 */
interface CloudRecapsRepository {
    /**
     * Removes [cloudUserId]'s recap data from [localProfileId]'s storage and
     * stores the withdrawal first, so a cache write racing this call cannot
     * put anything back once the account is gone.
     */
    suspend fun purgeAccountData(cloudUserId: String, localProfileId: String)
}
