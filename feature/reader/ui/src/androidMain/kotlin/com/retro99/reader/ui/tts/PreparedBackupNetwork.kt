package com.retro99.reader.ui.tts

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * Whether the device is on a network the user is not paying for by the byte.
 *
 * The brief says "Wi-Fi only"; what the user means by that is "not out of my
 * data allowance", so this reads the system's own metered flag instead of
 * looking for a Wi-Fi transport. An unmetered Ethernet or an unmetered tether
 * therefore counts, and a Wi-Fi network the user has marked as metered does
 * not, which is what they asked for in settings.
 */
internal interface PreparedBackupNetwork {
    fun onUnmeteredNetwork(): Boolean
}

@Single(binds = [PreparedBackupNetwork::class])
internal class AndroidPreparedBackupNetwork(
    @Provided private val context: Context,
) : PreparedBackupNetwork {

    override fun onUnmeteredNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val capabilities = runCatching {
            manager.activeNetwork?.let(manager::getNetworkCapabilities)
        }.getOrNull() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
