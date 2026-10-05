package com.retro99.dictionary

import app.cash.sqldelight.db.SqlDriver
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

interface DictionaryPlatform {
    val packRoot: String
    fun availableBytes(): Long
    fun open(path: String): SqlDriver
}

@Module
expect class PlatformDictionaryModule

@Module(includes = [PlatformDictionaryModule::class])
@Configuration
@ComponentScan("com.retro99.dictionary")
class DictionaryModule
