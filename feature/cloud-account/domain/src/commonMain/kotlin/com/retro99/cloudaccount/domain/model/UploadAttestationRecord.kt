package com.retro99.cloudaccount.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UploadAttestationRecord(
    @SerialName("attested_at")
    val attestedAt: String,
    @SerialName("tos_version")
    val tosVersion: String,
    @SerialName("attestation_version")
    val attestationVersion: String,
)
