package com.retro99.books.ui.positions

import com.github.michaelbull.result.fold
import com.retro99.reader.domain.positions.ApplyPreview
import com.retro99.reader.domain.positions.CopyPositionRow
import com.retro99.reader.domain.usecase.ApplyPositionUseCase
import com.retro99.reader.domain.usecase.ApplyResult
import com.retro99.reader.domain.usecase.ObserveCopyPositionsUseCase
import com.retro99.reader.domain.usecase.PreviewApplyPositionUseCase
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.InstallationDeviceIdentity
import com.retro99.server.api.ServerPositionLocalSource
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.positionDeviceName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

data class PositionsMetadata(
    val bookTitle: String,
    val deviceName: String,
    val serverNames: Map<String, String>,
)

/** UI-layer adapter: server display names never enter LinkedCopy. */
interface PositionsDataSource {
    suspend fun metadata(serverId: String, bookUuid: String): PositionsMetadata
    suspend fun load(serverId: String, bookUuid: String): List<CopyPositionRow>?
    suspend fun preview(source: CopyPositionRow, rows: List<CopyPositionRow>): List<ApplyPreview>
    suspend fun apply(source: CopyPositionRow, previews: List<ApplyPreview>): List<ApplyResult>
    fun changes(): Flow<Unit>
}

@Factory(binds = [PositionsDataSource::class])
class DefaultPositionsDataSource(
    private val observe: ObserveCopyPositionsUseCase,
    private val preview: PreviewApplyPositionUseCase,
    private val apply: ApplyPositionUseCase,
    @Provided private val registry: ServerRegistry,
    @Provided private val identity: InstallationDeviceIdentity,
    @Provided private val repositories: AuthenticatedRepositoryProvider,
    @Provided private val positions: ServerPositionLocalSource,
) : PositionsDataSource {
    override suspend fun metadata(serverId: String, bookUuid: String): PositionsMetadata {
        val title = repositories.getBooksRepository(serverId)?.getBook(bookUuid)?.first()
            ?.fold(success = { it.title }, failure = { "" }).orEmpty()
        return PositionsMetadata(title, identity.positionDeviceName(),
            registry.getAllServers().associate { it.id to it.name })
    }
    override suspend fun load(serverId: String, bookUuid: String) = observe(serverId, bookUuid)
    override suspend fun preview(source: CopyPositionRow, rows: List<CopyPositionRow>) = preview.invoke(source, rows)
    override suspend fun apply(source: CopyPositionRow, previews: List<ApplyPreview>) = apply.invoke(source, previews)
    override fun changes(): Flow<Unit> = positions.observeAllPositions()
        .distinctUntilChanged().map { Unit }
}
