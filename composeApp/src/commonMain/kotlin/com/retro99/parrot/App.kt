package com.retro99.parrot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.ParrotTheme
import com.retro99.parrot.navigation.RootNavigation

@Composable
fun App(onRootWelcomeBack: (() -> Unit)? = null) {
    val platform = getPlatform()

    ParrotTheme(eink = platform.isEink) {
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
