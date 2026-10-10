package com.retro99.reader.domain.tts

/**
 * Prepared chapter audio on this device, as the Settings screen sees it. The store itself is
 * Android-only; iPhone gets an implementation that has nothing and deletes nothing.
 */
interface PreparedAudioStorage {

    suspend fun totalBytes(): Long

    suspend fun deleteAll()

    /** Used where no platform implementation is wired in, such as an older unit test. */
    companion object None : PreparedAudioStorage {
        override suspend fun totalBytes(): Long = 0L
        override suspend fun deleteAll() = Unit
    }
}

/** Bytes as the app says them: MB with one decimal once there is one, kB below that. */
fun preparedAudioSizeLabel(bytes: Long): String = if (bytes >= 1_000_000) {
    val tenths = (bytes + 50_000) / 100_000
    "${tenths / 10}.${tenths % 10} MB"
} else {
    "${((bytes + 500) / 1_000).coerceAtLeast(1L)} kB"
}
