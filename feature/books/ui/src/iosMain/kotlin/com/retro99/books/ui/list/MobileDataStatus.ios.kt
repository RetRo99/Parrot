package com.retro99.books.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.NWInterfaceTypeCellular
import platform.Network.NWPathMonitor
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberIsOnMobileData(): Boolean {
    var isMobile by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val monitor = NWPathMonitor()
        monitor.setPathUpdateHandler { path ->
            isMobile = path.usesInterfaceType(NWInterfaceTypeCellular)
        }
        monitor.startWithQueue(dispatch_get_main_queue())
        onDispose { monitor.cancel() }
    }
    return isMobile
}
