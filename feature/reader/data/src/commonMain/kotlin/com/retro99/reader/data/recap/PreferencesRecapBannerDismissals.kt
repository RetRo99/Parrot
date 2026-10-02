package com.retro99.reader.data.recap

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
) : RecapBannerDismissals {
    private val mutex = Mutex()

    override suspend fun isDismissed(sessionId: String): Boolean = sessionId in entries()

    override suspend fun dismiss(sessionId: String) = mutex.withLock {
        saveUserPreferenceUseCase(
            PreferencesKey.DismissedRecapBanners,
            ((entries() - sessionId) + sessionId).takeLast(RecapBannerDismissals.MAX_ENTRIES),
        )
    }

    private fun entries(): List<String> =
        getUserPreferenceUseCase<List<String>>(PreferencesKey.DismissedRecapBanners).orEmpty()
}
