package com.retro99.books.domain

import com.retro99.server.api.library.DeviceStorageRef

fun interface DeviceStorageAvailability {
    suspend fun exists(storage: DeviceStorageRef): Boolean
}
