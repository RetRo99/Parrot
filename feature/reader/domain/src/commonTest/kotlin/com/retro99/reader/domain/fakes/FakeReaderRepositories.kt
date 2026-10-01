package com.retro99.reader.domain.fakes

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.PositionOrigin
import com.retro99.server.api.ServerBooksRepository
import com.retro99.server.api.ServerPosition
import com.retro99.server.api.ServerReaderRepository
import com.retro99.server.api.ServerSeriesRepository
import com.retro99.server.api.TextAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A server's positions: local ones by book, remote ones by book, and every save made. */
class FakeReaderRepository(
    override val serverId: String,
    val local: MutableMap<String, ServerPosition> = mutableMapOf(),
    val remote: MutableMap<String, ServerPosition> = mutableMapOf(),
    var remoteDelayMs: Long = 0L,
    var remoteFails: Boolean = false,
    var saveFails: Boolean = false,
) : ServerReaderRepository {
    val syncedSaves = mutableListOf<ServerPosition>()
    val localSaves = mutableListOf<ServerPosition>()
    var remoteFetches = 0

    override suspend fun getPosition(bookUuid: String): AppResult<ServerPosition?> =
        Ok(remote[bookUuid] ?: local[bookUuid])

    override suspend fun getLocalPosition(bookUuid: String): AppResult<ServerPosition?> =
        Ok(local[bookUuid])

    override suspend fun saveLocalPosition(position: ServerPosition): CompletableResult {
        if (saveFails) return Err(AppError.NotFoundError("save failed"))
        localSaves += position
        local[position.bookUuid] = position
        return Ok(Unit)
    }

    override suspend fun saveLocalPositionWithSync(
        bookUuid: String,
        position: ServerPosition,
    ): CompletableResult {
        if (saveFails) return Err(AppError.NotFoundError("save failed"))
        val saved = position.copy(bookUuid = bookUuid, serverId = serverId)
        syncedSaves += saved
        local[bookUuid] = saved
        return Ok(Unit)
    }

    override suspend fun getRemotePosition(bookUuid: String): AppResult<ServerPosition?> {
        remoteFetches++
        if (remoteDelayMs > 0) delay(remoteDelayMs)
        if (remoteFails) return Err(AppError.NotFoundError("offline"))
        return Ok(remote[bookUuid])
    }
}

class FakeRepositoryProvider(
    private val readers: List<ServerReaderRepository> = emptyList(),
    private val books: List<ServerBooksRepository> = emptyList(),
) : AuthenticatedRepositoryProvider {
    override fun observeBooksRepositories(): Flow<List<ServerBooksRepository>> = flowOf(books)

    override suspend fun getBooksRepositories(): List<ServerBooksRepository> = books

    override suspend fun getBooksRepository(serverId: String): ServerBooksRepository? =
        books.firstOrNull { repository -> repository.serverId == serverId }

    override suspend fun getReaderRepository(serverId: String): ServerReaderRepository? =
        readers.firstOrNull { repository -> repository.serverId == serverId }

    override fun observeSeriesRepositories(): Flow<List<ServerSeriesRepository>> =
        flowOf(emptyList())

    override suspend fun getSeriesRepositories(): List<ServerSeriesRepository> = emptyList()
}

fun serverPosition(
    bookUuid: String,
    serverId: String,
    totalProgression: Double? = null,
    href: String? = "chapter-1.xhtml",
    progression: Double? = totalProgression,
    audioMs: Long? = null,
    totalDurationMs: Long? = null,
    observedAt: String? = null,
    origin: PositionOrigin = PositionOrigin.User,
    timestamp: Long? = null,
    remoteRevision: Long? = null,
    textAnchor: TextAnchor? = null,
): ServerPosition = ServerPosition(
    bookUuid = bookUuid,
    serverId = serverId,
    timestamp = timestamp,
    createdAt = observedAt,
    updatedAt = observedAt,
    locatorHref = href,
    locatorType = null,
    locatorTitle = null,
    locatorTarget = null,
    audioTimestampMs = audioMs,
    chapterIndex = null,
    progression = progression,
    totalChapters = null,
    totalDurationMs = totalDurationMs,
    totalProgression = totalProgression,
    position = null,
    remoteRevision = remoteRevision,
    origin = origin,
    observedAt = observedAt,
    textAnchor = textAnchor,
)
