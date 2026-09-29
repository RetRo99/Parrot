package com.retro99.parrot

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.retro99.base.ui.platform.isEinkDisplay
import platform.UIKit.UIDevice

class IOSPlatform : Platform {
    override val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    override val isEink: Boolean = isEinkDisplay()
}

actual fun getPlatform(): Platform = IOSPlatform()

@Composable
actual fun SetNavigationBarAppearance(isLight: Boolean, backgroundColor: Color) = Unit
