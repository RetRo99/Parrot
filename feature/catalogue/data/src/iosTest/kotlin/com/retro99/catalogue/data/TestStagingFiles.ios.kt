package com.retro99.catalogue.data

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
internal actual fun newTestStagingFiles(): Pair<CatalogueStagingFiles, () -> Unit> {
    val directory = "${NSTemporaryDirectory()}catalogue-staging-test-${NSUUID().UUIDString}"
    val files = PosixCatalogueStagingFiles("$directory/${CatalogueStagingFiles.DIRECTORY}")
    return files to { NSFileManager.defaultManager.removeItemAtPath(directory, null); Unit }
}
