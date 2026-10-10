package com.retro99.cloudaccount.ui

import com.retro99.cloudaccount.domain.CloudStorageUsage
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.StringResource
import resources.translations.parrot_cloud_storage_books
import resources.translations.parrot_cloud_storage_prepared_audio

/** One part of what the allowance is being used by. */
internal data class CloudStorageBreakdownRow(
    val label: StringResource,
    val size: String,
)

/**
 * What the allowance is used by, under the total.
 *
 * Empty when the server has not told us -- a server without the prepared-audio
 * migration applied does not send the two fields -- so the screen then shows
 * the total alone, exactly as it did before this work. Empty too when the
 * account holds nothing but books, because "Prepared audio 0.0 KB" is noise.
 *
 * Pure, so every case is a test rather than something to find on a phone.
 */
internal fun cloudStorageBreakdown(usage: CloudStorageUsage): List<CloudStorageBreakdownRow> {
    val prepared = usage.preparedAudioBytes ?: return emptyList()
    if (prepared <= 0) return emptyList()
    // Prefer the server's own figure; fall back to the remainder, which is
    // what the server derives it from anyway.
    val books = usage.booksBytes ?: (usage.usedBytes - prepared)
    return listOf(
        CloudStorageBreakdownRow(StringRes.parrot_cloud_storage_books, storageLabel(books)),
        CloudStorageBreakdownRow(StringRes.parrot_cloud_storage_prepared_audio, storageLabel(prepared)),
    )
}
