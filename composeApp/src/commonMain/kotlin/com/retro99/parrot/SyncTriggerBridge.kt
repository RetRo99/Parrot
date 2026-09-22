package com.retro99.parrot

import com.retro99.base.AppInitializer
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/** Platform-callable entry points for app lifecycle and connectivity events. */
object SyncTriggerBridge {
    val shared: SyncTriggerBridge = this

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncNowUseCase: SyncNowUseCase? = null

    internal fun install(syncNowUseCase: SyncNowUseCase) {
        this.syncNowUseCase = syncNowUseCase
    }

    fun onStartup() = request(SyncTriggerReason.STARTUP)

    fun onForeground() = request(SyncTriggerReason.LIFECYCLE)

    fun onBackground() = request(
        reason = SyncTriggerReason.LIFECYCLE,
        urgency = SyncUrgency.URGENT,
    )

    fun onConnectivityRestored() = request(SyncTriggerReason.CONNECTIVITY)

    private fun request(
        reason: SyncTriggerReason,
        urgency: SyncUrgency = SyncUrgency.ROUTINE,
    ) {
        val useCase = syncNowUseCase ?: return
        scope.launch {
            useCase(
                SyncRequest(
                    reason = reason,
                    urgency = urgency,
                ),
            )
        }
    }
}

@Single(binds = [AppInitializer::class])
class SyncTriggerInitializer(
    private val syncNowUseCase: SyncNowUseCase,
) : AppInitializer {
    override fun initialize() {
        SyncTriggerBridge.install(syncNowUseCase)
    }
}
