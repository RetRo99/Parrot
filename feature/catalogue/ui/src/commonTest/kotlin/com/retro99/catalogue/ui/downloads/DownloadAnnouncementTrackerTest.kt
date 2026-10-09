package com.retro99.catalogue.ui.downloads

import com.retro99.catalogue.domain.*
import kotlin.test.*

class DownloadAnnouncementTrackerTest {
    @Test fun progressOnlyAt25PercentStepsWhileVisibleNeverZeroOr100() {
        val tracker = DownloadAnnouncementTracker()
        tracker.update(emptyList(), emptySet(), false)
        val row = downloadFixture(AcquisitionState.Downloading).copy(expectedSizeBytes = 100, bytesSoFar = 0)
        fun progress(bytes: Long, visible: Boolean = true) = tracker.update(listOf(row.copy(bytesSoFar = bytes)), if (visible) setOf("source\u0000book") else emptySet(), false)
        assertTrue(progress(0).isEmpty()); assertTrue(progress(24).isEmpty())
        assertEquals(listOf(DownloadAnnouncement.Progress("Title", 25)), progress(25))
        assertTrue(progress(42).isEmpty())
        assertTrue(progress(50, false).isEmpty())
        assertTrue(progress(60).isEmpty())
        assertEquals(listOf(DownloadAnnouncement.Progress("Title", 75)), progress(75))
        assertTrue(progress(100).isEmpty())
    }
    @Test fun waitingUnknownDoneAndFailureAreAnnouncedOnceWithTerminalStatesAnywhere() {
        val tracker = DownloadAnnouncementTracker(); tracker.update(emptyList(), emptySet(), false)
        val waiting = downloadFixture(AcquisitionState.Waiting)
        assertEquals(listOf(DownloadAnnouncement.Waiting("Title")), tracker.update(listOf(waiting), emptySet(), true))
        assertTrue(tracker.update(listOf(waiting), emptySet(), true).isEmpty())
        val unknown = waiting.copy(state = AcquisitionState.Downloading, expectedSizeBytes = null)
        assertEquals(listOf(DownloadAnnouncement.Downloading("Title")), tracker.update(listOf(unknown), emptySet(), true))
        assertTrue(tracker.update(listOf(unknown.copy(bytesSoFar = 3_000_000)), emptySet(), true).isEmpty())
        val done = unknown.copy(state = AcquisitionState.Done)
        assertEquals(listOf(DownloadAnnouncement.Done("Title")), tracker.update(listOf(done), emptySet(), false))
        assertTrue(tracker.update(listOf(done), emptySet(), false).isEmpty())
        val failed = downloadFixture(AcquisitionState.Failed(AcquisitionFailureReason.Connection), "other")
        assertEquals(listOf(DownloadAnnouncement.Failed(failed)), tracker.update(listOf(done, failed), emptySet(), false))
    }
    @Test fun historicalTerminalRowsDoNotAnnounceOnStartupAndDetailIdentityIsVisible() {
        val tracker = DownloadAnnouncementTracker()
        assertTrue(tracker.update(listOf(downloadFixture(AcquisitionState.Done)), emptySet(), false).isEmpty())
        val row = downloadFixture(AcquisitionState.Downloading, "second").copy(detailIdentity = "listing", expectedSizeBytes = null)
        assertEquals(listOf(DownloadAnnouncement.Downloading("Title")), tracker.update(listOf(row), setOf("source\u0000listing"), false))
        tracker.reset()
        assertTrue(tracker.update(listOf(downloadFixture(AcquisitionState.Done)), emptySet(), false).isEmpty())
    }
}
