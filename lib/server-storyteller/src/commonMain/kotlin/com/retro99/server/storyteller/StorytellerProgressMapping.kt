package com.retro99.server.storyteller

import com.retro99.server.api.ServerPosition
import com.retro99.server.storyteller.model.StorytellerPositionApiModel
import com.retro99.server.storyteller.model.toServerPosition
import com.retro99.sync.domain.ProgressKind
import com.retro99.sync.domain.ProgressLocator
import com.retro99.sync.domain.ProgressMutation
import com.retro99.sync.domain.ProgressSnapshot
import com.retro99.sync.domain.RemoteProgressSnapshot

internal fun ProgressMutation.toStorytellerServerPosition(
    serverId: String,
): ServerPosition {
    return snapshot.toStorytellerServerPosition(
        bookUuid = entityId,
        serverId = serverId,
        libraryBookId = libraryBookId,
    )
}

internal fun ProgressSnapshot.toStorytellerServerPosition(
    bookUuid: String,
    serverId: String,
    libraryBookId: String?,
): ServerPosition {
    return ServerPosition(
        bookUuid = bookUuid,
        serverId = serverId,
        libraryBookId = libraryBookId,
        timestamp = timestamp,
        createdAt = createdAt,
        updatedAt = updatedAt,
        locatorHref = locator?.href,
        locatorType = locator?.type,
        locatorTitle = locator?.title,
        locatorTarget = locator?.target,
        audioTimestampMs = audioTimestampMs,
        chapterIndex = chapterIndex,
        progression = progression,
        totalChapters = totalChapters,
        totalDurationMs = totalDurationMs,
        totalProgression = totalProgression,
        position = position,
        cssSelector = locator?.cssSelector,
    )
}

internal fun StorytellerPositionApiModel.toRemoteProgressSnapshot(
    remoteBookId: String,
    serverId: String,
): RemoteProgressSnapshot {
    return toServerPosition(
        bookUuid = remoteBookId,
        serverId = serverId,
    ).toRemoteProgressSnapshot(remoteBookId)
}

internal fun ServerPosition.toRemoteProgressSnapshot(
    remoteBookId: String,
): RemoteProgressSnapshot {
    return RemoteProgressSnapshot(
        entityId = bookUuid,
        remoteBookId = remoteBookId,
        libraryBookId = libraryBookId,
        kind = ProgressKind.EBOOK,
        snapshot = ProgressSnapshot(
            timestamp = timestamp,
            createdAt = createdAt,
            updatedAt = updatedAt,
            locator = ProgressLocator(
                href = locatorHref,
                type = locatorType,
                title = locatorTitle,
                target = locatorTarget,
                cssSelector = cssSelector,
            ),
            audioTimestampMs = audioTimestampMs,
            chapterIndex = chapterIndex,
            progression = progression,
            totalChapters = totalChapters,
            totalDurationMs = totalDurationMs,
            totalProgression = totalProgression,
            position = position,
        ),
        version = null,
        observedAt = observedAt ?: updatedAt ?: createdAt,
        // The timestamp is what the writing client sent; ours is in the write log.
        marker = timestamp?.toString(),
    )
}
