package com.retro99.saved.domain

import org.koin.core.annotation.Single
import kotlin.concurrent.Volatile

/**
 * A saved item to open once its book's reader is ready: set when the reader is opened
 * from the Notes & highlights screen, taken by the reader when it starts.
 */
@Single
class PendingSavedJump {
    @Volatile
    private var pending: Pair<String, String>? = null

    fun set(bookUuid: String, itemId: String) {
        pending = bookUuid to itemId
    }

    /** The item to open for [bookUuid], once; null when there is none. */
    fun take(bookUuid: String): String? {
        val current = pending ?: return null
        if (current.first != bookUuid) return null
        pending = null
        return current.second
    }
}
