package com.retro99.cloudaccount.ui

import com.retro99.cloudaccount.domain.CloudStorageUsage
import com.retro99.translations.StringRes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import resources.translations.parrot_cloud_storage_books
import resources.translations.parrot_cloud_storage_prepared_audio

/**
 * The storage card's breakdown. The important case is the one that will be true
 * on every device until the owner applies the migration: the server sends no
 * breakdown, and the screen must look exactly as it did before this work.
 */
class CloudStorageBreakdownTest {

    @Test
    fun `a server that does not send the breakdown shows no breakdown`() {
        assertTrue(cloudStorageBreakdown(usage()).isEmpty())
    }

    @Test
    fun `an account with no prepared audio shows no breakdown`() {
        assertTrue(
            cloudStorageBreakdown(usage(booksBytes = 50_000_000, preparedAudioBytes = 0)).isEmpty(),
            "Prepared audio 0.0 KB is noise, not information",
        )
    }

    @Test
    fun `books and prepared audio are both named, with their sizes`() {
        val rows = cloudStorageBreakdown(
            usage(usedBytes = 60_000_000, booksBytes = 50_000_000, preparedAudioBytes = 10_000_000),
        )

        assertEquals(
            listOf(
                CloudStorageBreakdownRow(StringRes.parrot_cloud_storage_books, "50.0 MB"),
                CloudStorageBreakdownRow(StringRes.parrot_cloud_storage_prepared_audio, "10.0 MB"),
            ),
            rows,
        )
    }

    @Test
    fun `books falls back to the remainder when the server sends only the audio`() {
        val rows = cloudStorageBreakdown(
            usage(usedBytes = 60_000_000, booksBytes = null, preparedAudioBytes = 10_000_000),
        )

        assertEquals("50.0 MB", rows.first().size)
        assertEquals("10.0 MB", rows.last().size)
    }

    @Test
    fun `the two parts always add up to the total the user is charged for`() {
        val usage = usage(usedBytes = 60_000_000, booksBytes = 50_000_000, preparedAudioBytes = 10_000_000)

        assertEquals(usage.usedBytes, usage.booksBytes!! + usage.preparedAudioBytes!!)
    }

    private fun usage(
        usedBytes: Long = 50_000_000,
        booksBytes: Long? = null,
        preparedAudioBytes: Long? = null,
    ) = CloudStorageUsage(
        usedBytes = usedBytes,
        reservedBytes = 0,
        quotaBytes = 209_715_200,
        availableBytes = 209_715_200 - usedBytes,
        booksBytes = booksBytes,
        preparedAudioBytes = preparedAudioBytes,
    )
}
