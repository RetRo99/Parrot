package com.retro99.sync.data.di

import com.retro99.analytics.api.Analytics
import com.retro99.database.api.sync.SyncCheckpointDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.sync.data.SyncDataRepository
import com.retro99.sync.data.SyncDestination
import com.retro99.sync.data.SyncExecutionContextProvider
import com.retro99.sync.data.SyncPass
import com.retro99.sync.domain.FileTransferStatusSource
import com.retro99.sync.domain.FileTransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import com.retro99.sync.domain.SyncRepository
import java.lang.reflect.Proxy
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SyncDataModuleRegressionTest {
    @Test
    fun `registered transfer status sources reach the coordinator`() = withProductionRepository { koin ->
        assertEquals(1, koin.getAll<FileTransferStatusSource>().size)
        assertEquals(1, collection(koin, "fileTransferStatusSources").size)
        assertSame(koin.get<FileTransferStatusSource>(), collection(koin, "fileTransferStatusSources").single())
    }

    @Test
    fun `registered external destinations reach the coordinator`() = withProductionRepository { koin ->
        assertEquals(1, koin.getAll<SyncDestination>().size)
        assertEquals(1, collection(koin, "destinations").size)
        assertSame(koin.get<SyncDestination>(), collection(koin, "destinations").single())
    }

    private fun collection(koin: Koin, name: String): List<*> {
        val field = SyncDataRepository::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(koin.get<SyncRepository>()) as List<*>
    }

    private fun withProductionRepository(check: (Koin) -> Unit) {
        val production = Class.forName("com.retro99.sync.data.di.ComRetro99SyncDataDiSyncDataModuleModuleKt")
            .getMethod("module", SyncDataModule::class.java).invoke(null, SyncDataModule()) as Module
        val app = koinApplication {
            modules(production, module {
                single<SyncPass> { stub() }
                single<SyncExecutionContextProvider> { stub() }
                single<SyncOutboxDatabase> { stub() }
                single<SyncCheckpointDatabase> { stub() }
                single<Analytics> { stub() }
                single<FileTransferStatusSource> {
                    object : FileTransferStatusSource {
                        override fun observe(): Flow<FileTransferStatus?> = emptyFlow()
                    }
                }
                single<SyncDestination> { stub() }
            })
        }
        try {
            check(app.koin)
        } finally {
            app.close()
        }
    }

    private inline fun <reified T> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> T::class.java.simpleName
                else -> error("Unexpected ${T::class.java.simpleName} call: ${method.name}")
            }
        } as T
}
