package com.retro99.cloud.implementation.di

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.retro99.cloud.implementation.CloudConfiguration
import com.retro99.cloud.implementation.CloudOAuthUrlLauncher
import com.retro99.cloud.implementation.transfer.TusLocalFileSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
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

    @Single
    fun provideTusLocalFileSource(): TusLocalFileSource = AndroidTusLocalFileSource()

    /**
     * Dedicated upload client. Qualified so it never collides with the shared app
     * HttpClient binding (see TusUploadClient).
     */
    @Single
    @Named("tus")
    fun provideTusHttpClient(): HttpClient = HttpClient(OkHttp)
}

private class AndroidTusLocalFileSource : TusLocalFileSource {
    override suspend fun size(path: String): Long = java.io.File(path).length()

    override suspend fun read(path: String, offset: Long, length: Int): ByteArray {
        val file = java.io.RandomAccessFile(path, "r")
        return try {
            file.seek(offset)
            ByteArray(length).also { bytes ->
                var read = 0
                while (read < length) {
                    val count = file.read(bytes, read, length - read)
                    if (count < 0) break
                    read += count
                }
                if (read == length) bytes else bytes.copyOf(read)
            }
        } finally {
            file.close()
        }
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
