package com.retro99.database.api.books

import com.retro99.database.api.sync.SyncOutboxEntry

data class BookmarkMutation(
    val bookmark: BookmarkEntity,
    val outboxEntry: SyncOutboxEntry,
)
