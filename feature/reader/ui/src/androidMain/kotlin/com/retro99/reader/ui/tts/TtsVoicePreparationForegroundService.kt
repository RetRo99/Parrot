package com.retro99.reader.ui.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.retro99.translations.R as TranslationsR

class TtsVoicePreparationForegroundService : Service() {

    private val synthesizer: TtsSynthesizer by inject()
    private val stateHolder: TtsVoicePreparationStateHolder by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preparationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                val voiceId = intent.getStringExtra(EXTRA_VOICE_ID)
                    ?: return START_NOT_STICKY
                val updateToLatest = intent.getBooleanExtra(EXTRA_UPDATE_TO_LATEST, false)
                startInForeground()
                if (preparationJob?.isActive != true) {
                    startPreparation(voiceId, updateToLatest)
                }
            }

            ACTION_CANCEL -> preparationJob?.cancel()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val state = stateHolder.state.value
        val progress = (state as? TtsVoicePreparationState.Running)?.progress
            ?: TtsPreparationProgress.Finalizing
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(progress),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun startPreparation(voiceId: String, updateToLatest: Boolean) {
        val voicePackage = voiceId.neuralVoicePackage()
        if (voicePackage == null) {
            finishPreparation()
            return
        }
        preparationJob = serviceScope.launch {
            try {
                val prepared = synthesizer.prepareVoice(voiceId, updateToLatest) { progress ->
                    stateHolder.updateProgress(voicePackage, progress)
                    updateNotification(progress)
                }
                if (prepared) {
                    stateHolder.markComplete(voicePackage)
                } else {
                    stateHolder.markFailed(voicePackage)
                }
            } catch (error: CancellationException) {
                stateHolder.markIdle(voicePackage)
            } catch (error: Exception) {
                Log.e(TAG, "Neural voice preparation failed", error)
                stateHolder.markFailed(voicePackage)
            } finally {
                finishPreparation()
            }
        }
    }

    private fun finishPreparation() {
        preparationJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun updateNotification(progress: TtsPreparationProgress) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification(progress),
        )
    }

    private fun createNotification(progress: TtsPreparationProgress): Notification {
        val cancelPendingIntent = PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            createCancelIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(TranslationsR.string.tts_voice_preparation_notification_title),
            )
            .setContentText(progress.toNotificationText())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(TranslationsR.string.tts_voice_preparation_notification_cancel),
                cancelPendingIntent,
            )

        val percentage = when (progress) {
            is TtsPreparationProgress.Downloading -> progress.percentage
            TtsPreparationProgress.Finalizing -> null
        }
        if (percentage == null) {
            notification.setProgress(0, 0, true)
        } else {
            notification.setProgress(100, percentage, false)
        }
        return notification.build()
    }

    private fun TtsPreparationProgress.toNotificationText(): String = when (this) {
        is TtsPreparationProgress.Downloading -> {
            val total = totalBytes
            val progressPercentage = percentage
            if (total != null && progressPercentage != null) {
                getString(
                    TranslationsR.string.tts_voice_preparation_notification_downloading,
                    progressPercentage,
                    downloadedBytes.toMegabytes(),
                    total.toMegabytes(),
                )
            } else {
                getString(
                    TranslationsR.string.tts_voice_preparation_notification_downloading_unknown,
                    downloadedBytes.toMegabytes(),
                )
            }
        }

        TtsPreparationProgress.Finalizing -> getString(
            TranslationsR.string.tts_voice_preparation_notification_finalizing,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(TranslationsR.string.tts_voice_preparation_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(
                TranslationsR.string.tts_voice_preparation_channel_description,
            )
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "neural_voice_preparation"
        private const val NOTIFICATION_ID = 2002
        private const val CANCEL_REQUEST_CODE = 2002
        private const val TAG = "TtsVoicePreparation"

        private const val ACTION_PREPARE = "com.retro99.reader.PREPARE_NEURAL_VOICES"
        private const val ACTION_CANCEL = "com.retro99.reader.CANCEL_NEURAL_VOICES"
        private const val EXTRA_VOICE_ID = "voice_id"
        private const val EXTRA_UPDATE_TO_LATEST = "update_to_latest"

        fun createStartIntent(
            context: Context,
            voiceId: String,
            updateToLatest: Boolean = false,
        ): Intent =
            Intent(context, TtsVoicePreparationForegroundService::class.java).apply {
                action = ACTION_PREPARE
                putExtra(EXTRA_VOICE_ID, voiceId)
                putExtra(EXTRA_UPDATE_TO_LATEST, updateToLatest)
            }

        private fun createCancelIntent(context: Context): Intent =
            Intent(context, TtsVoicePreparationForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
    }
}

private fun Long.toMegabytes(): Long = this / 1_000_000L
