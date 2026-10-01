package com.retro99.reader.ui.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(
    includes = [
        PlatformReaderModule::class,
    ],
)
@ComponentScan("com.retro99.reader.ui")
class ReaderUiModule
