package com.retro99.sync.data.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.annotation.Provided
import com.retro99.analytics.api.Analytics
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.sync.data.SyncDataRepository
import com.retro99.sync.data.SyncDestination
import com.retro99.sync.data.SyncExecutionContextProvider
import com.retro99.sync.data.SyncOutboxPreflight
import com.retro99.sync.data.SyncPass
import com.retro99.sync.domain.SyncRepository
import com.retro99.sync.domain.FileTransferStatusSource

@Module
@Configuration
@ComponentScan("com.retro99.sync.data")
class SyncDataModule {
    // The compiler omits collection parameters with defaults rather than injecting
    // getAll(). Keep the repository's test defaults, but require destinations here.
    @Single(binds = [SyncRepository::class])
    fun syncRepository(
        @Provided syncPass: SyncPass,
        @Provided executionContextProvider: SyncExecutionContextProvider,
        @Provided syncOutboxPreflight: SyncOutboxPreflight,
        @Provided destinations: List<SyncDestination>,
        @Provided fileTransferStatusSources: List<FileTransferStatusSource>,
        @Provided syncCheckpointDatabase: SyncCheckpointDatabase,
        @Provided analytics: Analytics,
    ): SyncDataRepository = SyncDataRepository(
        syncPass = syncPass,
        executionContextProvider = executionContextProvider,
        syncOutboxPreflight = syncOutboxPreflight,
        destinations = destinations,
        fileTransferStatusSources = fileTransferStatusSources,
        syncCheckpointDatabase = syncCheckpointDatabase,
        analytics = analytics,
    )
}
