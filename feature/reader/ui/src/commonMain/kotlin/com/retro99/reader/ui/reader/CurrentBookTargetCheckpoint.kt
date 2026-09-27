package com.retro99.reader.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs one durable current-book checkpoint after a qualifying active reading interval. */
internal class CurrentBookTargetCheckpoint(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val onCheckpointDue: () -> Unit,
) {
    private var started = false
    private var job: Job? = null

    fun start() {
        if (started) return
        started = true
        job = scope.launch {
            delay(delayMillis)
            onCheckpointDue()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
