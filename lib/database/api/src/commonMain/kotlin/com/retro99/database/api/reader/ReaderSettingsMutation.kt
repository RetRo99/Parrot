package com.retro99.database.api.reader

import com.retro99.database.api.sync.SyncOutboxEntry

data class ReaderSettingsMutation(
    val settings: ReaderSettingsEntity,
    /** Null for device-specific preferences that are stored locally but never cloud-synced. */
    val outboxEntry: SyncOutboxEntry?,
)
