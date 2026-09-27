package com.retro99.home.ui.appsettings

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-wide tap shield while a profile mutation runs and briefly after its modal closes. */
internal class ProfileOperationTapShield(
    private val repeatTapWindowMillis: Long = DEFAULT_REPEAT_TAP_WINDOW_MILLIS,
) {
    private val _isBlocking = MutableStateFlow(false)
    val isBlocking: StateFlow<Boolean> = _isBlocking.asStateFlow()

    fun block() {
        _isBlocking.value = true
    }

    suspend fun releaseAfterRepeatTapWindow() {
        delay(repeatTapWindowMillis)
        _isBlocking.value = false
    }

    companion object {
        const val DEFAULT_REPEAT_TAP_WINDOW_MILLIS = 250L
    }
}

/** Shared between the profile ViewModel and Home's root input surface. */
internal object ProfileOperationTapShieldHolder {
    val instance = ProfileOperationTapShield()
}
