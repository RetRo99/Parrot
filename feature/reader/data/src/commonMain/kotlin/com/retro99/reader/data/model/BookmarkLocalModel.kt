package com.retro99.reader.data.model

import com.retro99.database.api.books.BookmarkEntity
import com.retro99.reader.domain.model.BookmarkDomainModel

data class BookmarkLocalModel(
    override val id: String,
    override val bookUuid: String,
    override val locatorHref: String,
    override val locatorType: String?,
    override val locatorTitle: String?,
    override val progression: Double?,
    override val totalProgression: Double?,
    override val chapterIndex: Int?,
    override val position: Int?,
    override val createdAt: String,
    override val sortOrder: Int = 0,
    override val remoteRevision: Long? = null,
    override val deletedAt: String? = null,
) : BookmarkEntity

fun BookmarkLocalModel.toDomain(): BookmarkDomainModel {
    return BookmarkDomainModel(
        id = id,
        bookUuid = bookUuid,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        progression = progression,
        totalProgression = totalProgression,
        chapterIndex = chapterIndex,
        position = position,
        createdAt = createdAt,
        sortOrder = sortOrder,
    )
}

fun BookmarkDomainModel.toLocal(): BookmarkLocalModel {
    return BookmarkLocalModel(
        id = id,
        bookUuid = bookUuid,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        progression = progression,
        totalProgression = totalProgression,
        chapterIndex = chapterIndex,
        position = position,
        createdAt = createdAt,
        sortOrder = sortOrder,
    )
}

fun BookmarkEntity.toLocalModel(): BookmarkLocalModel {
    return BookmarkLocalModel(
        id = id,
        bookUuid = bookUuid,
        locatorHref = locatorHref,
        locatorType = locatorType,
        locatorTitle = locatorTitle,
        progression = progression,
        totalProgression = totalProgression,
        chapterIndex = chapterIndex,
        position = position,
        createdAt = createdAt,
        sortOrder = sortOrder,
        remoteRevision = remoteRevision,
        deletedAt = deletedAt,
    )
}
