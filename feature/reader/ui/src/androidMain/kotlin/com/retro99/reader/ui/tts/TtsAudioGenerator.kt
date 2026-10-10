package com.retro99.reader.ui.tts

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * The one synthesis call read-aloud makes. Lets a host test drive synthesis outcomes:
 * [TtsAudioGenerator] itself needs [TtsAudioCache], which needs an Android `Context`.
 */
interface TtsSentenceAudioSource {

    suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsSynthesisResult
}

@Single(binds = [TtsAudioGenerator::class, TtsSentenceAudioSource::class])
class TtsAudioGenerator(
    synthesizer: TtsSynthesizer,
    cache: TtsAudioCache,
) : TtsSentenceAudioSource {

    private val core = TtsAudioGeneratorCore(synthesizer = synthesizer, cache = cache.store)

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsSynthesisResult = core.synthesize(text = text, voiceId = voiceId, rate = rate, pitch = pitch)

    /** The cached WAV for identical text and parameters, or null. Skips the synthesis gate. */
    fun findCached(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): File? = core.findCached(text = text, voiceId = voiceId, rate = rate, pitch = pitch)
}

/**
 * The generation logic, with the cache as the Context-free [TtsAudioCacheStore], so a host
 * test can drive synthesis outcomes against a real cache directory.
 */
internal class TtsAudioGeneratorCore(
    private val synthesizer: TtsSynthesizer,
    private val cache: TtsAudioCacheStore,
) : TtsSentenceAudioSource {

    private val lockRegistryMutex = Mutex()
    private val synthesisLocks = mutableMapOf<String, LockEntry>()
    private val synthesisSemaphore = Semaphore(MAX_CONCURRENT_SYNTHESIS)

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsSynthesisResult {
        val effectiveRate = TtsSpeechRate.coerce(rate)
        val key = cacheKey(text, voiceId, effectiveRate, pitch)
        return withContext(Dispatchers.IO) {
            withSynthesisLock(key) {
                cache.get(key)?.let { cachedFile ->
                    val durationMs = cache.durationMs(cachedFile)
                    return@withSynthesisLock TtsSynthesisResult(
                        status = TtsSynthesisStatus.SUCCESS,
                        file = cachedFile,
                        durationMs = durationMs,
                    )
                }

                val outputFile = cache.fileFor(key)
                val result = try {
                    synthesisSemaphore.withPermit {
                        synthesizer.synthesize(
                            text = text,
                            voiceId = voiceId,
                            rate = effectiveRate,
                            pitch = pitch,
                            outputFile = outputFile,
                        )
                    }
                } catch (error: Throwable) {
                    outputFile.delete()
                    throw error
                }
                val resultFile = result.file
                if (
                    result.status == TtsSynthesisStatus.SUCCESS &&
                    resultFile != null &&
                    resultFile.exists()
                ) {
                    cache.onStored(resultFile)
                    return@withSynthesisLock result.copy(
                        durationMs = result.durationMs ?: cache.durationMs(resultFile),
                    )
                }
                // TTS-F03: the output file is the cache entry, so anything a failed or
                // cancelled synthesis left there would be served as a valid hit forever.
                // The rule holds here for every synthesizer, whatever each one deletes.
                outputFile.delete()
                result
            }
        }
    }

    fun findCached(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): File? = cache.get(cacheKey(text, voiceId, TtsSpeechRate.coerce(rate), pitch))

    private fun cacheKey(text: String, voiceId: String?, rate: Float, pitch: Float): String =
        cache.key(
            voiceId = voiceId.orEmpty(),
            modelVersion = synthesizer.activeModelVersion(voiceId),
            rate = rate,
            pitch = pitch,
            text = text,
        )

    private suspend fun <T> withSynthesisLock(key: String, block: suspend () -> T): T {
        val entry = lockRegistryMutex.withLock {
            synthesisLocks.getOrPut(key) { LockEntry(Mutex()) }.also { lockEntry ->
                lockEntry.users++
            }
        }

        return try {
            entry.mutex.withLock { block() }
        } finally {
            lockRegistryMutex.withLock {
                entry.users--
                if (entry.users == 0) {
                    synthesisLocks.remove(key)
                }
            }
        }
    }

    private data class LockEntry(
        val mutex: Mutex,
        var users: Int = 0,
    )

    private companion object {
        const val MAX_CONCURRENT_SYNTHESIS = 1
    }
}
