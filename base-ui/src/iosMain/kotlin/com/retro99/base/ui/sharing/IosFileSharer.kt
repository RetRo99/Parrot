package com.retro99.base.ui.sharing

import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.writeToFile
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

/**
 * iOS implementation of FileSharer.
 * Uses UIActivityViewController to share files.
 */
class IosFileSharer : FileSharer {

    override fun shareFile(filePath: String, mimeType: String, title: String?) {
        val fileManager = NSFileManager.defaultManager
        check(fileManager.fileExistsAtPath(filePath)) { "Diagnostic log file is unavailable" }
        present(listOf(NSURL.fileURLWithPath(filePath)))
    }

    override fun shareText(text: String, title: String?) {
        present(listOf(text))
    }

    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    override fun shareTextAsFile(fileName: String, text: String, mimeType: String, title: String?) {
        val path = NSTemporaryDirectory() + fileName
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Export could not be encoded")
        check(data.writeToFile(path, atomically = true)) { "Export could not be written" }
        shareFile(path, mimeType, title)
    }

    private fun present(items: List<Any>) {
        val activityViewController = UIActivityViewController(
            activityItems = items,
            applicationActivities = null,
        )

        // Get the root view controller to present from.
        val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController
            ?: error("Share presentation is unavailable")
        rootViewController.presentViewController(
            activityViewController,
            animated = true,
            completion = null,
        )
    }
}
