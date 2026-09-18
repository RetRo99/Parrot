package com.retro99.cloud.implementation.di

import android.content.Context
import android.content.pm.PackageManager
import com.retro99.cloud.implementation.CloudConfiguration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
actual class PlatformCloudModule {
    @Single
    fun provideCloudConfiguration(context: Context): CloudConfiguration {
        val metadata = context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
        return CloudConfiguration(
            supabaseUrl = metadata
                ?.getString(CloudConfiguration.SUPABASE_URL_METADATA_KEY)
                .orEmpty(),
            publishableKey = metadata
                ?.getString(CloudConfiguration.SUPABASE_PUBLISHABLE_KEY_METADATA_KEY)
                .orEmpty(),
        )
    }
}
