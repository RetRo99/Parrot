package com.retro99.analytics.implementation

import android.content.Context
import com.retro99.analytics.api.FileLogger
import com.retro99.analytics.api.FileLogger.Companion.BACKUP_LOG_FILE_NAME
import com.retro99.analytics.api.FileLogger.Companion.LOG_FILE_NAME
import com.retro99.analytics.api.FileLogger.Companion.MAX_LOG_SIZE_BYTES
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.io.IOException
import kotlin.time.Clock

/**
 * Android implementation of FileLogger.
 * Writes logs to a file in the app's files directory.
 */
class AndroidFileLogger(
    private val context: Context,
) : FileLogger {

    private val logFile: File
        get() = File(context.filesDir, LOG_FILE_NAME)

    override fun logException(throwable: Throwable, message: String?) {
        val sanitizedThrowable = sanitizeDiagnosticThrowable(throwable)
        val sanitizedMessage = sanitizeDiagnosticMessage(message)
        val timestamp = getCurrentTimestamp()
        val logEntry = buildString {
            appendLine("[$timestamp] ERROR")
            if (!sanitizedMessage.isNullOrEmpty()) {
                appendLine("Message: $sanitizedMessage")
            }
            appendLine("Exception: ${sanitizedThrowable::class.simpleName}: ${sanitizedThrowable.message}")
            appendLine("Stack trace:")
            appendLine(sanitizedThrowable.stackTraceToString())
            appendLine("---")
        }
        appendToLogFile(logEntry)
    }

    override fun log(tag: String, message: String) {
        val timestamp = getCurrentTimestamp()
        val logEntry = "[$timestamp] [$tag] $message\n"
        appendToLogFile(logEntry)
    }

    override fun getLogFilePath(): String = logFile.absolutePath

    override fun clearLogs() {
        val backupFile = File(context.filesDir, BACKUP_LOG_FILE_NAME)
        clearDiagnosticLogFiles(
            paths = listOf(logFile.absolutePath, backupFile.absolutePath),
            exists = { path -> File(path).exists() },
            delete = { path -> File(path).delete() },
        )
    }

    override fun getLogContents(): String {
        if (!logFile.exists()) return ""
        return try {
            logFile.readText()
        } catch (error: Exception) {
            throw IOException("Diagnostic logs could not be read", error)
        }
    }

    private fun appendToLogFile(entry: String) {
        try {
            // Rotate log file if it gets too large
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                rotateLogFile()
            }
            logFile.appendText(entry)
        } catch (e: Exception) {
            // Silently fail - we don't want logging to crash the app
        }
    }

    private fun rotateLogFile() {
        try {
            val backupFile = File(context.filesDir, BACKUP_LOG_FILE_NAME)
            if (backupFile.exists()) {
                backupFile.delete()
            }
            logFile.renameTo(backupFile)
        } catch (e: Exception) {
            // If rotation fails, just clear the log
            logFile.delete()
        }
    }

    private fun getCurrentTimestamp(): String {
        val now = Clock.System.now()
        val localDateTime = now.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.date} ${localDateTime.hour.toString().padStart(2, '0')}:" +
            "${localDateTime.minute.toString().padStart(2, '0')}:" +
            "${localDateTime.second.toString().padStart(2, '0')}"
    }
}
