package com.retro99.books.data

import com.retro99.base.server.LOCAL_SERVER_ID
import com.retro99.books.data.transfer.BookFileTransferFileStore
import com.retro99.database.api.library.LibraryBookMergeDatabase
import com.retro99.preferences.api.Preferences
import com.retro99.preferences.api.PreferencesKey
import com.retro99.preferences.api.getObject
import com.retro99.preferences.api.putObject
import com.retro99.sync.domain.LibraryBookMerger
import com.retro99.user.api.UserRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

@Single(binds = [LibraryBookMerger::class])
class LibraryBookMergerImpl(
    @Provided private val mergeDatabase: LibraryBookMergeDatabase,
    @Provided private val fileStore: BookFileTransferFileStore,
    @Provided private val preferences: Preferences,
    @Provided private val userRegistry: UserRegistry,
) : LibraryBookMerger {

    override suspend fun merge(fromId: String, intoId: String) {
        val redundantPaths = mergeDatabase.mergeLibraryBook(fromId = fromId, intoId = intoId)
        redundantPaths.forEach { path -> fileStore.delete(path) }
        moveCurrentlyReading(fromId = fromId, intoId = intoId)
        moveLinkedResumeDismissals(fromId = fromId, intoId = intoId)
    }

    /**
     * "Stay here" answers to the linked resume prompt are stored by the reader as
     * `<targetKey>|<sourceKey>|<observedAt>` strings; entries naming the merged book move.
     */
    private fun moveLinkedResumeDismissals(fromId: String, intoId: String) {
        val key = PreferencesKey.UserScoped(
            userId = userRegistry.getActiveProfileIdOrDefault(),
            key = PreferencesKey.DismissedLinkedResume.name,
        )
        val stored = preferences.getObject<List<String>>(key) ?: return
        val moved = renameDismissedCopy(
            entries = stored,
            fromKey = "library:$fromId",
            intoKey = "library:$intoId",
        )
        if (moved != stored) preferences.putObject(key, moved)
    }

    /** "Continue reading" is stored as JSON owned by the reader; only its book id moves. */
    private fun moveCurrentlyReading(fromId: String, intoId: String) {
        val key = PreferencesKey.UserScoped(
            userId = userRegistry.getActiveProfileIdOrDefault(),
            key = PreferencesKey.CurrentlyReading.name,
        )
        val stored = preferences.getStringOrNull(key) ?: return
        val currentlyReading = runCatching { Json.parseToJsonElement(stored).jsonObject }
            .getOrNull()
            ?: return
        val serverId = currentlyReading["serverId"]?.jsonPrimitive?.contentOrNull
        val bookUuid = currentlyReading["bookUuid"]?.jsonPrimitive?.contentOrNull
        if (serverId != LOCAL_SERVER_ID || bookUuid != fromId) return
        val moved = JsonObject(currentlyReading + ("bookUuid" to JsonPrimitive(intoId)))
        preferences.putString(key, moved.toString())
    }
}

/** [entries] with the target or source copy [fromKey] renamed to [intoKey]. */
internal fun renameDismissedCopy(
    entries: List<String>,
    fromKey: String,
    intoKey: String,
): List<String> = entries.map { entry ->
    val parts = entry.split('|')
    if (parts.size < 3) return@map entry
    val target = if (parts[0] == fromKey) intoKey else parts[0]
    val source = if (parts[1] == fromKey) intoKey else parts[1]
    (listOf(target, source) + parts.drop(2)).joinToString("|")
}.distinct()
