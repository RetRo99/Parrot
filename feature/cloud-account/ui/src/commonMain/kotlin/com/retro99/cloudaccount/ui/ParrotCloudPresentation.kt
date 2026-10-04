package com.retro99.cloudaccount.ui

import com.retro99.sync.domain.SyncStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Instant

internal fun storageLabel(bytes: Long): String {
    val value = bytes.coerceAtLeast(0).toDouble()
    val (divisor, unit) = when {
        value >= 1_000_000_000 -> 1_000_000_000.0 to "GB"
        value >= 1_000_000 -> 1_000_000.0 to "MB"
        else -> 1_000.0 to "KB"
    }
    val tenths = (value / divisor * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10} $unit"
}

internal fun isStorageAlmostFull(used: Long, quota: Long): Boolean = quota > 0 && used.toDouble() / quota > 0.9
internal fun canConfirmDeletion(text: String): Boolean = text == "DELETE"

internal fun syncProgress(status: SyncStatus.Running): Float? {
    val bytes = status.totalBytes
    val items = status.totalItems
    return when {
        bytes != null && bytes > 0 -> status.bytesTransferred.toDouble().div(bytes).toFloat()
        items != null && items > 0 -> status.completedItems.toFloat() / items
        else -> null
    }?.coerceIn(0f, 1f)
}

internal fun elapsedSyncMinutes(timestamp: String?, now: Instant): Long? = timestamp?.let {
    runCatching { (now - Instant.parse(it)).inWholeMinutes.coerceAtLeast(0) }.getOrNull()
}

internal fun localSyncDate(timestamp: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = Instant.parse(timestamp).toLocalDateTime(zone)
    val time = "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
    return "${local.date}, $time"
}

internal fun isYesterday(timestamp: String, now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): Boolean =
    Instant.parse(timestamp).toLocalDateTime(zone).date == now.toLocalDateTime(zone).date.minus(1, DateTimeUnit.DAY)

internal fun localSyncTime(timestamp: String, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = Instant.parse(timestamp).toLocalDateTime(zone)
    return "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

internal enum class SyncFailureKind { Network, Timeout, Quota, Authentication, Other }
internal fun syncFailureKind(raw: String): SyncFailureKind {
    val error = raw.lowercase()
    return when {
        "timeout" in error || "timed out" in error -> SyncFailureKind.Timeout
        listOf("offline", "failed to connect", "unknownhost", "unable to resolve", "network is unreachable").any { it in error } -> SyncFailureKind.Network
        "quota" in error || "storage full" in error -> SyncFailureKind.Quota
        "unauthorized" in error || "not authenticated" in error || "jwt" in error -> SyncFailureKind.Authentication
        else -> SyncFailureKind.Other
    }
}
