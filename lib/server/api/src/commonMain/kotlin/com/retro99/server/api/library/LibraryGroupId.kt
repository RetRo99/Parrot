package com.retro99.server.api.library

/** Displayed-library identity, independent of source, content, replica, and transfer IDs. */
data class LibraryGroupId(val value: String) {
    init { require(value.isNotBlank()) }
}

enum class LibraryMembershipOrigin { Backfill, Automatic, Manual }
