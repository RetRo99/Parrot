package com.retro99.cloudaccount.domain.model

data class PendingCloudAuthentication(
    val localProfileId: String,
    val cloudUserId: String,
    val createdAt: Long,
)
