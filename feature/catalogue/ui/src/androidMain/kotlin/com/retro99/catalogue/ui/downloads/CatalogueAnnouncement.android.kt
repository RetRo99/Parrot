package com.retro99.catalogue.ui.downloads

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun catalogueAnnouncementSender(): (String) -> Unit {
    val view = LocalView.current
    return remember(view) { { text ->
        @Suppress("DEPRECATION")
        view.announceForAccessibility(text)
    } }
}
