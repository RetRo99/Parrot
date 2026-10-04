package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.recap.RecapBannerDismissals
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Dismissals in the active profile's preferences, newest last, capped. */
@Single(binds = [RecapBannerDismissals::class])
class PreferencesRecapBannerDismissals(
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val database: SessionRecapDatabase,
) : RecapBannerDismissals {
    private val mutex = Mutex()

    override suspend fun isDismissed(sessionId: String): Boolean = sessionId in entries()

    override suspend fun dismiss(sessionId: String) = mutex.withLock {
        saveUserPreferenceUseCase(
            PreferencesKey.DismissedRecapBanners,
            live((entries() - sessionId) + sessionId),
        )
    }

    /**
     * A dismissal only matters while its recap can still be offered, so
     * entries whose recap is gone are dropped before the cap: evicting a live
     * one would make a dismissed chip resurface.
     */
    private suspend fun live(ids: List<String>): List<String> =
        ids.filter { database.getRecap(it) != null }
            .takeLast(RecapBannerDismissals.MAX_ENTRIES)

    private fun entries(): List<String> =
        getUserPreferenceUseCase<List<String>>(PreferencesKey.DismissedRecapBanners).orEmpty()
}
