package com.retro99.reader.data.source

/**
 * Downloads a multi-file book into a private staging directory and publishes it only after every
 * file has completed. Failed and cancelled attempts remove only their staging directory.
 */
internal suspend fun <T> downloadMultiFileBundle(
    filePaths: List<String>,
    destinationPath: (index: Int) -> String,
    prepareStagingDirectory: suspend () -> Unit,
    downloadFile: suspend (
        sourcePath: String,
        destinationPath: String,
        index: Int,
    ) -> T,
    isFailure: (result: T) -> Boolean,
    promoteStagingDirectory: suspend () -> Unit,
    deleteStagingDirectory: suspend () -> Unit,
    successResult: suspend () -> T,
): T {
    require(filePaths.isNotEmpty()) { "A multi-file download must contain at least one path" }

    var promoted = false
    try {
        prepareStagingDirectory()
        for ((index, sourcePath) in filePaths.withIndex()) {
            val result = downloadFile(sourcePath, destinationPath(index), index)
            if (isFailure(result)) return result
        }
        promoteStagingDirectory()
        promoted = true
        return successResult()
    } finally {
        if (!promoted) {
            deleteStagingDirectory()
        }
    }
}
