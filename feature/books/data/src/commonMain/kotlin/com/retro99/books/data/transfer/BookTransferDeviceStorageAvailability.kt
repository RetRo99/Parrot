package com.retro99.books.data.transfer

import com.retro99.books.domain.DeviceStorageAvailability
import com.retro99.server.api.library.DeviceStorageRef
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [DeviceStorageAvailability::class])
class BookTransferDeviceStorageAvailability(
    @Provided private val fileStore: BookFileTransferFileStore,
) : DeviceStorageAvailability {
    override suspend fun exists(storage: DeviceStorageRef): Boolean =
        fileStore.exists(storage.value)
}
