package com.retro99.database.api.links

import com.retro99.database.api.sync.SyncOutboxEntry

/**
 * Everything one link change stores, applied in a single transaction: links (tombstones
 * first, so their members are free to join another link), decisions and the sync outbox
 * entries that carry the change to Parrot Cloud.
 */
data class BookLinkWrite(
    val links: List<BookLinkEntity> = emptyList(),
    val decisions: List<BookLinkDecisionEntity> = emptyList(),
    val outboxEntries: List<SyncOutboxEntry> = emptyList(),
    /** Local edits must not overwrite membership changed by sync after validation. */
    val expectedMemberships: Map<String, Set<String>>? = null,
)
