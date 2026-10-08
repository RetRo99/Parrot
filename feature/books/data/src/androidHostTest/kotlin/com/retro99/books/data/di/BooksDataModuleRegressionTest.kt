package com.retro99.books.data.di

import com.retro99.analytics.api.Analytics
import com.retro99.books.data.transfer.BookFileTransferEngine
import com.retro99.books.data.transfer.BookFileTransferFileStore
import com.retro99.books.data.transfer.DownloadTransferFinalizer
import com.retro99.books.domain.BookFileDeletionTransport
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.library.DeviceFilesDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.sync.domain.FileTransferStatusSource
import java.lang.reflect.Proxy
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Resolve the compiler-generated production module, not a hand-written test factory. */
class BooksDataModuleRegressionTest {
    @Test
    fun `registered download transport enables downloads`() = withProductionEngine { koin ->
        assertTrue(koin.getAll<BookFileDownloadTransport>().single().supportsDownload)
        assertTrue(koin.get<BookFileTransferManager>().supportsDownload("cloud"))
    }

    @Test
    fun `registered deletion transport enables deletion`() = withProductionEngine { koin ->
        assertTrue(koin.getAll<BookFileDeletionTransport>().size == 1)
        assertTrue(koin.get<BookFileTransferManager>().supportsDeletion("cloud"))
    }

    @Test
    fun `transfer manager and status source share one engine`() = withProductionEngine { koin ->
        assertSame<Any>(koin.get<BookFileTransferManager>(), koin.get<FileTransferStatusSource>())
    }

    @Test
    fun `nullable production dependencies are injected when registered`() = withProductionEngine { koin ->
        val engine = koin.get<BookFileTransferManager>() as BookFileTransferEngine
        listOf(
            "downloadFinalizer" to koin.get<DownloadTransferFinalizer>(),
            "fileStore" to koin.get<BookFileTransferFileStore>(),
            "analytics" to koin.get<Analytics>(),
        ).forEach { (name, dependency) ->
            val field = BookFileTransferEngine::class.java.getDeclaredField(name)
            field.isAccessible = true
            assertSame(dependency, field.get(engine), name)
        }
    }

    private fun withProductionEngine(check: (Koin) -> Unit) {
        // The generated extension is emitted after source analysis, so load its JVM entry point.
        val production = Class.forName("com.retro99.books.data.di.ComRetro99BooksDataDiBooksDataModuleModuleKt")
            .getMethod("module", BooksDataModule::class.java).invoke(null, BooksDataModule()) as Module
        val app = koinApplication {
            modules(production, module {
                single<CloudFilesDatabase> { stub() }
                single<DeviceFilesDatabase> { stub() }
                single<LibraryBooksDatabase> { stub() }
                single<BookFileTransferFileStore> { stub() }
                single<DownloadTransferFinalizer> { stub() }
                single<Analytics> { stub() }
                single<BookFileDownloadTransport> { stub { name -> when (name) {
                    "getServerId" -> "cloud"
                    "getSupportsDownload" -> true
                    else -> error("Unexpected download transport call: $name")
                } } }
                single<BookFileDeletionTransport> { stub { name ->
                    if (name == "getServerId") "cloud" else error("Unexpected deletion transport call: $name")
                } }
            })
        }
        try {
            check(app.koin)
        } finally {
            app.close()
        }
    }

    private inline fun <reified T> stub(crossinline result: (String) -> Any? = { error("Unexpected call: $it") }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> T::class.java.simpleName
                else -> result(method.name)
            }
        } as T
}
