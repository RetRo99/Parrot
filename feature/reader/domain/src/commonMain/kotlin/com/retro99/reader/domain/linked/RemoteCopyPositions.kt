package com.retro99.reader.domain.linked

import com.github.michaelbull.result.getOrElse
import com.retro99.books.domain.model.links.CopySource
import com.retro99.books.domain.model.links.LinkedCopy
import com.retro99.database.api.links.LinkedCopyWritesDatabase
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.PositionOrigin
import com.retro99.sync.domain.EchoClassifier
import com.retro99.sync.domain.OwnWrite
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** A server's own position for one copy, fetched now. */
sealed interface RemoteFetch {
    data class Fetched(val position: PositionDomainModel?) : RemoteFetch

    /** The server failed or took longer than 2 seconds. */
    data object Failed : RemoteFetch
}

/**
 * Fetches the servers' positions for linked Storyteller and Audiobookshelf copies, in parallel,
 * waiting at most 2 seconds. Each fetched position is classified with [EchoClassifier]: an echo
 * of our own write gets origin `linked_copy`, anything else `remote` (§1.4, guard 2).
 */
@Factory
class RemoteCopyPositions(
    @Provided private val repositoryProvider: AuthenticatedRepositoryProvider,
    @Provided private val linkedCopyWritesDatabase: LinkedCopyWritesDatabase,
) {
    suspend fun fetch(copies: List<LinkedCopy>): Map<LinkedCopy, RemoteFetch> = coroutineScope {
        copies
            .filter { copy -> copy.key.source != CopySource.Library }
            .map { copy -> async { copy to fetchOne(copy) } }
            .awaitAll()
            .toMap()
    }

    private suspend fun fetchOne(copy: LinkedCopy): RemoteFetch {
        val repository = repositoryProvider.getReaderRepository(copy.serverId)
            ?: return RemoteFetch.Failed
        val fetched = withTimeoutOrNull(BUDGET_MS) {
            repository.getRemotePosition(copy.uuid).getOrElse { _ -> return@withTimeoutOrNull null }
                .let { position -> RemoteFetch.Fetched(position?.toPositionDomainModel()) }
        } ?: return RemoteFetch.Failed
        val position = fetched.position ?: return fetched
        return RemoteFetch.Fetched(
            position.copy(serverId = copy.serverId, origin = originOf(copy, position)),
        )
    }

    private suspend fun originOf(copy: LinkedCopy, position: PositionDomainModel): PositionOrigin {
        val cutoff = Clock.System.now().minus(WRITE_LOG_DAYS.days).toString()
        val ownWrite = linkedCopyWritesDatabase.getWriteForBook(copy.uuid, notBefore = cutoff)
            ?.let { write -> OwnWrite(write.marker, write.totalProgression) }
        val marker = position.timestamp?.toString()
            .takeIf { _ -> copy.key.source == CopySource.Storyteller }
        val isEcho = EchoClassifier.isEcho(marker, position.totalProgression, ownWrite)
        return if (isEcho) PositionOrigin.LinkedCopy else PositionOrigin.Remote
    }

    private companion object {
        const val BUDGET_MS = 2_000L
        const val WRITE_LOG_DAYS = 7
    }
}
