package com.retro99.reader.ui.audiobook

import androidx.compose.runtime.Composable
import com.retro99.server.api.library.ProgressOwnerRef

@Composable
expect fun AudiobookPlayerScreen(
    serverId: String,
    bookUuid: String,
    selectedLocalPath: String? = null,
    progressNativeId: String? = null,
    progressAdapterId: String? = null,
    progressOwner: ProgressOwnerRef? = null,
    onClose: () -> Unit,
)
