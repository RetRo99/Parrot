package com.retro99.parrot.initializer

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.retro99.server.api.CatalogueImageModel
import com.retro99.server.api.CatalogueImageRepository
import com.retro99.server.api.CatalogueRepositoryProvider
import com.retro99.server.api.OpdsCredentialStore
import com.retro99.server.api.catalogueRasterImageType
import com.retro99.server.api.decodeCatalogueDataImage
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Buffer
import org.koin.core.annotation.Single

internal class CatalogueImageData(val bytes: ByteArray, val mediaType: String)

/**
 * Where a [CatalogueImageModel] gets its bytes (plan §10.7). The catalogue is found by its id
 * among the open profile's turned-on catalogues, and its own session makes the request, so
 * the rules of its pages apply. The global loader's client, with its bearer tokens, is never
 * involved. Only PNG, JPEG, GIF and WebP bytes are handed on.
 */
@Single
class CatalogueImageSource(
    private val repositories: CatalogueRepositoryProvider,
    private val users: UserRegistry,
    private val credentials: OpdsCredentialStore,
) {
    internal suspend fun load(model: CatalogueImageModel): CatalogueImageData? {
        val bytes = if (model.isInline) decodeCatalogueDataImage(model.url) else fetch(model)
        val mediaType = bytes?.let(::catalogueRasterImageType) ?: return null
        return CatalogueImageData(bytes, mediaType)
    }

    private suspend fun fetch(model: CatalogueImageModel): ByteArray? = try {
        (repositories.getRepository(model.sourceId) as? CatalogueImageRepository)?.loadImage(model.url)
    } catch (ended: CancellationException) {
        // A session that ended throws this too; only a cancelled caller passes it on.
        currentCoroutineContext().ensureActive()
        null
    } catch (_: Exception) {
        // Not this profile's catalogue, or no profile is open.
        null
    }

    /**
     * Null means "do not keep in memory". The key changes with the profile and whenever the
     * catalogue's account details do, so a picture fetched with one account is never shown
     * for another. Inline pictures are cheap to decode and their address is the whole file.
     */
    internal fun cacheKey(model: CatalogueImageModel): String? {
        if (model.isInline) return null
        val profileId = users.getActiveProfileId() ?: return null
        return "catalogue-image:$profileId:${model.sourceId}:${credentials.accessGeneration(profileId, model.sourceId)}:${model.url}"
    }
}

internal class CatalogueImageFetcher(
    private val model: CatalogueImageModel,
    private val options: Options,
    private val images: CatalogueImageSource,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // Coil turns the exception into the request's error state; the message has no address.
        val image = images.load(model) ?: throw IllegalStateException("Catalogue picture not available")
        return SourceFetchResult(
            source = ImageSource(Buffer().write(image.bytes), options.fileSystem),
            mimeType = image.mediaType,
            // Not kept on disk: the bytes may have needed the catalogue's account.
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val images: CatalogueImageSource) : Fetcher.Factory<CatalogueImageModel> {
        override fun create(data: CatalogueImageModel, options: Options, imageLoader: ImageLoader): Fetcher =
            CatalogueImageFetcher(data, options, images)
    }
}

internal class CatalogueImageKeyer(private val images: CatalogueImageSource) : Keyer<CatalogueImageModel> {
    override fun key(data: CatalogueImageModel, options: Options): String? = images.cacheKey(data)
}
