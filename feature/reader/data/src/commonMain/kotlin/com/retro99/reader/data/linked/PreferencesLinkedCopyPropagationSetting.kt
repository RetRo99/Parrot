package com.retro99.reader.data.linked

import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.implementation.usecase.GetUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.ObserveUserPreferenceUseCase
import com.retro99.preferences.implementation.usecase.SaveUserPreferenceUseCase
import com.retro99.reader.domain.write.LinkedCopyPropagationSetting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** The setting in the active profile's preferences. Unset means on. */
@Single(binds = [LinkedCopyPropagationSetting::class])
class PreferencesLinkedCopyPropagationSetting(
    @Provided private val getUserPreferenceUseCase: GetUserPreferenceUseCase,
    @Provided private val saveUserPreferenceUseCase: SaveUserPreferenceUseCase,
    @Provided private val observeUserPreferenceUseCase: ObserveUserPreferenceUseCase,
) : LinkedCopyPropagationSetting {

    override fun observeEnabled(): Flow<Boolean> =
        observeUserPreferenceUseCase<Boolean>(PreferencesKey.UpdateLinkedCopies)
            .map { enabled -> enabled ?: true }

    override suspend fun isEnabled(): Boolean =
        getUserPreferenceUseCase<Boolean>(PreferencesKey.UpdateLinkedCopies) ?: true

    override suspend fun setEnabled(enabled: Boolean) {
        saveUserPreferenceUseCase(PreferencesKey.UpdateLinkedCopies, enabled)
    }
}
