package com.retro99.parrot.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.retro99.sync.domain.SyncRequest
import com.retro99.sync.domain.SyncResult
import com.retro99.sync.domain.SyncTriggerReason
import com.retro99.sync.domain.SyncUrgency
import com.retro99.sync.domain.usecase.SyncNowUseCase
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Recovers durable sync work after process death or deferred background execution. */
class SyncRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams), KoinComponent {
    private val syncNowUseCase: SyncNowUseCase by inject()

    override suspend fun doWork(): Result {
        return try {
            when (
                syncNowUseCase(
                    SyncRequest(
                        reason = SyncTriggerReason.RECOVERY,
                        urgency = SyncUrgency.ROUTINE,
                    ),
                )
            ) {
                is SyncResult.Failed -> Result.retry()
                else -> Result.success()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.retry()
        }
    }
}

object SyncWorkScheduler {
    private const val UNIQUE_WORK_NAME = "parrot-sync-recovery"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<SyncRecoveryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
