package com.retro99.reader.data.recap

import com.retro99.base.AppInitializer

/**
 * App start: finish sessions a dead process left open, apply retention
 * and send what is due. Foreground and connectivity call [RecapJobRunner.trigger].
 */
class RecapStartupInitializer(
    private val recorder: RecapSessionRecorderImpl,
    private val runner: RecapJobRunner,
) : AppInitializer {
    override fun initialize() {
        runner.start(startupWork = recorder::recoverAbandoned)
    }
}
