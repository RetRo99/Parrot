package com.retro99.database.api.library

/** One copy of a library book on this device, keyed by (book, media type). */
data class DeviceFileEntity(
    val libraryBookId: String,
    val mediaType: String,
    val filePath: String,
    val fileSize: Long,
    val contentHash: String?,
    val contentHashAlgorithm: String?,
    val origin: String,
    val addedAt: String,
) {
    companion object {
        const val ORIGIN_IMPORT = "import"
        const val ORIGIN_CLOUD_DOWNLOAD = "cloud_download"
    }
}
