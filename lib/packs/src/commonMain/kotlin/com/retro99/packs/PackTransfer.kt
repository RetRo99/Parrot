package com.retro99.packs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A server that ignores Range starts over; a 206 must describe this exact partial. */
fun packResumeOffset(status: Int, range: String?, existing: Long, total: Long): Long = when (status) {
    200 -> 0
    206 -> {
        val bounds = range?.let { Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(it) }?.groupValues
        check(bounds != null && bounds[1].toLongOrNull() == existing &&
            bounds[2].toLongOrNull() == total - 1 && bounds[3].toLongOrNull() == total &&
            existing in 0 until total) { "Invalid resumed download. Try again." }
        existing
    }
    else -> error("Download failed ($status). Try again.")
}

/** Bounded, cancellable transfer shared by voice and dictionary downloaders. */
suspend fun copyPackBytes(
    total: Long,
    offset: Long,
    read: suspend (ByteArray) -> Int,
    write: (ByteArray, Int) -> Unit,
    onBytes: (Long) -> Unit,
) {
    val buffer = ByteArray(64 * 1024)
    var downloaded = offset
    onBytes(downloaded)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        downloaded += count
        check(downloaded <= total) { "Pack is larger than its manifest." }
        write(buffer, count)
        onBytes(downloaded)
    }
    check(downloaded == total) { "Incomplete download. Try again to resume." }
}
