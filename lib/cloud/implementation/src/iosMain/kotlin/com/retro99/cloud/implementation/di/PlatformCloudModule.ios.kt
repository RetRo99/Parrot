package com.retro99.cloud.implementation.di

import com.retro99.cloud.implementation.CloudConfiguration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import platform.Foundation.NSBundle

@Module
actual class PlatformCloudModule {
    @Single
    fun provideCloudConfiguration(): CloudConfiguration {
        val bundle = NSBundle.mainBundle
        return CloudConfiguration(
            supabaseUrl = (
                bundle.objectForInfoDictionaryKey(CloudConfiguration.SUPABASE_URL_INFO_KEY) as? String
            ).orEmpty(),
            publishableKey = (
                bundle.objectForInfoDictionaryKey(
                    CloudConfiguration.SUPABASE_PUBLISHABLE_KEY_INFO_KEY,
                ) as? String
            ).orEmpty(),
        )
    }
}
