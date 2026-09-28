package com.retro99.base.ui.sharing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android implementation of FileSharer.
 * Uses Intent.ACTION_SEND with FileProvider to share files securely.
 */
class AndroidFileSharer(
    private val context: Context,
) : FileSharer {

    override fun shareFile(filePath: String, mimeType: String, title: String?) {
        val file = File(filePath)
        check(file.isFile) { "Diagnostic log file is unavailable" }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooserIntent = Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooserIntent)
    }
}
