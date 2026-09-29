package com.retro99.home.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal actual fun rememberAutofillSessionCanceller(): () -> Unit = remember { {} }
