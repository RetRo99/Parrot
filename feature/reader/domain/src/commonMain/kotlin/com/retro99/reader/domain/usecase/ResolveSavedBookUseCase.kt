package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.links.CopyKey
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.copyKey
import com.retro99.books.domain.usecase.GetBooksUseCase
import com.retro99.reader.domain.linked.LinkedCopiesSource
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Who a book's saved items belong to: the open copy's portable key, plus every linked
 * copy, whose bookmarks and highlights show up in the same list.
 */
data class SavedBookIdentity(
    val selfKey: String,
    val selfUuid: String,
    val keys: Set<String>,
    val uuids: Set<String>,
)

@Factory
class ResolveSavedBookUseCase(
    private val linkedCopiesSource: LinkedCopiesSource,
    @Provided private val getBooksUseCase: GetBooksUseCase,
) {
    suspend operator fun invoke(serverId: String, bookUuid: String): SavedBookIdentity {
        val linked = runCatching { linkedCopiesSource.linkedCopies(serverId, bookUuid) }.getOrNull()
        if (linked != null) {
            return SavedBookIdentity(
                selfKey = linked.self.key.value,
                selfUuid = bookUuid,
                keys = linked.all.mapTo(linkedSetOf()) { copy -> copy.key.value },
                uuids = linked.all.mapTo(linkedSetOf()) { copy -> copy.uuid },
            )
        }
        val book = runCatching {
            getBooksUseCase(groupLinked = false).first().getOrElse { emptyList() }
                .firstOrNull { candidate -> candidate.serverId == serverId && candidate.uuid == bookUuid }
        }.getOrNull()
        // A book missing from the lists (still loading, say) is a library book: local and
        // Parrot Cloud books are keyed by their library book id, which is their uuid.
        val key = book?.copyKey() ?: CopyKey(CopySource.Library, bookUuid)
        return SavedBookIdentity(
            selfKey = key.value,
            selfUuid = bookUuid,
            keys = setOf(key.value),
            uuids = setOf(bookUuid),
        )
    }
}
