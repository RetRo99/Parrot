package com.retro99.server.storyteller.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StorytellerServerDetailsResponse(
    @SerialName("id")
    val id: String? = null,
)
