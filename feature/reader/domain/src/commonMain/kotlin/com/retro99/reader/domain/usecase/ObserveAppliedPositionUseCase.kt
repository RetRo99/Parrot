package com.retro99.reader.domain.usecase

import com.retro99.database.api.books.PositionDatabase
import com.retro99.reader.domain.linked.observedAtMillis
import com.retro99.reader.domain.model.PositionDomainModel
import com.retro99.reader.domain.model.toPositionDomainModel
import com.retro99.server.api.PositionOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Positions the person applied to this copy from the positions panel after [afterMillis].
 * The open reader moves there (§1.3b: applying to the open copy moves the reader).
 */
@Factory
class ObserveAppliedPositionUseCase(
    @Provided private val positionDatabase: PositionDatabase,
) {
    operator fun invoke(
        serverId: String,
        bookUuid: String,
        afterMillis: Long,
    ): Flow<PositionDomainModel> = positionDatabase.observePositionByBookUuid(bookUuid)
        .mapNotNull { entity -> entity?.toPositionDomainModel(serverId) }
        .filter { position ->
            position.origin == PositionOrigin.Manual &&
                (position.observedAtMillis ?: 0L) > afterMillis
        }
        .distinctUntilChanged()
}
