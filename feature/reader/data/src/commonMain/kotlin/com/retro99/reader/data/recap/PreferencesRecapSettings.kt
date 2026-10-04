package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.reader.domain.recap.RecapPresentation
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Cloud recap consent in the active profile's preferences. Unset means off. */
@Single(binds = [RecapSettings::class])
class PreferencesRecapSettings(
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val observeUserPreferenceUseCase: ObserveUserPreferenceUseCase,
    @Provided private val database: SessionRecapDatabase,
    @Provided private val auth: RecapAuthTokens,
    @Provided private val cloud: CloudRecapEngine,
) : RecapSettings {
    private val consentMutex = Mutex()
    override fun observeSignedIn(): Flow<Boolean> = auth.observeSignedIn()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeFeatureAvailable(): Flow<Boolean> = combine(auth.observeAccountId(),
        observeUserPreferenceUseCase<String>(PreferencesKey.CloudRecapConsentAccount)) { account, owner -> account to owner }
        .distinctUntilChanged()
        .transformLatest { (account, owner) ->
        emit(false)
        if (account == null) {
            // Only a previously verified consenting account may see sign-in advice.
            emit(owner != null && owner == getUserPreferenceUseCase<String>(PreferencesKey.RecapAllowedAccount))
        } else {
            try {
                val allowed = cloud.featureAvailable(account)
                if (auth.accountId() != account) return@transformLatest
                saveUserPreferenceUseCase(PreferencesKey.RecapAllowedAccount, if (allowed) account else "")
                emit(allowed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emit(auth.accountId() == account && getUserPreferenceUseCase<String>(PreferencesKey.RecapAllowedAccount) == account)
            }
        }
    }.distinctUntilChanged()

    override fun observePresentation(): Flow<RecapPresentation> =
        observeUserPreferenceUseCase<String>(PreferencesKey.RecapPresentation).map { stored ->
            RecapPresentation.entries.firstOrNull { it.name == stored } ?: RecapPresentation.AFTER_BREAK
        }.distinctUntilChanged()

    override suspend fun setPresentation(value: RecapPresentation) {
        saveUserPreferenceUseCase(PreferencesKey.RecapPresentation, value.name)
    }

    override fun observeCloudRecapsEnabled(): Flow<Boolean> =
        combine(
            observeUserPreferenceUseCase<Boolean>(PreferencesKey.CloudStoredRecapsEnabled),
            observeUserPreferenceUseCase<String>(PreferencesKey.CloudRecapConsentAccount),
            auth.observeAccountId(),
        ) { enabled, owner, account -> enabled == true && account != null && owner == account }
            .distinctUntilChanged()

    override fun observeConsentGiven(): Flow<Boolean> =
        combine(
            observeUserPreferenceUseCase<Boolean>(PreferencesKey.CloudStoredRecapsEnabled),
            observeUserPreferenceUseCase<String>(PreferencesKey.CloudRecapConsentAccount),
            auth.observeAccountId(),
        ) { enabled, owner, account ->
            // Consent outlives the session: only another account's consent
            // does not count.
            enabled == true && (owner == null || account == null || owner == account)
        }
            .distinctUntilChanged()

    override suspend fun isCloudRecapsEnabled(): Boolean =
        auth.accountId()?.let { account ->
            getUserPreferenceUseCase<Boolean>(PreferencesKey.CloudStoredRecapsEnabled) == true &&
                getUserPreferenceUseCase<String>(PreferencesKey.CloudRecapConsentAccount) == account &&
                !database.hasCloudWithdrawal(account)
        } ?: false

    override suspend fun isConsentGiven(accountId: String?): Boolean {
        if (getUserPreferenceUseCase<Boolean>(PreferencesKey.CloudStoredRecapsEnabled) != true) return false
        val owner = getUserPreferenceUseCase<String>(PreferencesKey.CloudRecapConsentAccount) ?: return false
        if (accountId != null && owner != accountId) return false
        // An unavailable/restoring session is not a withdrawal. A different
        // authenticated account still cannot use the old account's consent.
        if (auth.accountId()?.let { it != owner } == true) return false
        return !database.hasCloudWithdrawal(owner)
    }

    override suspend fun setCloudRecapsEnabled(enabled: Boolean) = consentMutex.withLock {
        // Keep the server command and its local commit in user-action order:
        // a slow enable must finish before a later withdrawal is applied.
        val account = auth.accountId()
        if (enabled) {
            requireNotNull(account)
            if (database.hasCloudWithdrawal(account)) {
                cloud.consent(account, false)
                database.acknowledgeCloudWithdrawal(account)
            }
            cloud.consent(account, true)
            check(auth.accountId() == account)
            database.enableCloudConsent(account)
            saveUserPreferenceUseCase(PreferencesKey.CloudRecapConsentAccount, account)
            saveUserPreferenceUseCase(PreferencesKey.CloudStoredRecapsEnabled, true)
            return@withLock
        }
        val owner = getUserPreferenceUseCase<String>(PreferencesKey.CloudRecapConsentAccount)
        // Persist the privacy command before changing preferences or doing
        // network I/O. Local failures must not pretend withdrawal succeeded.
        if (owner != null) database.queueCloudWithdrawal(owner, Clock.System.now().toEpochMilliseconds())
        saveUserPreferenceUseCase(PreferencesKey.CloudStoredRecapsEnabled, false)
        database.withdrawText(Clock.System.now().toEpochMilliseconds())
        // Text captured under the old consent must never go out later.
        try {
            if (owner != null) {
                if (auth.accountId() == owner) {
                    cloud.consent(owner, false)
                    if (auth.accountId() == owner) database.acknowledgeCloudWithdrawal(owner)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The durable command is already stored; reconnect retries it.
        }
    }
}
