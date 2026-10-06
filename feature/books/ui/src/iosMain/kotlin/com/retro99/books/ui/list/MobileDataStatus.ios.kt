package com.retro99.books.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberIsOnMobileData(): Boolean {
    var isMobile by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        // NWPathMonitor (ObjC) is not exposed in Kotlin/Native's platform.Network
        // bindings, so the equivalent C API (nw_path_monitor_*) is used instead.
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            isMobile = path != null && nw_path_uses_interface_type(path, nw_interface_type_cellular)
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_start(monitor)
        onDispose { nw_path_monitor_cancel(monitor) }
    }
    return isMobile
}
