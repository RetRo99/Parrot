package com.retro99.reader.ui.playback.auto

import com.retro99.analytics.api.Analytics
import com.retro99.reader.ui.media.smil.SmilContentProvider
import com.retro99.reader.ui.publication.EpubPublication
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.getOrElse

/**
 * SMIL content provider for headless playback.
 *
 * This is a standalone implementation that doesn't depend on ReaderScope,
 * allowing it to be used in the service context for Android Auto playback.
 *
 * Functionally identical to PublicationSmilContentProvider, but without
 * the @Scope annotation that would tie it to ReaderScope.
 */
class HeadlessSmilContentProvider(
    private val epubPublication: EpubPublication,
    private val analytics: Analytics,
) : SmilContentProvider {

    private val publication: Publication = epubPublication.publication

    override suspend fun readSmilContent(smilHref: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = Url(smilHref) ?: return@withContext null
                val resource = publication.get(url) ?: return@withContext null
                val bytes = resource.read().getOrElse { return@withContext null }
                bytes.decodeToString()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                analytics.logException(e, "HeadlessSmilContentProvider: Failed to read SMIL file: $smilHref")
                null
            }
        }
    }

    override fun getAllSmilHrefs(): List<String> {
        // Try to find SMIL files in resources
        val fromResources = publication.resources
            .filter { link ->
                link.mediaType?.toString()?.contains("smil") == true ||
                        link.href.toString().endsWith(".smil")
            }
            .map { it.href.toString() }

        // If no SMIL files in resources, try to find them in readingOrder alternates
        if (fromResources.isEmpty()) {
            val fromAlternates = publication.readingOrder
                .flatMap { it.alternates }
                .filter { link ->
                    link.mediaType?.toString()?.contains("smil") == true ||
                            link.href.toString().endsWith(".smil")
                }
                .map { it.href.toString() }
            if (fromAlternates.isNotEmpty()) {
                return fromAlternates
            }
        }

        return fromResources
    }

    override fun getReadingOrder(): List<String> {
        return publication.readingOrder.map { it.href.toString() }
    }

    override fun resolveSmilPath(smilHref: String, relativePath: String): String {
        // If relative path is absolute (starts with /), return as-is without leading slash
        if (relativePath.startsWith('/')) {
            return relativePath.removePrefix("/")
        }

        // Get the directory of the SMIL file
        val baseDir = smilHref.substringBeforeLast('/', "")

        // Handle ../ navigation
        var currentDir = baseDir
        var remainingPath = relativePath

        while (remainingPath.startsWith("../")) {
            remainingPath = remainingPath.removePrefix("../")
            currentDir = currentDir.substringBeforeLast('/', "")
        }

        // Remove leading ./ if present
        remainingPath = remainingPath.removePrefix("./")

        // Combine directory and remaining path
        return if (currentDir.isEmpty()) {
            remainingPath
        } else {
            "$currentDir/$remainingPath"
        }
    }
}

