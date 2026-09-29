package com.retro99.home.ui.navigation

import androidx.compose.runtime.Composable

@Composable
internal expect fun rememberAutofillSessionCanceller(): () -> Unit

internal fun shouldCancelCloudAccountAutofill(source: HomeDestination?): Boolean =
    source is HomeDestination.SyncAndBackup
