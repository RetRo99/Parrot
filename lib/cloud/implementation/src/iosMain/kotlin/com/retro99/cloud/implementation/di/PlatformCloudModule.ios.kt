package com.retro99.cloud.implementation.di

import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.CloudOAuthUrlLauncher
import com.retro99.cloud.implementation.transfer.TusLocalFileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.seekToFileOffset
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.readDataOfLength
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

    @Single
    fun provideTusLocalFileSource(): TusLocalFileSource = IosTusLocalFileSource()

    @Single
    fun provideTusHttpClient(): HttpClient = HttpClient(Darwin)
}

private class IosTusLocalFileSource : TusLocalFileSource {
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun size(path: String): Long {
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)
            ?: error("Could not inspect upload file")
        return (attributes.get("NSFileSize") as? Long)
            ?: error("Could not read upload file size")
    }

    @OptIn(ExperimentalForeignApi::class)
    override suspend fun read(path: String, offset: Long, length: Int): ByteArray {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path)
            ?: error("Could not open upload file")
        return try {
            handle.seekToFileOffset(offset.toULong())
            val data = handle.readDataOfLength(length.convert())
            if (data.length == 0UL) return byteArrayOf()
            data.bytes?.reinterpret<kotlinx.cinterop.ByteVar>()?.readBytes(data.length.toInt())
                ?: error("Could not read upload file")
        } finally {
            handle.closeFile()
        }
    }
}

private class IosCloudOAuthUrlLauncher : CloudOAuthUrlLauncher {
    override fun open(url: String) {
        NSURL.URLWithString(url)?.let { oauthUrl ->
            UIApplication.sharedApplication.openURL(oauthUrl)
        }
    }
}
