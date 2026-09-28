package com.retro99.analytics.api

/**
 * Interface for logging to a file on disk.
 * Allows users to share log files for debugging purposes.
 */
interface FileLogger {

    companion object {
        const val LOG_FILE_NAME = "parrot_logs.txt"
        const val BACKUP_LOG_FILE_NAME = "parrot_logs_old.txt"
        const val MAX_LOG_SIZE_BYTES = 9.9 * 1024 * 1024L // 9.9MB max log file size
    }

    /**
     * Logs an exception with an optional context message to the log file.
     *
     * @param throwable The exception to log
     * @param message Optional context message
     */
    fun logException(throwable: Throwable, message: String?)

    /**
     * Logs a general message to the log file.
     *
     * @param tag The tag/category for the log entry
     * @param message The message to log
     */
    fun log(tag: String, message: String)

    /**
     * Gets the path to the current log file.
     * Users can share this file for debugging.
     *
     * @return The absolute path to the log file
     */
    fun getLogFilePath(): String

    /**
     * Deletes the current and rotated log files, verifying that both are absent afterward.
     * Missing files are an ordinary no-op; throws when deletion or verification fails.
     */
    fun clearLogs()

    /**
     * Gets the contents of the log file as a string.
     *
     * @return The log file contents, or empty string if file doesn't exist
     */
    fun getLogContents(): String
}
