package com.retro99.epub.implementation

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.analytics.api.Analytics
import com.retro99.analytics.api.DiagnosticContext
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.epub.api.ReadaloudTiming
import com.retro99.epub.api.ReadaloudTimingReader
import com.retro99.epub.api.TimedClip
import com.retro99.epub.implementation.smil.SmilParser
import com.retro99.epub.implementation.zip.RandomAccessSource
import com.retro99.epub.implementation.zip.ZipArchive
import com.retro99.epub.implementation.zip.openRandomAccessSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.math.roundToLong

/**
 * Reads a read-aloud's SMIL timing. Only the package document and the SMIL files are read;
 * audio entries are never touched. Each audio file's length is taken as the end of its last
 * clip, and files are placed on one global timeline in the order the book first uses them.
 */
@Single(binds = [ReadaloudTimingReader::class])
class ReadaloudTimingReaderImpl(
    @Provided private val smilParser: SmilParser,
    @Provided private val analytics: Analytics,
) : ReadaloudTimingReader {

    internal var openSource: (String) -> RandomAccessSource? = ::openRandomAccessSource

    override suspend fun readTiming(filePath: String): AppResult<ReadaloudTiming> =
        withContext(Dispatchers.IO) {
            try {
                val source = openSource(filePath)
                    ?: return@withContext Err(AppError.NotFoundError("EPUB not found"))
                ZipArchive.open(source).use { archive ->
                    val epubPackage = EpubPackage.read(archive)
                    val smilItems = epubPackage.readingOrder
                        .mapNotNull { item -> item.mediaOverlayId?.let(epubPackage.manifest::get) }
                        .distinctBy { item -> item.path }
                    val clips = smilItems.flatMap { smil ->
                        val bytes = archive.read(smil.path, MAX_TEXT_ENTRY_BYTES)
                        if (bytes == null) {
                            logSkipped()
                            emptyList()
                        } else {
                            smilParser.parseClips(bytes.decodeToString()).map { clip ->
                                TimedClip(
                                    textHref = resolvePath(smil.path, clip.textSrc),
                                    fragmentId = clip.textSrc.substringAfter('#', "")
                                        .ifEmpty { null },
                                    audioSrc = resolvePath(smil.path, clip.audioSrc),
                                    clipBeginMs = (clip.clipBegin * 1000).roundToLong(),
                                    clipEndMs = (clip.clipEnd * 1000).roundToLong(),
                                )
                            }
                        }
                    }
                    Ok(timingOf(clips))
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Err(AppError.UnknownError(exception))
            }
        }

    private fun logSkipped() {
        analytics.logBreadcrumb(
            DiagnosticContext(
                operation = "readaloud_timing_read",
                stage = "smil",
                outcome = "skipped",
                reasonCode = "entry_too_large",
            ),
        )
    }
}

/** Places audio files on one timeline in order of first use; each lasts until its last clip. */
internal fun timingOf(clips: List<TimedClip>): ReadaloudTiming {
    val durations = LinkedHashMap<String, Long>()
    clips.forEach { clip ->
        durations[clip.audioSrc] = maxOf(durations[clip.audioSrc] ?: 0L, clip.clipEndMs)
    }
    val offsets = LinkedHashMap<String, Long>()
    var total = 0L
    durations.forEach { (audioSrc, duration) ->
        offsets[audioSrc] = total
        total += duration
    }
    return ReadaloudTiming(clips = clips, audioFileOffsetsMs = offsets, totalDurationMs = total)
}
