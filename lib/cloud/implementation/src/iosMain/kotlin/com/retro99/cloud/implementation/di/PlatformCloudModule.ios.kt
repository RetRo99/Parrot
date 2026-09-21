package com.retro99.cloud.implementation.di

import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.CloudOAuthUrlLauncher
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

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

    @Single
    fun provideCloudOAuthUrlLauncher(): CloudOAuthUrlLauncher {
        return IosCloudOAuthUrlLauncher()
    }
}

private class IosCloudOAuthUrlLauncher : CloudOAuthUrlLauncher {
    override fun open(url: String) {
        NSURL.URLWithString(url)?.let { oauthUrl ->
            UIApplication.sharedApplication.openURL(oauthUrl)
        }
    }
}
