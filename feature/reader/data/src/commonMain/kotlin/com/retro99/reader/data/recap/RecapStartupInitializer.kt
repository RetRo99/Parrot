package com.retro99.reader.data.recap

import com.retro99.base.AppInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App start: finish sessions a dead process left open, apply retention
 * and send what is due. Foreground and connectivity call [RecapJobRunner.trigger].
 */
class RecapStartupInitializer(
    private val recorder: RecapSessionRecorderImpl,
    private val runner: RecapJobRunner,
) : AppInitializer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun initialize() {
        runner.start()
        scope.launch { recorder.recoverAbandoned() }
    }
}
