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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.retro99.translations.R as TranslationsR

/**
 * Keeps the chapter preparation alive while the user leaves the reader, and shows its
 * progress with a Cancel action. The work itself belongs to [TtsChapterPreparationJob];
 * this service only watches its state. The notification names no book and no chapter.
 */
class TtsChapterPreparationForegroundService : Service() {

    private val job: TtsChapterPreparationJob by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> {
                startInForeground()
                if (watcher?.isActive != true) watcher = watchPreparation()
            }

            ACTION_CANCEL -> job.cancel()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createNotification(job.state.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun watchPreparation(): Job = serviceScope.launch {
        job.state.collectLatest { state ->
            if (state is TtsChapterPreparationState.Running) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, createNotification(state))
            } else {
                finish()
            }
        }
    }

    private fun finish() {
        watcher?.cancel()
        watcher = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun createNotification(state: TtsChapterPreparationState): Notification {
        val running = state as? TtsChapterPreparationState.Running
        val cancelPendingIntent = PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            createCancelIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(TranslationsR.string.tts_chapter_preparation_notification_title))
            .setContentText(progressText(running))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(TranslationsR.string.tts_chapter_preparation_notification_cancel),
                cancelPendingIntent,
            )
            .apply {
                if (running == null || running.total <= 0) {
                    setProgress(0, 0, true)
                } else {
                    setProgress(running.total, running.done, false)
                }
            }
            .build()
    }

    private fun progressText(running: TtsChapterPreparationState.Running?): String {
        val text = chapterPreparationNotificationText(running)
        val resource = when (text.line) {
            TtsChapterPreparationNotificationLine.COUNT ->
                TranslationsR.string.tts_chapter_preparation_notification_progress
            TtsChapterPreparationNotificationLine.WAITING ->
                TranslationsR.string.tts_chapter_preparation_notification_progress_waiting
            TtsChapterPreparationNotificationLine.TIME_LEFT ->
                TranslationsR.string.tts_chapter_preparation_notification_progress_left
            TtsChapterPreparationNotificationLine.TIME_LEFT_SHORT ->
                TranslationsR.string.tts_chapter_preparation_notification_progress_left_short
            TtsChapterPreparationNotificationLine.TIME_LEFT_HOURS ->
                TranslationsR.string.tts_chapter_preparation_notification_progress_left_hours
            TtsChapterPreparationNotificationLine.CANCELLING ->
                TranslationsR.string.tts_chapter_preparation_notification_cancelling
        }
        return getString(resource, *text.args.toTypedArray())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(TranslationsR.string.tts_chapter_preparation_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(TranslationsR.string.tts_chapter_preparation_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "chapter_audio_preparation"
        private const val NOTIFICATION_ID = 2003
        private const val CANCEL_REQUEST_CODE = 2003

        private const val ACTION_PREPARE = "com.retro99.reader.PREPARE_CHAPTER_AUDIO"
        private const val ACTION_CANCEL = "com.retro99.reader.CANCEL_CHAPTER_AUDIO"

        fun createStartIntent(context: Context): Intent =
            Intent(context, TtsChapterPreparationForegroundService::class.java).apply {
                action = ACTION_PREPARE
            }

        private fun createCancelIntent(context: Context): Intent =
            Intent(context, TtsChapterPreparationForegroundService::class.java).apply {
                action = ACTION_CANCEL
            }
    }
}
