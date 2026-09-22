package com.retro99.database.implementation.dao.sync

import com.retro99.database.api.sync.SyncCheckpoint
import com.retro99.database.implementation.Sync_checkpoints
import com.retro99.database.implementation.SyncCheckpointQueries

internal fun SyncCheckpointQueries.upsert(checkpoint: SyncCheckpoint) {
    upsertCheckpoint(
        destination_id = checkpoint.destinationId,
        remote_account_id = checkpoint.remoteAccountId,
        cursor = checkpoint.cursor,
        updated_at = checkpoint.updatedAt,
    )
}

internal fun Sync_checkpoints.toCheckpoint(): SyncCheckpoint {
    return SyncCheckpoint(
        destinationId = destination_id,
        remoteAccountId = remote_account_id,
        cursor = cursor,
        updatedAt = updated_at,
    )
}
