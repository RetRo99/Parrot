package com.retro99.home.ui.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

internal data class ProfileScopedPreferenceValue<T>(
    val value: T?,
    val isResolved: Boolean,
    val isFirstValue: Boolean,
    val isProfileSwitch: Boolean,
)

/**
 * Re-subscribes to a user-scoped preference whenever the active profile changes.
 * A not-ready null is emitted first so UI state from the previous profile is cleared
 * before the destination profile's persisted value is read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> observeProfileScopedPreference(
    activeProfileIds: Flow<String?>,
    observePreference: () -> Flow<T?>,
): Flow<ProfileScopedPreferenceValue<T>> = flow {
    var hasObservedProfile = false
    activeProfileIds
        .distinctUntilChanged()
        .transformLatest {
            val isProfileSwitch = hasObservedProfile
            hasObservedProfile = true
            emit(
                ProfileScopedPreferenceValue<T>(
                    value = null,
                    isResolved = false,
                    isFirstValue = false,
                    isProfileSwitch = isProfileSwitch,
                ),
            )

            var isFirstValue = true
            observePreference().collect { value ->
                emit(
                    ProfileScopedPreferenceValue(
                        value = value,
                        isResolved = true,
                        isFirstValue = isFirstValue,
                        isProfileSwitch = isProfileSwitch,
                    ),
                )
                isFirstValue = false
            }
        }
        .collect { emit(it) }
}
