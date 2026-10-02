package com.retro99.parrot

import com.retro99.base.AppInitializer
import com.retro99.reader.data.recap.RecapJobRunner
import com.retro99.reader.data.recap.RecapTrigger
import org.koin.core.annotation.Single
import kotlin.coroutines.cancellation.CancellationException

/**
 * Platform entry points that wake the recap job runner. App start and
 * sign-in are handled inside the runner; these add foreground,
 * connectivity and Android background work.
 */
object RecapTriggerBridge {
    val shared: RecapTriggerBridge = this

    private var runner: RecapJobRunner? = null

    internal fun install(runner: RecapJobRunner) {
        this.runner = runner
    }

    fun onForeground() {
        runner?.trigger(RecapTrigger.FOREGROUND)
    }

    fun onConnectivityRestored() {
        runner?.trigger(RecapTrigger.CONNECTIVITY)
    }

    /** One pass for background work; false when it should retry later. */
    suspend fun runPendingNow(): Boolean {
        val current = runner ?: return false
        return try {
            current.runPending()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}

@Single(binds = [AppInitializer::class])
class RecapTriggerInitializer(
    private val runner: RecapJobRunner,
) : AppInitializer {
    override fun initialize() {
        RecapTriggerBridge.install(runner)
    }
}
