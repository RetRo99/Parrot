package com.retro99.cloudaccount.ui

import com.retro99.sync.domain.SyncPhase
import com.retro99.sync.domain.SyncStatus
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ParrotCloudPresentationTest {
    @Test
    fun `storage uses decimal units rounded to one decimal`() {
        assertEquals("0.0 KB", storageLabel(0))
        assertEquals("1.2 KB", storageLabel(1234))
        assertEquals("1.3 MB", storageLabel(1_250_000))
        assertEquals("1.2 GB", storageLabel(1_234_000_000))
        assertEquals("209.7 MB", storageLabel(209_715_200))
    }

    @Test
    fun `storage warning starts above ninety percent and ignores absent quota`() {
        assertFalse(isStorageAlmostFull(90, 100))
        assertTrue(isStorageAlmostFull(91, 100))
        assertTrue(isStorageAlmostFull(110, 100))
        assertFalse(isStorageAlmostFull(10, 0))
    }

    @Test
    fun `delete requires exact confirmation`() {
        assertTrue(canConfirmDeletion("DELETE"))
        listOf("", "delete", " DELETE", "DELETE ").forEach { assertFalse(canConfirmDeletion(it)) }
    }

    @Test
    fun `progress is real and clamped`() {
        assertNull(syncProgress(SyncStatus.Running(SyncPhase.PREPARING)))
        assertNull(syncProgress(SyncStatus.Running(SyncPhase.PULLING, totalItems = 0)))
        assertEquals(0.25f, syncProgress(SyncStatus.Running(SyncPhase.APPLYING, completedItems = 3, totalItems = 12)))
        assertEquals(1f, syncProgress(SyncStatus.Running(SyncPhase.UPLOADING_FILES, bytesTransferred = 20, totalBytes = 10)))
    }

    @Test
    fun `last sync handles missing invalid future and real timestamps`() {
        val now = Instant.parse("2026-10-03T12:00:00Z")
        assertNull(elapsedSyncMinutes(null, now))
        assertNull(elapsedSyncMinutes("bad timestamp", now))
        assertEquals(5L, elapsedSyncMinutes("2026-10-03T11:55:00Z", now))
        assertEquals(0L, elapsedSyncMinutes("2026-10-04T11:55:00Z", now))
        assertEquals("2026-10-02, 21:40", localSyncDate("2026-10-02T21:40:00Z", TimeZone.UTC))
        assertTrue(isYesterday("2026-10-02T21:40:00Z", now, TimeZone.UTC))
        assertFalse(isYesterday("2026-10-01T21:40:00Z", now, TimeZone.UTC))
    }

    @Test
    fun `transport errors become safe plain categories`() {
        assertEquals(SyncFailureKind.Network, syncFailureKind("Failed to connect to private.server"))
        assertEquals(SyncFailureKind.Timeout, syncFailureKind("request timed out"))
        assertEquals(SyncFailureKind.Quota, syncFailureKind("quota_exceeded"))
        assertEquals(SyncFailureKind.Authentication, syncFailureKind("JWT expired"))
        assertEquals(SyncFailureKind.Other, syncFailureKind("SQL private diagnostic"))
    }
}
