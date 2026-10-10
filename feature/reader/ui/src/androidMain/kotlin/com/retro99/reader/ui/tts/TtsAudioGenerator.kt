package com.retro99.reader.ui.tts

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    prepared: TtsPreparedAudioStore,
    encoder: TtsPreparedAudioEncoder,
) : TtsSentenceAudioSource {

    private val core = TtsAudioGeneratorCore(synthesizer, cache.store, prepared.store, encoder)

    internal suspend fun prepareSentence(id: PreparedChapterId, text: String, voiceId: String?, rate: Float, pitch: Float): PreparedAudioEncoding =
        core.prepareSentence(id, text, voiceId, rate, pitch)

    /** The prepared-store key for one sentence, so a chapter's order can be planned up front. */
    internal fun preparedKey(text: String, voiceId: String?, rate: Float, pitch: Float): String =
        core.preparedKey(text, voiceId, rate, pitch)

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
    private val prepared: TtsPreparedStore? = null,
    private val encoder: TtsPreparedAudioEncoder? = null,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : TtsSentenceAudioSource {

    suspend fun prepareSentence(id: PreparedChapterId, text: String, voiceId: String?, rate: Float, pitch: Float): PreparedAudioEncoding {
        val store = prepared ?: return PreparedAudioEncoding.Failure
        val encoder = encoder ?: return PreparedAudioEncoding.Failure
        val effectiveRate = TtsSpeechRate.coerce(rate)
        val key = cacheKey(text, voiceId, effectiveRate, pitch)
        return withContext(ioContext) {
            store.lookup(id, key)?.let { return@withContext PreparedAudioEncoding.Success(it.file, it.durationMs) }
            store.lookup(key, markUsed = false)?.let { existing ->
                // Each chapter must remain independently packageable/deletable, even for repeated text.
                store.add(id, key, existing.file, existing.durationMs)
                val copied = checkNotNull(store.lookup(id, key))
                return@withContext PreparedAudioEncoding.Success(copied.file, copied.durationMs)
            }
            val generated = generate(text, voiceId, effectiveRate, pitch, preparation = true)
            val wav = generated.result.file
            if (generated.result.status != TtsSynthesisStatus.SUCCESS || wav == null) return@withContext PreparedAudioEncoding.Failure
            var temporary: File? = null
            try {
                val output = File.createTempFile("prepared-encode-", ".$PREPARED_AUDIO_EXTENSION", wav.parentFile)
                temporary = output
                check(output.delete()) // The atomic encoder never overwrites an existing destination.
                when (val encoded = encoder.encode(wav, output)) {
                    PreparedAudioEncoding.Failure -> PreparedAudioEncoding.Failure
                    is PreparedAudioEncoding.Success -> {
                        store.add(id, key, encoded.file, encoded.durationMs)
                        val stored = checkNotNull(store.lookup(id, key))
                        generated.privateWav?.let { privateWav ->
                            withSynthesisLock(key) {
                                if (cache.get(key) == null) {
                                    // The optional ordinary-cache copy is published only after encoding
                                    // succeeds. A failed background encode can never delete a live file.
                                    runCatching {
                                        Files.move(privateWav.toPath(), cache.fileFor(key).toPath(), StandardCopyOption.ATOMIC_MOVE)
                                        cache.onStored(cache.fileFor(key))
                                    }
                                }
                            }
                        }
                        PreparedAudioEncoding.Success(stored.file, stored.durationMs)
                    }
                }
            } finally {
                temporary?.delete()
                generated.privateWav?.delete()
            }
        }
    }

    internal fun preparedKey(text: String, voiceId: String?, rate: Float, pitch: Float): String =
        cacheKey(text, voiceId, TtsSpeechRate.coerce(rate), pitch)

    private val lockRegistryMutex = Mutex()
    private val synthesisLocks = mutableMapOf<String, LockEntry>()
    private val synthesisGate = TtsSynthesisPriorityGate()
    private data class GeneratedAudio(val result: TtsSynthesisResult, val privateWav: File? = null)

    override suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsSynthesisResult = generate(text, voiceId, rate, pitch, preparation = false).result

    private suspend fun generate(text: String, voiceId: String?, rate: Float, pitch: Float, preparation: Boolean): GeneratedAudio {
        val effectiveRate = TtsSpeechRate.coerce(rate)
        val key = cacheKey(text, voiceId, effectiveRate, pitch)
        return withContext(ioContext) {
            if (!preparation) prepared?.lookup(key)?.let { audio ->
                return@withContext GeneratedAudio(TtsSynthesisResult(TtsSynthesisStatus.SUCCESS, audio.file, durationMs = audio.durationMs))
            }
            withSynthesisLock(key) {
                cache.get(key)?.let { cachedFile ->
                    val durationMs = cache.durationMs(cachedFile)
                    return@withSynthesisLock GeneratedAudio(TtsSynthesisResult(
                        status = TtsSynthesisStatus.SUCCESS,
                        file = cachedFile,
                        durationMs = durationMs,
                    ))
                }

                val outputFile = if (preparation) {
                    File.createTempFile("prepared-synthesis-", ".wav", cache.fileFor(key).parentFile)
                } else cache.fileFor(key)
                val result = try {
                    synthesisGate.run(preparation) {
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
                    if (!preparation) cache.onStored(resultFile)
                    return@withSynthesisLock GeneratedAudio(result.copy(
                        durationMs = result.durationMs ?: cache.durationMs(resultFile),
                    ), if (preparation) resultFile else null)
                }
                // TTS-F03: the output file is the cache entry, so anything a failed or
                // cancelled synthesis left there would be served as a valid hit forever.
                // The rule holds here for every synthesizer, whatever each one deletes.
                outputFile.delete()
                GeneratedAudio(result)
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

}
