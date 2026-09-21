package com.retro99.cloud.implementation.di

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.CloudOAuthUrlLauncher
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

    @Single
    fun provideCloudOAuthUrlLauncher(context: Context): CloudOAuthUrlLauncher {
        return AndroidCloudOAuthUrlLauncher(context)
    }
}

private class AndroidCloudOAuthUrlLauncher(
    private val context: Context,
) : CloudOAuthUrlLauncher {
    override fun open(url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
