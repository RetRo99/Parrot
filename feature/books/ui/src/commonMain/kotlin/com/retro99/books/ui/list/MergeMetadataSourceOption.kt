package com.retro99.books.ui.list

import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.server.api.library.SourceBookKey

data class MergeMetadataSourceOption(
    val sourceKey: SourceBookKey,
    val title: String,
    val authors: List<String>,
    val adapterId: String,
)

internal fun LibraryBookGroup.toMergeMetadataSourceOptions(): List<MergeMetadataSourceOption> =
    members.map { member ->
        val metadata = member.snapshot.metadata
        MergeMetadataSourceOption(
            sourceKey = member.sourceKey,
            title = metadata.title,
            authors = metadata.authors,
            adapterId = member.sourceKey.adapterId.value,
        )
    }
