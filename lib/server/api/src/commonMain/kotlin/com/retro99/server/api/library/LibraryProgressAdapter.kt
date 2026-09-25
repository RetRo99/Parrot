package com.retro99.server.api.library

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.retro99.server.api.ServerPosition
import com.retro99.base.result.AppError
import com.retro99.base.result.AppResult
import com.retro99.base.result.CompletableResult
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class ProgressOwnerRef(
    val adapterId: LibraryAdapterId,
    val source: SourceBookRef,
    val nativeProgressId: String,
) {
    init {
        require(nativeProgressId.isNotBlank())
        require(adapterId == source.key.adapterId)
        require(source.connectionId != null) {
            "Resolving native progress requires a local source connection"
        }
    }
}

/**
 * Values retain native semantics. In particular, adapter-owned data is never
 * converted to a generic percentage for writes.
 */
sealed interface LibraryProgressValue {
    /** Lossless projection used by the reader, including native timestamps and locators. */
    data class ReaderPosition(val position: ServerPosition) : LibraryProgressValue

    data class EbookLocator(
        val href: String?,
        val progression: Double?,
        val totalProgression: Double?,
        val cssSelector: String? = null,
    ) : LibraryProgressValue

    data class AudiobookPosition(
        val timestampMillis: Long,
        val durationMillis: Long? = null,
        val chapterIndex: Int? = null,
    ) : LibraryProgressValue {
        init { require(timestampMillis >= 0) }
    }

    data class AdapterOwned(
        val format: String,
        val payload: String,
    ) : LibraryProgressValue {
        init {
            require(format.isNotBlank())
            require(payload.isNotBlank())
        }
    }
}

interface LibraryProgressAdapter {
    val adapterId: LibraryAdapterId

    suspend fun read(owner: ProgressOwnerRef): LibraryProgressValue?

    suspend fun write(owner: ProgressOwnerRef, value: LibraryProgressValue)

    /** Confirms the exact native source is still available before using shared baselines. */
    suspend fun validateOwner(owner: ProgressOwnerRef): AppResult<Unit> = Ok(Unit)

    /**
     * Reads the adapter's local reader position. Adapters with native formats can
     * override this to project them into the lossless shared reader model.
     */
    suspend fun readLocalPosition(owner: ProgressOwnerRef): AppResult<ServerPosition?> =
        readPosition(owner)

    /** Reads a remote candidate separately so the reader can retain conflict handling. */
    suspend fun readRemotePosition(owner: ProgressOwnerRef): AppResult<ServerPosition?> = Ok(null)

    /** Persists a reader position and any durable sync intent owned by the adapter. */
    suspend fun savePositionWithSync(
        owner: ProgressOwnerRef,
        position: ServerPosition,
    ): CompletableResult = try {
        write(owner, LibraryProgressValue.ReaderPosition(position))
        Ok(Unit)
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Err(AppError.UnknownError(exception))
    }
}

private suspend fun LibraryProgressAdapter.readPosition(
    owner: ProgressOwnerRef,
): AppResult<ServerPosition?> = try {
    val value = read(owner) ?: return Ok(null)
    when (value) {
        is LibraryProgressValue.ReaderPosition -> Ok(value.position)
        is LibraryProgressValue.EbookLocator -> Ok(
            ServerPosition(
                bookUuid = owner.nativeProgressId,
                serverId = requireNotNull(owner.source.connectionId).value,
                timestamp = null,
                createdAt = null,
                updatedAt = null,
                locatorHref = value.href,
                locatorType = null,
                locatorTitle = null,
                locatorTarget = null,
                audioTimestampMs = null,
                chapterIndex = null,
                progression = value.progression,
                totalChapters = null,
                totalDurationMs = null,
                totalProgression = value.totalProgression,
                position = null,
                cssSelector = value.cssSelector,
            ),
        )
        is LibraryProgressValue.AudiobookPosition -> Ok(
            ServerPosition(
                bookUuid = owner.nativeProgressId,
                serverId = requireNotNull(owner.source.connectionId).value,
                timestamp = null,
                createdAt = null,
                updatedAt = null,
                locatorHref = null,
                locatorType = null,
                locatorTitle = null,
                locatorTarget = null,
                audioTimestampMs = value.timestampMillis,
                chapterIndex = value.chapterIndex,
                progression = null,
                totalChapters = null,
                totalDurationMs = value.durationMillis,
                totalProgression = null,
                position = null,
            ),
        )
        is LibraryProgressValue.AdapterOwned -> Err(
            AppError.NotFoundError(
                "Adapter-owned progress requires a reader position projection",
            ),
        )
    }
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
    Err(AppError.UnknownError(exception))
}

interface LibraryProgressAdapterRegistry {
    fun adapter(adapterId: LibraryAdapterId): LibraryProgressAdapter?
}
