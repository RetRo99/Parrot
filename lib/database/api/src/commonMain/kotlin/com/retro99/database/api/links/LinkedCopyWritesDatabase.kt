package com.retro99.database.api.links

/** The latest write this device made into one linked copy (§1.4, guard 1). */
data class LinkedCopyWriteEntity(
    /** The written copy's key, such as `storyteller:<uuid>`. */
    val targetKey: String,
    /** The written copy's book uuid, as its positions are stored. */
    val targetBookUuid: String,
    val sourceKey: String?,
    val sourceObservedAt: String?,
    /** ISO-8601 instant. */
    val writtenAt: String,
    /** Storyteller: the timestamp sent. Parrot Cloud: the acknowledged revision. */
    val marker: String?,
    val locatorHref: String?,
    val progression: Double?,
    val totalProgression: Double?,
    val audioMs: Long?,
)

interface LinkedCopyWritesDatabase {
    /** Replaces the target's row, and deletes rows written before [deleteWrittenBefore]. */
    suspend fun replace(write: LinkedCopyWriteEntity, deleteWrittenBefore: String)

    /** The target's row, unless it was written before [notBefore]. */
    suspend fun getWrite(targetKey: String, notBefore: String): LinkedCopyWriteEntity?

    /** The latest row for a book uuid, unless it was written before [notBefore]. */
    suspend fun getWriteForBook(bookUuid: String, notBefore: String): LinkedCopyWriteEntity?

    suspend fun setMarker(targetKey: String, marker: String)
}
