package com.retro99.cloud.implementation.di

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module(
    includes = [
        PlatformCloudModule::class,
    ],
)
@Configuration
@ComponentScan("com.retro99.cloud.implementation")
class CloudModule
