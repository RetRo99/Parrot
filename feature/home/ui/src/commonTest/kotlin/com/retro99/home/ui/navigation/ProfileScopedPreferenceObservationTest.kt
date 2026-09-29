package com.retro99.home.ui.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileScopedPreferenceObservationTest {
    @Test
    fun profileSwitchClearsPriorValueBeforeResolvingDestinationValue() = runTest {
        val activeProfileId = MutableStateFlow<String?>("source-profile")
        val values = mapOf(
            "source-profile" to MutableStateFlow<String?>("saved-target"),
            "empty-profile" to MutableStateFlow<String?>(null),
        )
        val observed = mutableListOf<ProfileScopedPreferenceValue<String>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeProfileScopedPreference(
                activeProfileIds = activeProfileId,
                observePreference = { values.getValue(activeProfileId.value!!) },
            ).collect(observed::add)
        }

        assertEquals(
            listOf(
                ProfileScopedPreferenceValue<String>(null, false, false, false),
                ProfileScopedPreferenceValue("saved-target", true, true, false),
            ),
            observed,
        )

        activeProfileId.value = "empty-profile"

        assertEquals(
            listOf(
                ProfileScopedPreferenceValue<String>(null, false, false, false),
                ProfileScopedPreferenceValue("saved-target", true, true, false),
                ProfileScopedPreferenceValue<String>(null, false, false, true),
                ProfileScopedPreferenceValue<String>(null, true, true, true),
            ),
            observed,
        )
    }

    @Test
    fun updatesFromPreviousProfileAreCancelledAfterRebind() = runTest {
        val activeProfileId = MutableStateFlow<String?>("source-profile")
        val sourceValue = MutableStateFlow<String?>("saved-target")
        val destinationValue = MutableStateFlow<String?>(null)
        val observed = mutableListOf<String?>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeProfileScopedPreference(
                activeProfileIds = activeProfileId,
                observePreference = {
                    if (activeProfileId.value == "source-profile") sourceValue else destinationValue
                },
            ).collect { state ->
                if (state.isResolved) observed += state.value
            }
        }

        assertEquals(listOf<String?>("saved-target"), observed)
        activeProfileId.value = "empty-profile"
        sourceValue.value = "new-source-target"
        destinationValue.value = "destination-target"

        assertEquals(listOf<String?>("saved-target", null, "destination-target"), observed)
    }
}
