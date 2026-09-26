package com.retro99.parrot.navigation

/** Guards Home exposure callbacks against stale destinations and repeated composition callbacks. */
internal class HomeExposureGate {
    private var reportedEntryId: Long? = null

    fun shouldReport(entryId: Long, visibleEntryId: Long): Boolean {
        if (entryId != visibleEntryId || reportedEntryId == entryId) return false
        reportedEntryId = entryId
        return true
    }
}
