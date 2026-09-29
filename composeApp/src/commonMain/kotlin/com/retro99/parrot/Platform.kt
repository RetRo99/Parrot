package com.retro99.parrot

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

interface Platform {
    val name: String
    val isEink: Boolean
}

expect fun getPlatform(): Platform

/** Keeps Android system navigation icons readable against the app's current theme. */
@Composable
expect fun SetNavigationBarAppearance(isLight: Boolean, backgroundColor: Color)
