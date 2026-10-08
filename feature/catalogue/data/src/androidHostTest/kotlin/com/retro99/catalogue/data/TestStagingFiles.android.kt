package com.retro99.catalogue.data

import java.io.File
import java.nio.file.Files

internal actual fun newTestStagingFiles(): Pair<CatalogueStagingFiles, () -> Unit> {
    val directory = Files.createTempDirectory("catalogue-staging-test").toFile()
    val files = JvmCatalogueStagingFiles { File(directory, CatalogueStagingFiles.DIRECTORY) }
    return files to { directory.deleteRecursively(); Unit }
}
