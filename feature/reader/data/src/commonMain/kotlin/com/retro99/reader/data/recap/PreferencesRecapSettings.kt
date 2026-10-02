package com.retro99.reader.data.recap

import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.recap.RecapSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Cloud recap consent in the active profile's preferences. Unset means off. */
@Single(binds = [RecapSettings::class])
class PreferencesRecapSettings(
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val observeUserPreferenceUseCase: ObserveUserPreferenceUseCase,
) : RecapSettings {

    override fun observeCloudRecapsEnabled(): Flow<Boolean> =
        observeUserPreferenceUseCase<Boolean>(PreferencesKey.CloudRecapsEnabled)
            .map { enabled -> enabled ?: false }
            .distinctUntilChanged()

    override suspend fun isCloudRecapsEnabled(): Boolean =
        getUserPreferenceUseCase<Boolean>(PreferencesKey.CloudRecapsEnabled) ?: false

    override suspend fun setCloudRecapsEnabled(enabled: Boolean) {
        saveUserPreferenceUseCase(PreferencesKey.CloudRecapsEnabled, enabled)
    }
}
