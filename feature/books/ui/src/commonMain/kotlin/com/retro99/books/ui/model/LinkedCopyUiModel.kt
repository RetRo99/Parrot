package com.retro99.books.ui.model

import com.retro99.books.domain.model.BookHome
import com.retro99.books.domain.model.links.LinkedCopy
import kotlinx.serialization.Serializable

/** Another copy of a linked book, as a row under "Also in". [key] is the copy key's value. */
@Serializable
data class LinkedCopyUiModel(
    val key: String,
    val serverId: String,
    val uuid: String,
    val title: String,
    val home: BookHome,
    val hasEbook: Boolean,
    val hasAudiobook: Boolean,
    val hasReadaloud: Boolean,
    val isDownloaded: Boolean,
    /** Text a library search matches against. */
    val searchTerms: List<String> = emptyList(),
    val serverLabel: String? = null,
)

fun LinkedCopy.toUiModel() = LinkedCopyUiModel(
    key = key.value,
    serverId = serverId,
    uuid = uuid,
    title = title,
    home = home,
    hasEbook = hasEbook,
    hasAudiobook = hasAudiobook,
    hasReadaloud = hasReadaloud,
    isDownloaded = isDownloaded,
    searchTerms = searchTerms,
)
