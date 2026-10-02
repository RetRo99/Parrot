package com.retro99.reader.domain.recap

/** When read text counts as read. Shared by both platforms' readers. */
object RecapCapturePolicy {
    /** A page counts as read once it has been visible this long. */
    const val PAGE_DWELL_MS = 5_000L

    /** Longest idle stretch that still counts as active reading. */
    const val IDLE_CAP_MS = 3 * 60_000L

    /** Visible text taken from one page at most. */
    const val MAX_PAGE_CHARS = 6_000
}

/**
 * Tracks how long the current page has been visible. A page is due once it
 * has counted [dwellMs]; time while not counting (backgrounded, read aloud
 * by the device) doesn't count. Each page is due at most once until a
 * different page is shown. Not thread-safe; callers serialise.
 */
class RecapPageDwell<P : Any>(
    private val dwellMs: Long = RecapCapturePolicy.PAGE_DWELL_MS,
) {
    private var page: P? = null
    private var visibleMs = 0L
    private var countingSinceMs: Long? = null
    private var counting = true
    private var taken = false

    val currentPage: P? get() = page

    /** A repeat of the current page keeps its time; a new page starts at zero. */
    fun show(next: P, nowMs: Long) {
        if (next == page) return
        page = next
        visibleMs = 0L
        taken = false
        countingSinceMs = if (counting) nowMs else null
    }

    fun setCounting(enabled: Boolean, nowMs: Long) {
        if (enabled == counting) return
        if (enabled) {
            countingSinceMs = nowMs
        } else {
            visibleMs += elapsed(nowMs)
            countingSinceMs = null
        }
        counting = enabled
    }

    /** The current page starts its dwell over (its text wasn't being read). */
    fun restart(nowMs: Long) {
        visibleMs = 0L
        countingSinceMs = if (counting) nowMs else null
    }

    /** Time until the page is due; null when nothing is pending. */
    fun remainingMs(nowMs: Long): Long? {
        if (page == null || taken || !counting) return null
        return (dwellMs - visibleMs - elapsed(nowMs)).coerceAtLeast(0L)
    }

    /** The page, once, when it has been visible long enough. */
    fun takeDue(nowMs: Long): P? {
        if (remainingMs(nowMs) != 0L) return null
        taken = true
        return page
    }

    private fun elapsed(nowMs: Long): Long =
        countingSinceMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
}

/**
 * Active reading time: counts only while active (in the foreground or
 * listening), and stops counting [idleCapMs] after the last activity so a
 * book left open on the table doesn't count. Not thread-safe.
 */
class RecapActiveReadingClock(
    private val idleCapMs: Long = RecapCapturePolicy.IDLE_CAP_MS,
) {
    private var active = false
    private var markMs = 0L
    private var lastActivityMs = 0L
    private var totalMs = 0L

    fun setActive(enabled: Boolean, nowMs: Long) {
        if (enabled == active) return
        advance(nowMs)
        active = enabled
        if (enabled) {
            markMs = nowMs
            lastActivityMs = nowMs
        }
    }

    /** A page turn or a heard sentence: the reader is still there. */
    fun onActivity(nowMs: Long) {
        advance(nowMs)
        lastActivityMs = nowMs
    }

    fun totalMs(nowMs: Long): Long {
        advance(nowMs)
        return totalMs
    }

    private fun advance(nowMs: Long) {
        if (!active) return
        val end = minOf(nowMs, lastActivityMs + idleCapMs)
        if (end > markMs) totalMs += end - markMs
        markMs = maxOf(markMs, nowMs)
    }
}
