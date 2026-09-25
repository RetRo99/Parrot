package com.retro99.reader.ui.audiobook

import com.retro99.server.api.library.ProgressOwnerRef

data class AudiobookPlayerViewModelArgs(
    val serverId: String,
    val bookUuid: String,
    val selectedLocalPath: String?,
    val progressNativeId: String?,
    val progressAdapterId: String?,
    val progressOwner: ProgressOwnerRef?,
    val onClose: () -> Unit,
)
