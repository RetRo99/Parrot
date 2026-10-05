package com.retro99.books.ui.list

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberIsOnMobileData(): Boolean {
    val context = LocalContext.current
    val connectivity = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    var isMobile by remember(connectivity) { mutableStateOf(connectivity.isMobileNetwork()) }

    DisposableEffect(connectivity) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isMobile = connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                isMobile = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }

            override fun onLost(network: Network) {
                isMobile = connectivity.isMobileNetwork()
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return isMobile
}

private fun ConnectivityManager.isMobileNetwork(): Boolean = activeNetwork
    ?.let(::getNetworkCapabilities)
    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
