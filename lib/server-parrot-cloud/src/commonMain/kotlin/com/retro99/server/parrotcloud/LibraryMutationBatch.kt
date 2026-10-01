package com.retro99.server.parrotcloud

import com.retro99.database.api.sync.SyncOutboxEntry

/** Resolve duplicate library identities before sending snapshots that refer to them. */
internal fun List<SyncOutboxEntry>.libraryMutationBatch(): List<SyncOutboxEntry> =
    filter { entry -> entry.entityType == SyncOutboxEntry.ENTITY_TYPE_LIBRARY_BOOK }
        .ifEmpty { this }
