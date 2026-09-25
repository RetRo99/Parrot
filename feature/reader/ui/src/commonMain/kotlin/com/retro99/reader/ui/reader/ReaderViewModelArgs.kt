package com.retro99.reader.ui.reader

import com.retro99.books.domain.model.BookType
import com.retro99.server.api.library.ProgressOwnerRef

data class ReaderViewModelArgs(
    val serverId: String,
    val bookUuid: String,
    val bookType: BookType,
    val onClose: () -> Unit,
    val onSettingsClick: () -> Unit,
    val selectedLocalPath: String? = null,
    val progressNativeId: String? = null,
    val progressAdapterId: String? = null,
    val progressOwner: ProgressOwnerRef? = null,
)
