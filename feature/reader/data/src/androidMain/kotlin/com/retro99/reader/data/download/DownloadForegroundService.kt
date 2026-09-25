package com.retro99.reader.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.retro99.analytics.api.Analytics
import com.retro99.base.result.log
import com.retro99.books.domain.model.BookType
import com.retro99.reader.data.source.EbookFileDownloader
import com.retro99.reader.data.source.isMultiFileDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Foreground service for downloading ebooks.
 *
 * This service runs downloads in the foreground with a notification,
 * ensuring downloads continue even when the app is killed.
 */
class DownloadForegroundService : Service() {

    private data class PendingDownload(
        val bookUuid: String,
        val bookType: BookType,
        val filePath: String,
        val bookTitle: String,
        val serverId: String,
    )

    private val fileDownloader: EbookFileDownloader by inject()
    private val downloadStateHolder: DownloadStateHolder by inject()
    private val analytics: Analytics by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobsLock = Any()
    private val activeJobs = mutableMapOf<String, Job>()
    private val cancellingKeys = mutableSetOf<String>()
    private val pendingDownloads = mutableMapOf<String, PendingDownload>()
    private val startingKeys = mutableSetOf<String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestStartId: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val bookUuid = intent.getStringExtra(EXTRA_BOOK_UUID) ?: return START_NOT_STICKY
                val bookTypeValue =
                    intent.getStringExtra(EXTRA_BOOK_TYPE) ?: return START_NOT_STICKY
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
                val bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: "Book"
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return START_NOT_STICKY
                val bookType = BookType.entries.find { it.value == bookTypeValue }
                    ?: return START_NOT_STICKY

                startForegroundWithNotification(bookUuid, bookType, bookTitle)
                startDownload(bookUuid, bookType, filePath, bookTitle, serverId)
            }

            ACTION_CANCEL_DOWNLOAD -> {
                val bookUuid = intent.getStringExtra(EXTRA_BOOK_UUID) ?: return START_NOT_STICKY
                val bookTypeValue =
                    intent.getStringExtra(EXTRA_BOOK_TYPE) ?: return START_NOT_STICKY
                val bookType = BookType.entries.find { it.value == bookTypeValue }
                    ?: return START_NOT_STICKY

                cancelDownload(bookUuid, bookType)
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(
        bookUuid: String,
        bookType: BookType,
        bookTitle: String,
    ) {
        val notification = createNotification(
            bookUuid = bookUuid,
            bookType = bookType,
            bookTitle = bookTitle,
            progress = 0,
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun startDownload(
        bookUuid: String,
        bookType: BookType,
        filePath: String,
        bookTitle: String,
        serverId: String,
    ) {
        val key = "$bookUuid:${bookType.value}"
        val request = PendingDownload(bookUuid, bookType, filePath, bookTitle, serverId)
        val isMultiFile = filePath.isMultiFileDownload()

        var activeJob: Job? = null
        val job = synchronized(activeJobsLock) {
            when {
                key in cancellingKeys -> {
                    pendingDownloads[key] = request
                    null
                }
                key in activeJobs -> null
                else -> {
                    val createdJob = serviceScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            downloadStateHolder.clearCancelledState(bookUuid, bookType)
                            downloadStateHolder.updateProgress(bookUuid, bookType, null)

                            fileDownloader.downloadEbookWithProgress(
                                ebookFilePath = filePath,
                                bookUuid = bookUuid,
                                bookType = bookType,
                                serverId = serverId,
                                onProgress = { bytesDownloaded, totalBytes ->
                                    val progress = if (totalBytes != null && totalBytes > 0) {
                                        (bytesDownloaded.toFloat() / totalBytes.toFloat())
                                            .coerceIn(0f, 1f)
                                    } else {
                                        null
                                    }
                                    downloadStateHolder.updateProgress(bookUuid, bookType, progress)
                                    updateNotification(
                                        bookUuid = bookUuid,
                                        bookType = bookType,
                                        bookTitle = bookTitle,
                                        progress = progress,
                                    )
                                },
                            ).onSuccess {
                                downloadStateHolder.markCached(bookUuid, bookType)
                            }.onFailure { error ->
                                error.log(
                                    analytics,
                                    "DownloadForegroundService: Download failed for book=" +
                                        "$bookUuid, type=$bookType",
                                )
                                downloadStateHolder.markFailed(bookUuid, bookType, error)
                            }
                        } finally {
                            val wasCancelled = !currentCoroutineContext().isActive
                            withContext(NonCancellable) {
                                if (wasCancelled) {
                                    if (!isMultiFile) {
                                        fileDownloader.deleteEbookCache(bookUuid, bookType)
                                    }
                                    downloadStateHolder.markIdle(bookUuid, bookType)
                                }

                                val pendingRequest = synchronized(activeJobsLock) {
                                    if (activeJobs[key] === activeJob) {
                                        activeJobs.remove(key)
                                    }
                                    val pending = if (cancellingKeys.remove(key)) {
                                        pendingDownloads.remove(key)
                                    } else {
                                        null
                                    }
                                    if (pending != null) startingKeys.add(key)
                                    pending
                                }
                                pendingRequest?.let { pending ->
                                    startReservedDownload(key, pending)
                                }
                                stopSelfIfNoActiveDownloads()
                            }
                        }
                    }
                    activeJob = createdJob
                    activeJobs[key] = createdJob
                    createdJob
                }
            }
        }
        job?.start()
    }

    private fun cancelDownload(bookUuid: String, bookType: BookType) {
        val key = "$bookUuid:${bookType.value}"
        val (activeJob, cleanWithoutJob) = synchronized(activeJobsLock) {
            val job = activeJobs[key]
            if (job == null) {
                null to cancellingKeys.add(key)
            } else {
                cancellingKeys.add(key)
                job to false
            }
        }
        if (activeJob != null) {
            activeJob.cancel()
        } else if (cleanWithoutJob) {
            serviceScope.launch {
                withContext(NonCancellable) {
                    downloadStateHolder.markIdle(bookUuid, bookType)
                    val pendingRequest = synchronized(activeJobsLock) {
                        val pending = if (cancellingKeys.remove(key)) {
                            pendingDownloads.remove(key)
                        } else {
                            null
                        }
                        if (pending != null) startingKeys.add(key)
                        pending
                    }
                    pendingRequest?.let { pending ->
                        startReservedDownload(key, pending)
                    }
                    stopSelfIfNoActiveDownloads()
                }
            }
        }
    }

    private fun startReservedDownload(key: String, request: PendingDownload) {
        try {
            startDownload(
                request.bookUuid,
                request.bookType,
                request.filePath,
                request.bookTitle,
                request.serverId,
            )
        } finally {
            synchronized(activeJobsLock) { startingKeys.remove(key) }
        }
    }

    private fun stopSelfIfNoActiveDownloads() {
        mainHandler.post {
            val canStop = synchronized(activeJobsLock) {
                activeJobs.isEmpty() && cancellingKeys.isEmpty() &&
                    pendingDownloads.isEmpty() && startingKeys.isEmpty()
            }
            if (!canStop || !stopSelfResult(latestStartId)) return@post

            stopForeground(STOP_FOREGROUND_REMOVE)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private fun updateNotification(
        bookUuid: String,
        bookType: BookType,
        bookTitle: String,
        progress: Float?,
    ) {
        val key = "$bookUuid:${bookType.value}"
        // Don't update notification if download was cancelled
        if (!synchronized(activeJobsLock) { activeJobs.containsKey(key) }) return

        val notification = createNotification(
            bookUuid = bookUuid,
            bookType = bookType,
            bookTitle = bookTitle,
            progress = ((progress ?: 0f) * 100).toInt(),
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(
        bookUuid: String,
        bookType: BookType,
        bookTitle: String,
        progress: Int,
    ): Notification {
        val cancelIntent = createCancelIntent(this, bookUuid, bookType)
        val cancelPendingIntent = PendingIntent.getService(
            this,
            bookUuid.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading: $bookTitle")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Book Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows download progress for books"
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "book_downloads"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_DOWNLOAD = "com.retro99.reader.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.retro99.reader.CANCEL_DOWNLOAD"
        const val EXTRA_BOOK_UUID = "book_uuid"
        const val EXTRA_BOOK_TYPE = "book_type"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_BOOK_TITLE = "book_title"
        const val EXTRA_SERVER_ID = "server_id"

        fun createStartIntent(
            context: Context,
            bookUuid: String,
            bookType: BookType,
            filePath: String,
            bookTitle: String,
            serverId: String,
        ): Intent {
            return Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_BOOK_UUID, bookUuid)
                putExtra(EXTRA_BOOK_TYPE, bookType.value)
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_BOOK_TITLE, bookTitle)
                putExtra(EXTRA_SERVER_ID, serverId)
            }
        }

        fun createCancelIntent(
            context: Context,
            bookUuid: String,
            bookType: BookType,
        ): Intent {
            return Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_BOOK_UUID, bookUuid)
                putExtra(EXTRA_BOOK_TYPE, bookType.value)
            }
        }
    }
}
