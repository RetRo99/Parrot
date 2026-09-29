package com.retro99.home.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalAutofillManager

@Composable
internal actual fun rememberAutofillSessionCanceller(): () -> Unit {
    val autofillManager = LocalAutofillManager.current
    return remember(autofillManager) {
        { autofillManager?.cancel() }
    }
}
