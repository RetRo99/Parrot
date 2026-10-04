package com.retro99.reader.data.recap

import com.retro99.base.server.PARROT_CLOUD_SERVER_ID
import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.reader.domain.recap.RecapSettings
import kotlin.time.Clock

/** Called by the existing runner's lifecycle triggers, never by display reads. */
class RecapCloudSync(
    private val database: SessionRecapDatabase,
    private val cloud: CloudRecapEngine,
    private val auth: RecapAuthTokens,
    private val settings: RecapSettings,
    private val identity: RecapIdentity,
    private val profileId: () -> String?,
    private val clock: Clock = Clock.System,
) {
    private var bookOffset = 0
    private var recoveryOffset = 0
    suspend fun sync(): Boolean {
        val account = auth.accountId() ?: return false
        val profile = profileId()
        fun current() = auth.accountId() == account && profileId() == profile
        if (database.hasCloudWithdrawal(account)) {
            cloud.consent(account, false)
            if (!current()) return false
            database.acknowledgeCloudWithdrawal(account)
        }
        for (session in database.getCloudDeletions(account).take(20)) {
            cloud.delete(account, session)
            if (!current()) return false
            database.acknowledgeCloudDeletion(account, session)
        }
        if (!settings.isCloudRecapsEnabled() || !current()) return false
        // Empty session IDs cannot exist; this fetch checks account-wide
        // consent even on a device with no linked books or pending rows.
        if (!cloud.fetch(account, sessionId = "").consentEnabled) {
            if (current()) settings.setCloudRecapsEnabled(false)
            return false
        }
        // Recover originating jobs, including unlinked books and lost responses.
        val recovery = database.getCloudRows(account).filter {
            it.consentVersion == 2 && (it.status in setOf("PENDING", "FAILED_RETRYABLE", "RUNNING", "CLOUD_QUEUED", "CLOUD_RUNNING") ||
                // Local failures may have reached the server, even if their
                // text has expired. A fetched server failure is already resolved.
                (it.status == "FAILED_PERMANENT" && it.cloudChangeId == 0L))
        }
        var pending = recovery.size > 20
        val recoveryStart = if (recovery.isEmpty()) 0 else recoveryOffset % recovery.size
        // Empty lookups must not let unresolved local failures monopolize recovery.
        for (index in 0 until minOf(20, recovery.size)) {
            val row = recovery[(recoveryStart + index) % recovery.size]
            recoveryOffset = (recoveryStart + index + 1) % recovery.size
            val page = cloud.fetch(account, sessionId = row.sessionId)
            if (!current() || !settings.isCloudRecapsEnabled()) return false
            if (!page.consentEnabled) { settings.setCloudRecapsEnabled(false); return false }
            for (record in page.items) pending = apply(record, account, row.bookUuid) || pending
        }
        // Canonical cloud-linked books: independent cursor per book, using the
        // server's existing commit-ordered sync change IDs. Unlinked results are
        // never attached to a book on a different device.
        val books = identity.cloudBooks()
        if (books.isEmpty()) return pending
        val start = bookOffset % books.size
        // Round-robin with one page per book per pass: a busy first book
        // cannot starve another device's results in later books.
        for (index in 0 until minOf(10, books.size)) {
            val bookId = books[(start + index) % books.size]
            bookOffset = (start + index + 1) % books.size
            var cursor = database.getCloudCursor(account, bookId)
                val page = cloud.fetch(account, cloudBookId = bookId, cursor = cursor)
                if (!current() || !settings.isCloudRecapsEnabled()) return false
                if (!page.consentEnabled) { settings.setCloudRecapsEnabled(false); return false }
                require(page.nextCursor >= cursor && (!page.hasMore || page.nextCursor > cursor))
                for (record in page.items) {
                    val existing = database.getRecap(record.sessionId)
                    if (existing != null && existing.cloudAccountId != account) continue
                    pending = apply(record, account, existing?.bookUuid ?: bookId) || pending
                }
                if (!current()) return false
                cursor = page.nextCursor
                // Applying first then advancing is crash-safe: replay is harmless.
                database.setCloudCursor(account, bookId, cursor)
                pending = page.hasMore || pending
        }
        return pending || books.size > 10
    }

    private suspend fun apply(record: CloudRecapRecord, account: String, localBook: String): Boolean {
        val textExpired = record.state == "deleted" && record.errorCode == "EXCERPT_EXPIRED" && record.expiresAt > clock.now().toEpochMilliseconds()
        if (record.state == "deleted" && !textExpired || record.expiresAt <= clock.now().toEpochMilliseconds()) {
            database.getRecap(record.sessionId)?.takeIf { it.cloudAccountId == account }
                ?.let { database.deleteRecap(record.sessionId) }
            return false
        }
        // A local offline delete wins until its server acknowledgement arrives.
        if (record.sessionId in database.getCloudDeletions(account)) return false
        val status = when (record.state) {
            "queued" -> "CLOUD_QUEUED"
            "running" -> "CLOUD_RUNNING"
            "completed" -> "SUCCEEDED"
            "not_enough" -> "NOT_ENOUGH"
            "failed" -> "FAILED_PERMANENT"
            "deleted" -> if (textExpired) "FAILED_PERMANENT" else return false
            else -> return false
        }
        val now = clock.now().toEpochMilliseconds()
        database.cacheCloudRecap(SessionRecapEntity(
            sessionId = record.sessionId, serverId = PARROT_CLOUD_SERVER_ID, bookUuid = localBook,
            status = status, endHref = record.position?.href,
            endProgression = record.position?.progression, endTotalProgression = record.position?.totalProgression,
            language = record.language, summary = record.summary, model = record.model,
            engineId = "cloud", createdAt = record.endedAt ?: now, updatedAt = now, endedAt = record.endedAt,
            generatedAt = if (status == "SUCCEEDED") now else null, cloudAccountId = account,
            cloudBookId = record.cloudBookId, cloudChangeId = record.changeId, cloudExpiresAt = record.expiresAt,
            lastError = record.errorCode, cloudIdentityBound = true,
        ))
        return record.state in setOf("queued", "running")
    }
}
