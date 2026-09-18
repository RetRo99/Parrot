package com.retro99.database.api.reader

import com.retro99.database.api.sync.SyncOutboxEntry

data class ReaderSettingsMutation(
    val settings: ReaderSettingsEntity,
    val outboxEntry: SyncOutboxEntry,
)
