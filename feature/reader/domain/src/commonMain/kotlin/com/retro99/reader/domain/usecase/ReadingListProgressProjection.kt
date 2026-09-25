package com.retro99.reader.domain.usecase

import com.retro99.books.domain.model.BookMemberProgressDomainModel
import com.retro99.books.domain.model.BookProgressInfoDomainModel
import com.retro99.server.api.library.SourceBookKey

internal fun projectReadingListProgress(
    fallbackBookUuid: String,
    preferredMediaSourceKeys: Map<String, SourceBookKey>,
    memberProgress: List<BookMemberProgressDomainModel>,
): BookProgressInfoDomainModel? {
    val mediaTypes = memberProgress
        .flatMap { member -> member.mediaTypes }
        .distinct()
        .sorted()
    val mediaChoices = mediaTypes.mapNotNull { mediaType ->
        val candidates = memberProgress.filter { member ->
            mediaType in member.mediaTypes &&
                member.progressInfoByMediaType[mediaType]?.hasProgress() == true
        }
        val preferredSourceKey = preferredMediaSourceKeys[mediaType]
        val preferredCandidate = preferredSourceKey?.let { sourceKey ->
            candidates
                .filter { member -> member.sourceKey == sourceKey }
                .newestPosition(mediaType)
        }
        val selectedCandidate = preferredCandidate ?: candidates.newestPosition(mediaType)
        selectedCandidate?.let { member ->
            ReadingListMediaProgress(
                mediaType = mediaType,
                info = requireNotNull(member.progressInfoByMediaType[mediaType]),
                updatedAt = member.savedPositionUpdatedAtByMediaType[mediaType],
            )
        }
    }

    val cachedEbook = memberProgress.any { member ->
        member.progressInfoByMediaType["ebook"]?.isEbookCached == true
    }
    val cachedAudiobook = memberProgress.any { member ->
        member.progressInfoByMediaType["audiobook"]?.isAudiobookCached == true
    }
    val cachedReadaloud = memberProgress.any { member ->
        member.progressInfoByMediaType["readaloud"]?.isReadaloudCached == true
    }

    val selectedInfo = mediaChoices
        .sortedWith(
            compareByDescending<ReadingListMediaProgress> { choice -> choice.updatedAt.orEmpty() }
                .thenBy { choice -> choice.mediaType },
        )
        .firstOrNull()
        ?.info

    if (selectedInfo != null) {
        return selectedInfo.copy(
            isEbookCached = cachedEbook,
            isAudiobookCached = cachedAudiobook,
            isReadaloudCached = cachedReadaloud,
        )
    }

    if (!cachedEbook && !cachedAudiobook && !cachedReadaloud) return null
    return BookProgressInfoDomainModel(
        bookUuid = fallbackBookUuid,
        localProgression = null,
        remoteProgression = null,
        isEbookCached = cachedEbook,
        isAudiobookCached = cachedAudiobook,
        isReadaloudCached = cachedReadaloud,
    )
}

private fun List<BookMemberProgressDomainModel>.newestPosition(
    mediaType: String,
): BookMemberProgressDomainModel? = maxWithOrNull(
    compareBy<BookMemberProgressDomainModel> { member ->
        member.savedPositionUpdatedAtByMediaType[mediaType].orEmpty()
    }.thenBy { member -> member.serverId.orEmpty() }
        .thenBy { member -> member.bookUuid },
)

private fun BookProgressInfoDomainModel?.hasProgress(): Boolean =
    this?.let { progress ->
        progress.localProgression != null || progress.remoteProgression != null
    } == true

private data class ReadingListMediaProgress(
    val mediaType: String,
    val info: BookProgressInfoDomainModel,
    val updatedAt: String?,
)
