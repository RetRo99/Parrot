package com.retro99.reader.data

import android.content.Context
import android.graphics.Bitmap
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android implementation of [EpubMetadataExtractor] using Readium.
 */
@Single(binds = [EpubMetadataExtractor::class])
class AndroidEpubMetadataExtractor(
    private val context: Context,
    private val analytics: Analytics,
) : EpubMetadataExtractor {

    private val httpClient by lazy { DefaultHttpClient() }
    private val assetRetriever by lazy { AssetRetriever(context.contentResolver, httpClient) }
    private val publicationParser by lazy {
        DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null)
    }
    private val publicationOpener by lazy { PublicationOpener(publicationParser) }

    override suspend fun extractMetadata(filePath: String): AppResult<EpubMetadata> =
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    return@withContext Err(
                        AppError.UnknownError(Throwable("EPUB file not found: $filePath"))
                    )
                }

                val url = file.toUrl()
                val asset = assetRetriever.retrieve(url).getOrElse { error ->
                    analytics.logException(
                        Exception("Asset retrieval failed: ${error.message}"),
                        "AndroidEpubMetadataExtractor: Failed to retrieve asset",
                    )
                    return@withContext Err(
                        AppError.UnknownError(Throwable("Failed to retrieve EPUB asset: ${error.message}"))
                    )
                }

                val publication = publicationOpener.open(asset, allowUserInteraction = false)
                    .getOrElse { error ->
                        analytics.logException(
                            Exception("Publication open failed: ${error.message}"),
                            "AndroidEpubMetadataExtractor: Failed to open publication",
                        )
                        return@withContext Err(
                            AppError.UnknownError(Throwable("Failed to open EPUB: ${error.message}"))
                        )
                    }

                val metadata = publication.metadata
                val title = metadata.title ?: file.nameWithoutExtension
                val author = metadata.authors.firstOrNull()?.name
                val description = metadata.description

                // Extract cover image
                val coverBytes = try {
                    publication.cover()?.let { bitmap ->
                        ByteArrayOutputStream().use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                            outputStream.toByteArray()
                        }
                    }
                } catch (e: Exception) {
                    analytics.logException(e, "AndroidEpubMetadataExtractor: Failed to extract cover")
                    null
                }

                // Close the publication after extracting metadata
                publication.close()

                Ok(
                    EpubMetadata(
                        title = title,
                        author = author,
                        description = description,
                        coverBytes = coverBytes,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                analytics.logException(e, "AndroidEpubMetadataExtractor: Error extracting metadata")
                Err(AppError.UnknownError(e))
            }
        }
}

