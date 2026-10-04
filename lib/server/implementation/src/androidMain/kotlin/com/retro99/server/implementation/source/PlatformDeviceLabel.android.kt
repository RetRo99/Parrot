package com.retro99.server.implementation.source

import android.os.Build

internal actual fun platformDeviceLabel(): String? {
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    val model = Build.MODEL?.trim().orEmpty()
    return when {
        manufacturer.isBlank() -> model.ifBlank { null }
        model.isBlank() -> manufacturer
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }
}
