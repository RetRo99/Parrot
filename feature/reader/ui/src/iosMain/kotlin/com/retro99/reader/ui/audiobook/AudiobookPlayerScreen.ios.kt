package com.retro99.reader.ui.audiobook

import androidx.compose.runtime.Composable

@Composable
actual fun AudiobookPlayerScreen(
    serverId: String,
    bookUuid: String,
    readerOpenEntryPoint: String?,
    readerOpenCorrelationId: String?,
    onClose: () -> Unit,
) {
}
