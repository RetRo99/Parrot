package com.retro99.reader.ui.navigator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Owns cancellation and the bounded startup deadline for one pending TTS request. */
internal class TtsPlaybackOperationLifecycle(
    private val scope: CoroutineScope,
    private val startupTimeoutMs: Long,
    private val onStartupTimeout: () -> Unit,
) {
    private var requestJob: Job? = null
    private var startupTimeoutJob: Job? = null

    val hasStartupTimeout: Boolean
        get() = startupTimeoutJob?.isActive == true

    init {
        require(startupTimeoutMs > 0L)
    }

    fun launchRequest(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (requestJob === currentCoroutineContext()[Job]) {
                    requestJob = null
                }
            }
        }
        requestJob = job
        job.start()
    }

    fun cancelPendingRequest() {
        val job = requestJob
        requestJob = null
        job?.cancel()
    }

    fun armStartupTimeout() {
        cancelStartupTimeout()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(startupTimeoutMs)
            if (startupTimeoutJob === currentCoroutineContext()[Job]) {
                startupTimeoutJob = null
                onStartupTimeout()
            }
        }
        startupTimeoutJob = job
        job.start()
    }

    fun cancelStartupTimeout() {
        val job = startupTimeoutJob
        startupTimeoutJob = null
        job?.cancel()
    }
}
