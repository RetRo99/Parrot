package com.retro99.reader.ui.tts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.koin.core.annotation.Single

@Single
class TtsAudioGenerator(
    private val synthesizer: TtsSynthesizer,
    private val cache: TtsAudioCache,
) {

    private val lockRegistryMutex = Mutex()
    private val synthesisLocks = mutableMapOf<String, LockEntry>()
    private val synthesisSemaphore = Semaphore(MAX_CONCURRENT_SYNTHESIS)

    suspend fun synthesize(
        text: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): TtsSynthesisResult {
        val effectiveRate = TtsSpeechRate.coerce(rate)
        val key = cache.key(voiceId.orEmpty(), effectiveRate, pitch, text)
        return withSynthesisLock(key) {
            cache.get(key)?.let { cachedFile ->
                return@withSynthesisLock TtsSynthesisResult(
                    status = TtsSynthesisStatus.SUCCESS,
                    file = cachedFile,
                    durationMs = cache.durationMs(cachedFile),
                )
            }

            val outputFile = cache.fileFor(key)
            val result = synthesisSemaphore.withPermit {
                synthesizer.synthesize(
                    text = text,
                    voiceId = voiceId,
                    rate = effectiveRate,
                    pitch = pitch,
                    outputFile = outputFile,
                )
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
            result
        }
    }

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
