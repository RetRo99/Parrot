package com.retro99.books.ui.detail

import com.retro99.books.domain.model.BookWithProgressDomainModel
import com.retro99.library.domain.projection.LibraryBookGroup
import com.retro99.server.api.library.SourceBookKey

internal fun projectLibraryGroupReadingProgress(
    group: LibraryBookGroup,
    booksWithProgress: List<BookWithProgressDomainModel>,
): Map<SourceBookKey, Map<String, Double>> {
    val groupSourceKeys = group.members.map { member -> member.sourceKey }.toSet()
    val progressBySourceAndMediaType = linkedMapOf<SourceBookKey, MutableMap<String, Double>>()

    for (item in booksWithProgress) {
        if (item.book.unifiedGroupId != group.groupId.value) continue
        for (memberProgress in item.memberProgress) {
            val sourceKey = memberProgress.sourceKey
                ?.takeIf { key -> key in groupSourceKeys }
                ?: continue
            for ((mediaType, progressInfo) in memberProgress.progressInfoByMediaType) {
                val progression = progressInfo?.displayProgression ?: continue
                progressBySourceAndMediaType
                    .getOrPut(sourceKey) { linkedMapOf() }[mediaType.lowercase()] = progression
            }
        }
    }

    return progressBySourceAndMediaType.mapValues { (_, mediaProgress) ->
        mediaProgress.toMap()
    }
}

internal fun readingProgressForResource(
    progressBySourceAndMediaType: Map<SourceBookKey, Map<String, Double>>,
    sourceKey: SourceBookKey,
    mediaType: String,
): Double? = progressBySourceAndMediaType[sourceKey]?.get(mediaType.lowercase())
