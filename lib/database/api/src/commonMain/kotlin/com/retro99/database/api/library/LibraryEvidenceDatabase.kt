package com.retro99.database.api.library

import com.retro99.server.api.library.LibraryProfileId
import com.retro99.server.api.library.SourceBookKey
import com.retro99.server.api.library.SourceIdentityEvidence
import com.retro99.server.api.library.SourceResourceRef

enum class LibraryEvidenceProvenanceKind { Adapter, Transfer, Migration }

data class LibraryEvidenceProvenance(
    val kind: LibraryEvidenceProvenanceKind,
    val referenceId: String,
) {
    init { require(referenceId.isNotBlank()) }
}

data class LibraryEvidenceRecord(
    val evidenceId: String,
    val evidence: SourceIdentityEvidence,
    val provenance: LibraryEvidenceProvenance,
    val observedAt: String,
) {
    init {
        require(evidenceId.isNotBlank())
        require(observedAt.isNotBlank())
    }
}

interface LibraryEvidenceDatabase {
    suspend fun recordResource(resource: SourceResourceRef)

    suspend fun getResources(book: SourceBookKey): List<SourceResourceRef>

    /** An evidence ID is immutable; replay returns false and conflicting reuse fails. */
    suspend fun recordEvidence(record: LibraryEvidenceRecord): Boolean

    suspend fun getActiveEvidence(profileId: LibraryProfileId): List<LibraryEvidenceRecord>

    /** Retired evidence remains auditable but cannot drive automatic joins. */
    suspend fun retireEvidence(profileId: LibraryProfileId, evidenceId: String)
}
