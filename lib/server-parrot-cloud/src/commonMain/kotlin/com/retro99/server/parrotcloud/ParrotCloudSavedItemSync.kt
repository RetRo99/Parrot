package com.retro99.server.parrotcloud

import com.retro99.database.api.saved.SavedItemEntity
import com.retro99.database.api.saved.SavedItemsDatabase
import com.retro99.database.api.sync.SyncOutboxDatabase
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.sync.domain.SyncMutationResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Instant

/**
 * Bookmarks, highlights and notes in Parrot Cloud (entity type saved_item).
 *
 * Outbox entries only name the item; the payload is built from the row when it is
 * pushed, so a retry always sends the latest state. Every device resolves conflicts
 * the same way the server does: the later updated_at wins, and equal times go to the
 * larger mutation id on the server (locally, an equal time keeps what is stored).
 */
@Single
class ParrotCloudSavedItemSync(
    @Provided private val savedItemsDatabase: SavedItemsDatabase,
    @Provided private val syncOutboxDatabase: SyncOutboxDatabase,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Queues items Parrot Cloud has never had, such as bookmarks from before saved items synced. */
    suspend fun enqueueUnsynced(cloudUserId: String) {
        savedItemsDatabase.getUnsynced().forEach { item ->
            syncOutboxDatabase.enqueue(
                SyncOutboxEntry.new(
                    entityType = SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM,
                    entityId = item.id,
                    operation = if (item.deletedAt == null) {
                        SyncOutboxEntry.OPERATION_UPSERT
                    } else {
                        SyncOutboxEntry.OPERATION_DELETE
                    },
                    payload = """{"item_id":"${item.id}"}""",
                    cloudUserId = cloudUserId,
                ),
            )
        }
    }

    /**
     * Fills in saved_item payloads from the current rows. Entries whose item is gone are
     * dropped from the outbox; everything else passes through unchanged.
     */
    suspend fun preparePush(entries: List<SyncOutboxEntry>): List<SyncOutboxEntry> = entries.mapNotNull { entry ->
        if (entry.entityType != SyncOutboxEntry.ENTITY_TYPE_SAVED_ITEM) return@mapNotNull entry
        val item = savedItemsDatabase.get(entry.entityId)
        if (item == null) {
            syncOutboxDatabase.delete(entry.mutationId)
            return@mapNotNull null
        }
        entry.copy(
            operation = if (item.deletedAt == null) {
                SyncOutboxEntry.OPERATION_UPSERT
            } else {
                SyncOutboxEntry.OPERATION_DELETE
            },
            payload = json.encodeToString(item.toPayload()),
        )
    }

    /** A change pulled from Parrot Cloud. */
    suspend fun applyRemote(payload: JsonElement) {
        val remote = try {
            json.decodeFromJsonElement<ParrotCloudSavedItemPayload>(payload)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        storeIfNewer(remote)
    }

    suspend fun onAccepted(entry: SyncOutboxEntry, response: SyncMutationResponse) {
        val revision = response.revision ?: return
        val item = savedItemsDatabase.get(entry.entityId) ?: return
        // A newer local edit made while this push was in flight stays queued; only record
        // the revision the server now has.
        savedItemsDatabase.setRemoteRevision(item.id, revision)
    }

    /** The server kept a newer version: take it. */
    suspend fun onConflict(response: SyncMutationResponse) {
        val payload = response.payload ?: return
        val remote = try {
            json.decodeFromString<ParrotCloudSavedItemPayload>(payload)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        storeIfNewer(remote, acceptEqual = true)
    }

    private suspend fun storeIfNewer(remote: ParrotCloudSavedItemPayload, acceptEqual: Boolean = false) {
        val remoteUpdatedAt = Instant.parseOrNull(remote.updatedAt) ?: return
        val entity = remote.toEntity(localBookUuid = null)
        savedItemsDatabase.applyRemote(entity) { local ->
            if (local == null) return@applyRemote true
            val localUpdatedAt = Instant.parseOrNull(local.updatedAt) ?: return@applyRemote true
            if (acceptEqual) remoteUpdatedAt >= localUpdatedAt else remoteUpdatedAt > localUpdatedAt
        }
    }
}

@Serializable
internal data class ParrotCloudSavedItemPayload(
    @SerialName("item_id")
    val itemId: String,
    @SerialName("book_key")
    val bookKey: String,
    @SerialName("book_title")
    val bookTitle: String? = null,
    @SerialName("book_author")
    val bookAuthor: String? = null,
    val type: String,
    val href: String,
    @SerialName("media_type")
    val mediaType: String? = null,
    val progression: Double? = null,
    @SerialName("total_progression")
    val totalProgression: Double? = null,
    val position: Int? = null,
    @SerialName("chapter_title")
    val chapterTitle: String? = null,
    @SerialName("text_before")
    val textBefore: String? = null,
    @SerialName("text_quote")
    val textQuote: String? = null,
    @SerialName("text_after")
    val textAfter: String? = null,
    val color: String? = null,
    val note: String? = null,
    @SerialName("audio_href")
    val audioHref: String? = null,
    @SerialName("audio_ms")
    val audioMs: Long? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    @SerialName("remote_revision")
    val remoteRevision: Long? = null,
    @SerialName("word_selected")
    val wordSelected: String? = null,
    @SerialName("word_headword")
    val wordHeadword: String? = null,
    @SerialName("word_language")
    val wordLanguage: String? = null,
    @SerialName("word_gloss")
    val wordGloss: String? = null,
    @SerialName("word_part_of_speech")
    val wordPartOfSpeech: String? = null,
)

internal fun SavedItemEntity.toPayload() = ParrotCloudSavedItemPayload(
    itemId = id,
    bookKey = bookKey,
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    type = type,
    href = href,
    mediaType = mediaType,
    progression = progression,
    totalProgression = totalProgression,
    position = position,
    chapterTitle = chapterTitle,
    textBefore = textBefore,
    textQuote = textQuote,
    textAfter = textAfter,
    color = color,
    note = note,
    audioHref = audioHref,
    audioMs = audioMs,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    wordSelected = wordSelected,
    wordHeadword = wordHeadword,
    wordLanguage = wordLanguage,
    wordGloss = wordGloss,
    wordPartOfSpeech = wordPartOfSpeech,
)

/**
 * The local book id is the id part of the copy key for every source: library books use
 * their library book id, Storyteller and Audiobookshelf books their server ids.
 */
internal fun ParrotCloudSavedItemPayload.toEntity(localBookUuid: String?): SavedItemEntity = RemoteSavedItem(
    id = itemId,
    bookKey = bookKey,
    bookUuid = localBookUuid ?: bookKey.substringAfter(':', missingDelimiterValue = bookKey),
    bookTitle = bookTitle,
    bookAuthor = bookAuthor,
    type = type,
    href = href,
    mediaType = mediaType,
    progression = progression,
    totalProgression = totalProgression,
    position = position,
    chapterTitle = chapterTitle,
    textBefore = textBefore,
    textQuote = textQuote,
    textAfter = textAfter,
    color = color,
    note = note,
    audioHref = audioHref,
    audioMs = audioMs,
    // A bookmark synced before its first sentence was known still needs one.
    snippetPending = type == SavedItemEntity.TYPE_BOOKMARK && textQuote.isNullOrBlank() && audioMs == null,
    createdAt = createdAt?.normalizedInstant() ?: updatedAt.normalizedInstant(),
    updatedAt = updatedAt.normalizedInstant(),
    deletedAt = deletedAt?.normalizedInstant(),
    remoteRevision = remoteRevision,
    wordSelected = wordSelected,
    wordHeadword = wordHeadword,
    wordLanguage = wordLanguage,
    wordGloss = wordGloss,
    wordPartOfSpeech = wordPartOfSpeech,
)

/** Postgres writes "+00:00"; store the same "Z" form the app writes. */
private fun String.normalizedInstant(): String = Instant.parseOrNull(this)?.toString() ?: this

private data class RemoteSavedItem(
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
    override val wordSelected: String? = null,
    override val wordHeadword: String? = null,
    override val wordLanguage: String? = null,
    override val wordGloss: String? = null,
    override val wordPartOfSpeech: String? = null,
) : SavedItemEntity
