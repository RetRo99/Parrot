package com.retro99.reader.ui.media.smil

import com.retro99.analytics.api.Analytics
import com.retro99.base.nowMillis
import com.retro99.epub.api.SmilClip
import com.retro99.epub.implementation.smil.SmilParser
import com.retro99.reader.ui.di.ReaderScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * Callback interface for platform-specific SMIL file operations.
 *
 * Since file reading is platform-specific (Readium on Android, Swift on iOS),
 * the platform provides this callback to read SMIL content.
 */
interface SmilContentProvider {
    /**
     * Reads the content of a SMIL file.
     *
     * @param smilHref The href of the SMIL file
     * @return The file content as a string, or null if reading failed
     */
    suspend fun readSmilContent(smilHref: String): String?

    /**
     * Gets all SMIL file hrefs in the publication.
     *
     * @return List of SMIL file hrefs
     */
    fun getAllSmilHrefs(): List<String>

    /**
     * Gets the reading order (list of chapter hrefs in order).
     *
     * @return List of chapter hrefs in reading order
     */
    fun getReadingOrder(): List<String>

    /**
     * Resolves a relative path from a SMIL file to an absolute path.
     *
     * @param smilHref The SMIL file href
     * @param relativePath The relative path from the SMIL file
     * @return The resolved absolute path
     */
    fun resolveSmilPath(smilHref: String, relativePath: String): String
}

/**
 * Manages lazy loading of SMIL files with intelligent prefetching.
 *
 * Features:
 * - Hybrid index building (current + N chapters first, rest deferred)
 * - On-demand parsing with caching
 * - Background prefetching of next chapter
 * - No-eviction cache for session lifetime
 * - Cancelable prefetch when user jumps far
 *
 * @param smilParser Full SMIL parser for extracting clips
 * @param quickScanner Fast scanner for building chapter index
 * @param analytics Analytics for error logging
 */
@Scope(ReaderScope::class)
@Scoped
class SmilLoadingManager(
    @Provided private val smilParser: SmilParser,
    private val quickScanner: SmilQuickScanner,
    private val analytics: Analytics,
    private val index: SmilChapterIndex,
    private val cache: SmilClipCache,
    private val clipRepository: SmilClipRepository,
    @Provided private val contentProvider: SmilContentProvider,
) {
    private val ioDispatcher = Dispatchers.IO

    private var managerScope: CoroutineScope? = null
    private var prefetchJob: Job? = null

    // Number of chapters ahead to scan during initial index build
    private val initialScanAhead = 3

    // Timeout for scanning a single SMIL file (ms)
    private val scanTimeoutMs = 1000L

    // Timeout for parsing a single SMIL file (ms)
    private val parseTimeoutMs = 5000L

    /**
     * Initializes the manager with a coroutine scope for background operations.
     *
     * @param scope Coroutine scope for background operations
     */
    fun initialize(scope: CoroutineScope) {
        managerScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
    }

    /**
     * Builds the initial SMIL→chapter index for the current chapter and nearby chapters.
     *
     * This is a fast operation that only scans SMIL files until we find the ones
     * for the current chapter + N chapters ahead. Remaining SMIL files are scanned
     * on-demand when their chapters are accessed.
     *
     * @param currentChapterHref The current chapter href
     * @return Time taken in milliseconds
     */
    suspend fun buildInitialIndex(currentChapterHref: String): Long = withContext(ioDispatcher) {
        val startTime = currentTimeMillis()

        val allSmilHrefs = contentProvider.getAllSmilHrefs()

        // Scan ALL SMIL files to build complete index
        // This avoids slow fallback scans in getClipsForChapter later
        for (smilHref in allSmilHrefs) {
            if (index.isScanned(smilHref)) continue
            scanSmilFile(smilHref)
        }

        currentTimeMillis() - startTime
    }

    /**
     * Scans a single SMIL file to extract its chapter reference.
     *
     * @param smilHref The SMIL file href
     * @return The normalized chapter href, or null if not found
     */
    private suspend fun scanSmilFile(smilHref: String): String? {
        return try {
            val content = withTimeoutOrNull(scanTimeoutMs) {
                contentProvider.readSmilContent(smilHref)
            }

            if (content == null) {
                index.markFailed(smilHref)
                return null
            }

            val chapterHref = quickScanner.scanForChapterHref(content, smilHref)
            if (chapterHref != null) {
                val normalized = quickScanner.normalizeChapterHref(chapterHref)
                index.registerSmilForChapter(normalized, smilHref)
                normalized
            } else {
                index.markScanned(smilHref)
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analytics.logException(e, "Failed to scan SMIL file: $smilHref")
            index.markFailed(smilHref)
            null
        }
    }

    /**
     * Gets clips for a chapter, parsing SMIL files if needed.
     *
     * This is the main entry point for getting clips. It will:
     * 1. Return cached clips if available
     * 2. Parse SMIL files for the chapter if not cached
     * 3. Trigger fallback scan if no SMIL files are indexed for this chapter
     *
     * @param chapterHref The chapter href
     * @return List of clips for the chapter
     */
    suspend fun getClipsForChapter(chapterHref: String): List<SmilClip> =
        withContext(ioDispatcher) {
            val normalized = quickScanner.normalizeChapterHref(chapterHref)

            // Check cache first
            cache.getClips(normalized)?.let { cached ->
                return@withContext cached
            }

            // Try to claim parsing work
            if (!cache.tryClaimParsing(normalized)) {
                // Another coroutine is parsing, wait for it
                while (!cache.isParsed(normalized)) {
                    kotlinx.coroutines.delay(10)
                }
                return@withContext cache.getClips(normalized) ?: emptyList()
            }

            // Parse SMIL files for this chapter
            val clips = parseChapterSmilFiles(normalized)
            cache.putClips(normalized, clips)

            // Also store in global repository for service access
            // This allows clips to survive ReaderScope destruction
            clipRepository.storeClips(normalized, clips)

            clips
        }

    /**
     * Parses all SMIL files for a chapter.
     */
    private suspend fun parseChapterSmilFiles(normalizedChapterHref: String): List<SmilClip> {
        // Get SMIL files for this chapter from index
        var smilFiles = index.getSmilFilesForChapter(normalizedChapterHref)

        // If no SMIL files indexed, do a fallback scan
        if (smilFiles.isEmpty()) {
            smilFiles = fallbackScanForChapter(normalizedChapterHref)
        }

        if (smilFiles.isEmpty()) {
            return emptyList()
        }

        // Parse each SMIL file
        val allClips = mutableListOf<SmilClip>()
        for (smilHref in smilFiles) {
            val clips = parseSmilFile(smilHref)
            allClips.addAll(clips)
        }

        return allClips.sortedBy { it.clipBegin }
    }

    /**
     * Fallback scan: searches all unscanned SMIL files for ones referencing this chapter.
     */
    private suspend fun fallbackScanForChapter(normalizedChapterHref: String): List<String> {
        val allSmilHrefs = contentProvider.getAllSmilHrefs()
        val unscanned = index.getUnscannedSmilFiles(allSmilHrefs)

        for (smilHref in unscanned) {
            scanSmilFile(smilHref)
        }

        return index.getSmilFilesForChapter(normalizedChapterHref)
    }

    /**
     * Parses a single SMIL file and returns its clips with resolved paths.
     *
     * The SMIL file contains relative paths (e.g., "../Audio/00001.mp4").
     * This method resolves them to absolute paths relative to the publication root
     * (e.g., "OPS/Audio/00001.mp4") so they can be used directly by the player.
     */
    private suspend fun parseSmilFile(smilHref: String): List<SmilClip> {
        return try {
            val content = withTimeoutOrNull(parseTimeoutMs) {
                contentProvider.readSmilContent(smilHref)
            } ?: return emptyList()

            val rawClips = smilParser.parseClips(content)

            // Resolve relative paths to absolute paths using the SMIL file location
            rawClips.map { clip ->
                SmilClip(
                    textSrc = contentProvider.resolveSmilPath(smilHref, clip.textSrc),
                    audioSrc = contentProvider.resolveSmilPath(smilHref, clip.audioSrc),
                    clipBegin = clip.clipBegin,
                    clipEnd = clip.clipEnd,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            analytics.logException(e, "Failed to parse SMIL file: $smilHref")
            emptyList()
        }
    }

    /**
     * Finds the first chapter with audio starting from the given chapter (inclusive).
     *
     * This is useful when the user clicks play on a chapter that may not have audio
     * (e.g., cover page, table of contents). Instead of failing, we find the first
     * chapter that actually has audio and play that instead.
     *
     * @param chapterHref The chapter href to start searching from (inclusive)
     * @return The first chapter href that has audio, or null if none found
     */
    suspend fun findChapterWithAudio(chapterHref: String): String? {
        val normalized = quickScanner.normalizeChapterHref(chapterHref)
        val readingOrder = contentProvider.getReadingOrder()
            .map { quickScanner.normalizeChapterHref(it) }

        val currentIndex = readingOrder.indexOf(normalized)
        if (currentIndex < 0) {
            return null
        }

        // Ensure all SMIL files are scanned so we know which chapters have audio
        ensureAllSmilFilesScanned()

        // Find the first chapter with audio starting from current (inclusive)
        for (i in currentIndex until readingOrder.size) {
            val chapter = readingOrder[i]
            if (index.hasSmilForChapter(chapter)) {
                return chapter
            }
        }

        return null
    }

    /**
     * Finds the next chapter in the reading order that has audio (SMIL files).
     *
     * This is useful when the current chapter (e.g., cover page) doesn't have audio,
     * so we can prepare the next chapter that does.
     *
     * @param currentChapterHref The current chapter href
     * @return The next chapter href that has audio, or null if none found
     */
    suspend fun findNextChapterWithAudio(currentChapterHref: String): String? {
        val normalized = quickScanner.normalizeChapterHref(currentChapterHref)
        val readingOrder = contentProvider.getReadingOrder()
            .map { quickScanner.normalizeChapterHref(it) }

        val currentIndex = readingOrder.indexOf(normalized)
        if (currentIndex < 0) {
            return null
        }

        // Ensure all SMIL files are scanned so we know which chapters have audio
        ensureAllSmilFilesScanned()

        // Find the next chapter with audio (exclusive of current)
        for (i in (currentIndex + 1) until readingOrder.size) {
            val chapter = readingOrder[i]
            if (index.hasSmilForChapter(chapter)) {
                return chapter
            }
        }

        return null
    }

    /**
     * Finds the previous chapter in the reading order that has audio (SMIL files).
     *
     * This is useful for chapter navigation from media controls.
     *
     * @param currentChapterHref The current chapter href
     * @return The previous chapter href that has audio, or null if none found
     */
    suspend fun findPreviousChapterWithAudio(currentChapterHref: String): String? {
        val normalized = quickScanner.normalizeChapterHref(currentChapterHref)
        val readingOrder = contentProvider.getReadingOrder()
            .map { quickScanner.normalizeChapterHref(it) }

        val currentIndex = readingOrder.indexOf(normalized)
        if (currentIndex <= 0) {
            return null
        }

        // Ensure all SMIL files are scanned so we know which chapters have audio
        ensureAllSmilFilesScanned()

        // Find the previous chapter with audio (exclusive of current, searching backwards)
        for (i in (currentIndex - 1) downTo 0) {
            val chapter = readingOrder[i]
            if (index.hasSmilForChapter(chapter)) {
                return chapter
            }
        }

        return null
    }

    /**
     * Ensures all SMIL files have been scanned so we know which chapters have audio.
     */
    private suspend fun ensureAllSmilFilesScanned() {
        val allSmilHrefs = contentProvider.getAllSmilHrefs()
        val unscanned = index.getUnscannedSmilFiles(allSmilHrefs)
        if (unscanned.isNotEmpty()) {
            for (smilHref in unscanned) {
                scanSmilFile(smilHref)
            }
        }
    }

    /**
     * Prefetches clips for the next chapter in background.
     *
     * This should be called after preparing the current chapter.
     * The prefetch is cancelable if the user navigates away.
     *
     * @param currentChapterHref The current chapter href
     */
    fun prefetchNextChapter(currentChapterHref: String) {
        val scope = managerScope ?: return

        // Cancel any existing prefetch
        prefetchJob?.cancel()

        prefetchJob = scope.launch(ioDispatcher) {
            try {
                val normalized = quickScanner.normalizeChapterHref(currentChapterHref)
                val readingOrder = contentProvider.getReadingOrder()
                    .map { quickScanner.normalizeChapterHref(it) }

                val currentIndex = readingOrder.indexOf(normalized)
                if (currentIndex < 0 || currentIndex >= readingOrder.size - 1) {
                    return@launch
                }

                val nextChapter = readingOrder[currentIndex + 1]

                // Skip if already cached
                if (cache.isParsed(nextChapter)) {
                    return@launch
                }

                getClipsForChapter(nextChapter)
            } catch (e: CancellationException) {
                // Prefetch was cancelled, this is expected
            } catch (e: Exception) {
                analytics.logException(e, "Prefetch failed for chapter after: $currentChapterHref")
            }
        }
    }

    /**
     * Gets all cached clips across all parsed chapters.
     *
     * @return All cached clips
     */
    suspend fun getAllCachedClips(): List<SmilClip> = cache.getAllClips()

    /**
     * Gets diagnostics information.
     *
     * @return Diagnostic string with cache and index stats
     */
    suspend fun getDiagnostics(): String {
        val (parsedChapters, totalClips) = cache.getStats()
        val (indexedChapters, scannedSmils, failedSmils) = index.getStats()
        return "Cache: $parsedChapters chapters, $totalClips clips | " +
                "Index: $indexedChapters chapters, $scannedSmils scanned, $failedSmils failed"
    }

    /**
     * Releases all resources and clears caches.
     */
    fun release() {
        prefetchJob?.cancel()
        prefetchJob = null
        managerScope?.cancel()
        managerScope = null
        cache.clear()
        index.clear()
    }

    private fun currentTimeMillis(): Long = nowMillis()
}
