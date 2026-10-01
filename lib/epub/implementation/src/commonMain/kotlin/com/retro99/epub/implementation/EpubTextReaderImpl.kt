package com.retro99.epub.implementation

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.epub.api.EpubChapterText
import com.retro99.epub.api.EpubTextReader
import com.retro99.epub.implementation.text.XhtmlTextExtractor
import com.retro99.epub.implementation.zip.RandomAccessSource
import com.retro99.epub.implementation.zip.ZipArchive
import com.retro99.epub.implementation.zip.openRandomAccessSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/** Entries bigger than this are skipped: no chapter or SMIL file is legitimately this large. */
internal const val MAX_TEXT_ENTRY_BYTES = 10L * 1024 * 1024

private val xhtmlMediaTypes = setOf("application/xhtml+xml", "text/html")

/**
 * Reads chapter text entry by entry through the ZIP central directory: the package document
 * and the spine's XHTML files only, never audio, and never the whole archive (BookBridge #414).
 */
@Single(binds = [EpubTextReader::class])
class EpubTextReaderImpl(
    @Provided private val analytics: Analytics,
) : EpubTextReader {

    internal var openSource: (String) -> RandomAccessSource? = ::openRandomAccessSource

    override suspend fun readChapters(filePath: String): AppResult<List<EpubChapterText>> =
        withContext(Dispatchers.IO) {
            try {
                val source = openSource(filePath)
                    ?: return@withContext Err(AppError.NotFoundError("EPUB not found"))
                ZipArchive.open(source).use { archive ->
                    val epubPackage = EpubPackage.read(archive)
                    val chapters = epubPackage.readingOrder
                        .filter { item -> item.mediaType in xhtmlMediaTypes }
                        .map { item ->
                            val bytes = archive.read(item.path, MAX_TEXT_ENTRY_BYTES)
                            if (bytes == null) logSkipped(archive.entries[item.path] != null)
                            val extracted = XhtmlTextExtractor.extract(
                                bytes?.decodeToString().orEmpty(),
                            )
                            EpubChapterText(
                                href = item.path,
                                title = extracted.title,
                                text = extracted.text,
                                elementOffsets = extracted.elementOffsets,
                            )
                        }
                    Ok(chapters)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Err(AppError.UnknownError(exception))
            }
        }

    private fun logSkipped(exists: Boolean) {
        analytics.logBreadcrumb(
            DiagnosticContext(
                operation = "epub_text_read",
                stage = "chapter",
                outcome = "skipped",
                reasonCode = if (exists) "entry_too_large" else "entry_missing",
            ),
        )
    }
}
