package com.retro99.cloudaccount.data

import com.retro99.cloudaccount.domain.CloudRecapsRepository
import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [CloudRecapsRepository::class])
class CloudRecapsDataRepository(
    @Provided private val database: SessionRecapDatabase,
    @Provided private val preferences: Preferences,
) : CloudRecapsRepository {
    override suspend fun purgeAccountData(cloudUserId: String, localProfileId: String) {
        // One transaction stores the withdrawal (fencing later cache writes),
        // drops the read text and deletes the account's recaps and cursors.
        database.queueCloudWithdrawal(cloudUserId, Clock.System.now().toEpochMilliseconds())
        // The same scoped keys PreferencesRecapSettings keeps its consent in;
        // with them gone, a later account cannot inherit this consent.
        preferences.remove(PreferencesKey.UserScoped(localProfileId, PreferencesKey.CloudStoredRecapsEnabled.name))
        preferences.remove(PreferencesKey.UserScoped(localProfileId, PreferencesKey.CloudRecapConsentAccount.name))
    }
}
