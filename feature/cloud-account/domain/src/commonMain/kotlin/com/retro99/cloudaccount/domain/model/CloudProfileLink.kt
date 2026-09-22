package com.retro99.cloudaccount.domain.model

data class CloudProfileLink(
    val localProfileId: String,
    val cloudUserId: String,
    val syncEnabled: Boolean,
)
