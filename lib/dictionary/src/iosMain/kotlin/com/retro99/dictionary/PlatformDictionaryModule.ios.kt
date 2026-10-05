@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.retro99.dictionary

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber

@Module
actual class PlatformDictionaryModule {
    @Single
    fun platform(): DictionaryPlatform = object : DictionaryPlatform {
        private val library = (NSFileManager.defaultManager.URLsForDirectory(NSLibraryDirectory, NSUserDomainMask).first() as NSURL).path!!
        override val packRoot = "$library/packs"
        override fun availableBytes(): Long = (NSFileManager.defaultManager.attributesOfFileSystemForPath(library, null)
            ?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0
        override fun open(path: String): SqlDriver = NativeSqliteDriver(
            DictionaryDatabase.Schema, name = path.substringAfterLast('/'),
            onConfiguration = { config -> config.copy(journalMode = JournalMode.DELETE,
                extendedConfig = config.extendedConfig.copy(basePath = path.substringBeforeLast('/'))) },
        )
    }
}
