package com.retro99.reader.ui.tts

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class TtsSynthesisPriorityGate {
    private val semaphore = Semaphore(1)
    suspend fun <T> run(preparation: Boolean, block: suspend () -> T): T = semaphore.withPermit { block() }
}
