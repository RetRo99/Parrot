package com.retro99.analytics.implementation

import com.retro99.analytics.api.FileLogger
import com.retro99.analytics.api.FileLogger.Companion.BACKUP_LOG_FILE_NAME
import com.retro99.analytics.api.FileLogger.Companion.LOG_FILE_NAME
import com.retro99.analytics.api.FileLogger.Companion.MAX_LOG_SIZE_BYTES
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile
import kotlin.time.Clock

/**
 * iOS implementation of FileLogger.
 * Writes logs to a file in the app's documents directory.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileLogger : FileLogger {

    private val logFilePath: String
        get() {
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            )
            val documentsDir = paths.firstOrNull()?.toString() ?: ""
            return NSString.create(string = documentsDir).stringByAppendingPathComponent(LOG_FILE_NAME)
        }

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

    override fun getLogFilePath(): String = logFilePath

    override fun clearLogs() {
        val fileManager = NSFileManager.defaultManager
        val backupPath = backupLogFilePath()
        clearDiagnosticLogFiles(
            paths = listOf(logFilePath, backupPath),
            exists = { path -> fileManager.fileExistsAtPath(path) },
            delete = { path -> fileManager.removeItemAtPath(path, null) },
        )
    }

    override fun getLogContents(): String {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(logFilePath)) return ""
        return try {
            readFileContents(logFilePath)
        } catch (error: Exception) {
            throw IllegalStateException("Diagnostic logs could not be read", error)
        }
    }

    private fun readFileContents(path: String): String {
        val data = NSData.dataWithContentsOfFile(path)
            ?: error("Diagnostic logs could not be read")
        return NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            ?: error("Diagnostic logs could not be decoded")
    }

    private fun appendToLogFile(entry: String) {
        try {
            val fileManager = NSFileManager.defaultManager

            // Check file size and rotate if needed
            if (fileManager.fileExistsAtPath(logFilePath)) {
                val attributes = fileManager.attributesOfItemAtPath(logFilePath, null)
                val fileSize = (attributes?.get("NSFileSize") as? Long) ?: 0L
                if (fileSize > MAX_LOG_SIZE_BYTES) {
                    rotateLogFile()
                }
            }

            val existingContent = if (fileManager.fileExistsAtPath(logFilePath)) {
                readFileContents(logFilePath)
            } else {
                ""
            }

            val newContent = existingContent + entry
            NSString.create(string = newContent).writeToFile(
                logFilePath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null,
            )
        } catch (e: Exception) {
            // Silently fail - we don't want logging to crash the app
        }
    }

    private fun rotateLogFile() {
        try {
            val fileManager = NSFileManager.defaultManager
            val backupPath = backupLogFilePath()

            if (fileManager.fileExistsAtPath(backupPath)) {
                fileManager.removeItemAtPath(backupPath, null)
            }
            fileManager.moveItemAtPath(logFilePath, backupPath, null)
        } catch (e: Exception) {
            // If rotation fails, just clear the log
            val fileManager = NSFileManager.defaultManager
            fileManager.removeItemAtPath(logFilePath, null)
        }
    }

    private fun backupLogFilePath(): String = NSString.create(string = logFilePath.substringBeforeLast('/'))
        .stringByAppendingPathComponent(BACKUP_LOG_FILE_NAME)

    private fun getCurrentTimestamp(): String {
        val now = Clock.System.now()
        val localDateTime = now.toLocalDateTime(TimeZone.currentSystemDefault())
        return "${localDateTime.date} ${localDateTime.hour.toString().padStart(2, '0')}:" +
            "${localDateTime.minute.toString().padStart(2, '0')}:" +
            "${localDateTime.second.toString().padStart(2, '0')}"
    }
}
