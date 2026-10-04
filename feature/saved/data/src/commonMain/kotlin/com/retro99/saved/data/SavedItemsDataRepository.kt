package com.retro99.saved.data

import com.retro99.database.api.saved.SavedItemEntity
import com.retro99.database.api.saved.SavedItemMutation
import com.retro99.database.api.saved.SavedItemsDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.saved.domain.SavedItemsRepository
import com.retro99.saved.domain.model.HighlightColor
import com.retro99.saved.domain.model.SavedAudioPosition
import com.retro99.saved.domain.model.SavedBookRef
import com.retro99.saved.domain.model.SavedItem
import com.retro99.saved.domain.model.SavedItemType
import com.retro99.saved.domain.model.SavedLocation
import com.retro99.saved.domain.model.TextAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Saved items live in the local database and reach Parrot Cloud through the sync
 * outbox. The outbox entry only names the item: the Parrot Cloud adapter reads the
 * row when it pushes, so a queued change always carries the item's latest state.
 */
@Single(binds = [SavedItemsRepository::class])
class SavedItemsDataRepository(
    @Provided private val database: SavedItemsDatabase,
) : SavedItemsRepository {

    override fun observeForBook(bookKeys: Set<String>, bookUuids: Set<String>): Flow<List<SavedItem>> =
        database.observeForBooks(bookKeys, bookUuids).map { rows -> rows.map { row -> row.toDomain() } }

    override fun observeAll(): Flow<List<SavedItem>> =
        database.observeAll().map { rows -> rows.map { row -> row.toDomain() } }

    override suspend fun get(id: String): SavedItem? = database.get(id)?.toDomain()

    override suspend fun save(items: List<SavedItem>) {
        if (items.isEmpty()) return
        database.upsertWithMutations(
            items.map { item ->
                val stored = database.get(item.id)
                val entity = item.toEntity(remoteRevision = stored?.remoteRevision ?: item.remoteRevision)
                SavedItemMutation(item = entity, outboxEntry = entity.toOutboxEntry())
            },
        )
    }

    override suspend fun delete(id: String): SavedItem? {
        val stored = database.get(id) ?: return null
        if (stored.deletedAt != null) return null
        val now = Clock.System.now().toString()
        val tombstone = SavedItemRecord.from(stored).copy(deletedAt = now, updatedAt = now)
        database.upsertWithMutations(listOf(SavedItemMutation(tombstone, tombstone.toOutboxEntry())))
        return stored.toDomain()
    }

    override suspend fun getSnippetPending(bookUuid: String): List<SavedItem> =
        database.getSnippetPending(bookUuid).map { row -> row.toDomain() }

    override fun observePendingSyncCount(): Flow<Long> = database.observePendingSyncCount()
}

internal fun SavedItemEntity.toOutboxEntry(): SyncOutboxEntry = SyncOutboxEntry.new(
    entityType = SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM,
    entityId = id,
    operation = if (deletedAt == null) SyncOutboxEntry.OPERATION_UPSERT else SyncOutboxEntry.OPERATION_DELETE,
    payload = """{"item_id":"$id"}""",
    baseRevision = remoteRevision,
)

internal fun SavedItemEntity.toDomain(): SavedItem = SavedItem(
    id = id,
    book = SavedBookRef(key = bookKey, uuid = bookUuid, title = bookTitle, author = bookAuthor),
    type = SavedItemType.fromId(type),
    location = SavedLocation(
        href = href,
        mediaType = mediaType,
        progression = progression,
        totalProgression = totalProgression,
        position = position,
        chapterTitle = chapterTitle,
    ),
    anchor = textQuote?.let { quote -> TextAnchor(before = textBefore, quote = quote, after = textAfter) },
    color = HighlightColor.fromId(color),
    note = note,
    audio = audioMs?.let { offset -> SavedAudioPosition(href = audioHref, offsetMs = offset) },
    snippetPending = snippetPending,
    createdAt = parseInstant(createdAt),
    updatedAt = parseInstant(updatedAt),
    remoteRevision = remoteRevision,
)

internal fun SavedItem.toEntity(remoteRevision: Long? = this.remoteRevision): SavedItemRecord = SavedItemRecord(
    id = id,
    bookKey = book.key,
    bookUuid = book.uuid,
    bookTitle = book.title,
    bookAuthor = book.author,
    type = type.id,
    href = location.href,
    mediaType = location.mediaType,
    progression = location.progression,
    totalProgression = location.totalProgression,
    position = location.position,
    chapterTitle = location.chapterTitle,
    textBefore = anchor?.before,
    textQuote = anchor?.quote,
    textAfter = anchor?.after,
    color = if (type == SavedItemType.Highlight) (color ?: HighlightColor.Default).id else null,
    note = note?.trim()?.takeIf { text -> text.isNotEmpty() },
    audioHref = audio?.href,
    audioMs = audio?.offsetMs,
    snippetPending = snippetPending,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    deletedAt = null,
    remoteRevision = remoteRevision,
)

private fun parseInstant(value: String): Instant =
    Instant.parseOrNull(value) ?: Instant.fromEpochMilliseconds(0)

internal data class SavedItemRecord(
    override val id: String,
    override val bookKey: String,
    override val bookUuid: String,
    override val bookTitle: String?,
    override val bookAuthor: String?,
    override val type: String,
    override val href: String,
    override val mediaType: String?,
    override val progression: Double?,
    override val totalProgression: Double?,
    override val position: Int?,
    override val chapterTitle: String?,
    override val textBefore: String?,
    override val textQuote: String?,
    override val textAfter: String?,
    override val color: String?,
    override val note: String?,
    override val audioHref: String?,
    override val audioMs: Long?,
    override val snippetPending: Boolean,
    override val createdAt: String,
    override val updatedAt: String,
    override val deletedAt: String?,
    override val remoteRevision: Long?,
) : SavedItemEntity {
    companion object {
        fun from(entity: SavedItemEntity) = SavedItemRecord(
            id = entity.id,
            bookKey = entity.bookKey,
            bookUuid = entity.bookUuid,
            bookTitle = entity.bookTitle,
            bookAuthor = entity.bookAuthor,
            type = entity.type,
            href = entity.href,
            mediaType = entity.mediaType,
            progression = entity.progression,
            totalProgression = entity.totalProgression,
            position = entity.position,
            chapterTitle = entity.chapterTitle,
            textBefore = entity.textBefore,
            textQuote = entity.textQuote,
            textAfter = entity.textAfter,
            color = entity.color,
            note = entity.note,
            audioHref = entity.audioHref,
            audioMs = entity.audioMs,
            snippetPending = entity.snippetPending,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            deletedAt = entity.deletedAt,
            remoteRevision = entity.remoteRevision,
        )
    }
}
