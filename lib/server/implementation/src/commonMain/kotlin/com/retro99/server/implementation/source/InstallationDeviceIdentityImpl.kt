package com.retro99.server.implementation.source

import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.SourceDeviceIdentity
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [InstallationDeviceIdentity::class])
class InstallationDeviceIdentityImpl(
    @Provided private val preferences: Preferences,
) : InstallationDeviceIdentity {
    override fun getOrCreate(): SourceDeviceIdentity {
        val id = preferences.getStringOrNull(PreferencesKey.InstallationDeviceId)
            ?.takeIf { value -> value.isUuid() }
            ?: newUuid().also { generated ->
                preferences.putString(PreferencesKey.InstallationDeviceId, generated)
            }
        return SourceDeviceIdentity(
            id = id,
            name = platformDeviceLabel()?.trim()?.take(MAX_DEVICE_NAME_LENGTH)?.ifBlank { null },
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun String.isUuid(): Boolean = runCatching { Uuid.parse(this) }.isSuccess

    @OptIn(ExperimentalUuidApi::class)
    private fun newUuid(): String = Uuid.random().toString()

    private companion object {
        const val MAX_DEVICE_NAME_LENGTH = 80
    }
}

internal expect fun platformDeviceLabel(): String?
