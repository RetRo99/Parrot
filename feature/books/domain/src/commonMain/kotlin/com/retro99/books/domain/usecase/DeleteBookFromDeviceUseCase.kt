package com.retro99.books.domain.usecase

import com.retro99.base.result.CompletableResult
import com.retro99.books.domain.DeviceLibraryRepository
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/** Deletes a library book and its files from this device. Parrot Cloud keeps its copy. */
@Factory
class DeleteBookFromDeviceUseCase(
    @Provided private val deviceLibraryRepository: DeviceLibraryRepository,
) {
    suspend operator fun invoke(libraryBookId: String): CompletableResult =
        deviceLibraryRepository.deleteBookFromDevice(libraryBookId)
}
