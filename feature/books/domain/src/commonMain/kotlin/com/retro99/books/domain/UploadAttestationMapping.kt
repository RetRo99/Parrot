package com.retro99.books.domain

import com.retro99.cloudaccount.domain.model.UploadAttestationRecord

fun UploadAttestationRecord.toBookFileUploadAttestation(): UploadRightsAttestation =
    UploadRightsAttestation(
        attestedAt = attestedAt,
        tosVersion = tosVersion,
        attestationVersion = attestationVersion,
    )
