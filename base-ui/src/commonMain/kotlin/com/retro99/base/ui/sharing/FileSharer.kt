package com.retro99.base.ui.sharing

/**
 * Interface for sharing files with platform-specific implementations.
 * Uses expect/actual pattern for Android and iOS.
 */
interface FileSharer {

    /**
     * Shares a file at the given path using the platform's native sharing mechanism.
     *
     * @param filePath The absolute path to the file to share
     * @param mimeType The MIME type of the file (e.g., "text/plain")
     * @param title Optional title for the share dialog
     */
    fun shareFile(filePath: String, mimeType: String, title: String? = null)

    /** Shares plain text (for example a quote) through the platform's share sheet. */
    fun shareText(text: String, title: String? = null)

    /**
     * Writes [text] to a file named [fileName] in app storage and shares it, so the
     * reader can save it to Files, Drive or another app.
     */
    fun shareTextAsFile(fileName: String, text: String, mimeType: String, title: String? = null)
}
