package com.retro99.parrot

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.retro99.base.ui.platform.isEinkDisplay

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"

    override val isEink: Boolean = isEinkDisplay()
}

actual fun getPlatform(): Platform = AndroidPlatform()

@Composable
actual fun SetNavigationBarAppearance(isLight: Boolean, backgroundColor: Color) {
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowInsetsControllerCompat(window, view).apply {
            isAppearanceLightNavigationBars = isLight
            isAppearanceLightStatusBars = isLight
        }
        window.navigationBarColor = backgroundColor.toArgb()
        window.isNavigationBarContrastEnforced = false
    }
}
