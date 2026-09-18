package com.retro99.database.api.books

interface BookmarkEntity {
    val id: String
    val bookUuid: String
    val locatorHref: String
    val locatorType: String?
    val locatorTitle: String?
    val progression: Double?
    val totalProgression: Double?
    val chapterIndex: Int?
    val position: Int?
    val createdAt: String
    val sortOrder: Int
    val remoteRevision: Long?
        get() = null
    val deletedAt: String?
        get() = null
}
