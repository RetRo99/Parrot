package com.retro99.dictionary

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.koin.core.annotation.Module
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import java.io.File

@Module
actual class PlatformDictionaryModule {
    @Single
    fun platform(@Provided context: Context): DictionaryPlatform = object : DictionaryPlatform {
        override val packRoot = File(context.filesDir, "packs").absolutePath
        override fun availableBytes() = context.filesDir.usableSpace
        override fun open(path: String): SqlDriver = AndroidSqliteDriver(DictionaryDatabase.Schema, context, path)
    }
}
