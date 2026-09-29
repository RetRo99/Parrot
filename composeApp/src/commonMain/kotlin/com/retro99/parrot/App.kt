package com.retro99.parrot

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.EmberMode
import com.retro99.base.ui.compose.ParrotTheme
import com.retro99.base.ui.compose.ThemeMode
import com.retro99.base.ui.compose.colors
import com.retro99.base.ui.compose.resolveEmberMode
import com.retro99.parrot.navigation.RootNavigation
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import org.koin.compose.koinInject

@Composable
fun App(onRootWelcomeBack: (() -> Unit)? = null) {
    val platform = getPlatform()
    val preferences: Preferences = koinInject()
    val storedThemeMode by remember(preferences) {
        preferences.observeStringOrNull(PreferencesKey.ThemeMode)
    }.collectAsState(initial = preferences.getStringOrNull(PreferencesKey.ThemeMode))
    val emberMode = resolveEmberMode(
        themeMode = ThemeMode.fromKey(storedThemeMode),
        isSystemDark = isSystemInDarkTheme(),
        isEinkDevice = platform.isEink,
    )
    SetNavigationBarAppearance(
        isLight = emberMode != EmberMode.Night,
        backgroundColor = emberMode.colors().bg,
    )

    ParrotTheme(mode = emberMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            RootNavigation(
                onRootWelcomeBack = onRootWelcomeBack,
                modifier = Modifier
                    .navigationBarsPadding(),
            )
        }
    }
}
