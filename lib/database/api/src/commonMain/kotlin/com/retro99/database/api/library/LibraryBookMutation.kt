package com.retro99.database.api.library

import com.retro99.database.api.sync.SyncOutboxEntry

data class LibraryBookMutation(
    val libraryBook: LibraryBookEntity,
    val localBookFile: LocalBookFileEntity,
    val outboxEntry: SyncOutboxEntry,
)
