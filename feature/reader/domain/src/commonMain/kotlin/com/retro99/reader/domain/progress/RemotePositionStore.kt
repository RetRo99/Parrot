package com.retro99.reader.domain.progress

import com.github.michaelbull.result.getOrElse
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single

/**
 * The single source of truth for a book's remote reading position, and with it the progress
 * every screen shows for that book.
 *
 * Everything that fetches a position from a server stores it here (loading the library,
 * pulling to refresh, opening a book), and every surface reads its remote progress from here.
 * Before this existed each surface kept its own copy fetched at a different moment, so the
 * same book could show three different percentages at the same time.
 */
@Single
class RemotePositionStore(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
) {
    private val positions = MutableStateFlow<Map<String, ServerPosition>>(emptyMap())

    /** All known remote positions, keyed by book uuid. */
    fun observe(): Flow<Map<String, ServerPosition>> = positions.asStateFlow()

    /** The last known remote position for a book, or null when the server has none. */
    fun get(bookUuid: String): ServerPosition? = positions.value[bookUuid]

    /**
     * Fetches the remote position for one book and remembers it.
     *
     * A book without a remote position is forgotten; a failed fetch keeps the last known
     * position so every surface keeps showing the same value instead of dropping to none.
     */
    suspend fun refresh(serverId: String, bookUuid: String) {
        val readerRepository = repositoryProvider.getReaderRepository(serverId) ?: return
        val remotePosition = readerRepository.getRemotePosition(bookUuid).getOrElse { return }
        positions.update { current ->
            if (remotePosition == null) {
                current - bookUuid
            } else {
                current + (bookUuid to remotePosition)
            }
        }
    }

    fun clear() {
        positions.value = emptyMap()
    }
}
