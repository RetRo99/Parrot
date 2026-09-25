package com.retro99.reader.ui.audiobook

import androidx.compose.runtime.Composable
import com.retro99.server.api.library.ProgressOwnerRef

@Composable
actual fun AudiobookPlayerScreen(
    serverId: String,
    bookUuid: String,
    selectedLocalPath: String?,
    progressNativeId: String?,
    progressAdapterId: String?,
    progressOwner: ProgressOwnerRef?,
    onClose: () -> Unit,
) {
}
