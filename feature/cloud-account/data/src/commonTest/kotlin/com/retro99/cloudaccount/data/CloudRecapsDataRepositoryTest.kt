package com.retro99.cloudaccount.data

import com.retro99.database.api.recap.SessionRecapCapture
import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudRecapsDataRepositoryTest {
    private val database = RecordingSessionRecapDatabase()
    private val preferences = RecordingPreferences()
    private val repository = CloudRecapsDataRepository(database, preferences)

    @Test
    fun `purge stores the withdrawal for the account`() = runTest {
        repository.purgeAccountData("cloud-account", "profile-a")

        assertEquals(listOf("cloud-account"), database.withdrawals)
    }

    @Test
    fun `purge removes the consent keys PreferencesRecapSettings stores them under`() = runTest {
        repository.purgeAccountData("cloud-account", "profile-a")

        // The literal names pin the UserScoped scoping contract shared with
        // PreferencesRecapSettings; a mismatch would leave consent behind.
        assertEquals(
            listOf(
                "user_profile-a_CloudStoredRecapsEnabledV2",
                "user_profile-a_CloudRecapConsentAccountV2",
            ),
            preferences.removed,
        )
    }
}

private class RecordingPreferences : Preferences {
    val removed = mutableListOf<String>()
    private val values = mutableMapOf<String, Any>()

    override fun getStringOrNull(key: PreferencesKey): String? = values[key.name] as? String

    override fun putString(key: PreferencesKey, value: String) {
        values[key.name] = value
    }

    override fun observeStringOrNull(key: PreferencesKey): Flow<String?> =
        flowOf(getStringOrNull(key))

    override fun getBoolean(key: PreferencesKey, defaultValue: Boolean): Boolean =
        values[key.name] as? Boolean ?: defaultValue

    override fun putBoolean(key: PreferencesKey, value: Boolean) {
        values[key.name] = value
    }

    override fun observeBoolean(key: PreferencesKey, defaultValue: Boolean): Flow<Boolean> =
        flowOf(getBoolean(key, defaultValue))

    override fun getLong(key: PreferencesKey, defaultValue: Long): Long =
        values[key.name] as? Long ?: defaultValue

    override fun putLong(key: PreferencesKey, value: Long) {
        values[key.name] = value
    }

    override fun remove(key: PreferencesKey) {
        removed += key.name
        values.remove(key.name)
    }
}

private class RecordingSessionRecapDatabase : SessionRecapDatabase {
    val withdrawals = mutableListOf<String>()

    override suspend fun queueCloudWithdrawal(accountId: String, now: Long) {
        withdrawals += accountId
    }

    private fun unused(): Nothing = error("Not used")

    override suspend fun insertCapturing(entity: SessionRecapEntity): Boolean = unused()
    override suspend fun updateCapture(sessionId: String, capture: SessionRecapCapture, now: Long): Boolean = unused()
    override suspend fun finishCapture(
        sessionId: String,
        status: String,
        capture: SessionRecapCapture,
        excerptHash: String?,
        lastSentence: String?,
        activeReadingMs: Long,
        lastError: String?,
        endedAt: Long,
    ): Boolean = unused()

    override suspend fun getRecap(sessionId: String): SessionRecapEntity? = unused()
    override fun observeRecap(sessionId: String): Flow<SessionRecapEntity?> = unused()
    override fun observeLatestForBook(bookUuid: String): Flow<SessionRecapEntity?> = unused()
    override fun observeForBook(bookUuid: String): Flow<List<SessionRecapEntity>> = unused()
    override suspend fun getCapturing(): List<SessionRecapEntity> = unused()
    override suspend fun getPreviousEnded(
        bookUuid: String,
        sessionId: String,
        createdAt: Long,
    ): SessionRecapEntity? = unused()

    override suspend fun getNextDue(now: Long): SessionRecapEntity? = unused()
    override suspend fun getEarliestScheduled(now: Long): Long? = unused()
    override suspend fun getOldestRunningUpdate(): Long? = unused()
    override suspend fun claim(sessionId: String, engineId: String, now: Long): Boolean = unused()
    override suspend fun complete(
        sessionId: String,
        status: String,
        summary: String?,
        model: String?,
        now: Long,
    ): Boolean = unused()

    override suspend fun fail(
        sessionId: String,
        status: String,
        attemptCount: Int,
        nextAttemptAt: Long?,
        lastError: String?,
        now: Long,
        dropText: Boolean,
    ): Boolean = unused()

    override suspend fun recoverStaleRunning(staleBefore: Long, now: Long, maxAttempts: Int): Long = unused()
    override suspend fun requeue(sessionId: String, now: Long): Boolean = unused()
    override suspend fun withdrawText(now: Long) = unused()
    override suspend fun applyRetention(excerptCutoff: Long, rowCutoff: Long, now: Long): Long = unused()
    override suspend fun deleteRecap(sessionId: String) = unused()
    override suspend fun deleteAllRecaps() = unused()
    override suspend fun getCloudRows(accountId: String): List<SessionRecapEntity> = unused()
    override suspend fun cacheCloudRecap(entity: SessionRecapEntity) = unused()
    override suspend fun markCloudQueued(
        sessionId: String,
        accountId: String,
        cloudBookId: String?,
        running: Boolean,
        now: Long,
    ): Boolean = unused()

    override suspend fun queueCloudDeletion(accountId: String, sessionId: String) = unused()
    override suspend fun getCloudDeletions(accountId: String): List<String> = unused()
    override suspend fun acknowledgeCloudDeletion(accountId: String, sessionId: String) = unused()
    override suspend fun hasCloudWithdrawal(accountId: String): Boolean = unused()
    override suspend fun acknowledgeCloudWithdrawal(accountId: String) = unused()
    override suspend fun enableCloudConsent(accountId: String) = unused()
    override suspend fun getCloudCursor(accountId: String, bookId: String): Long = unused()
    override suspend fun setCloudCursor(accountId: String, bookId: String, cursor: Long) = unused()
    override suspend fun bindCloudIdentity(sessionId: String, cloudBookId: String?): Boolean = unused()
}
