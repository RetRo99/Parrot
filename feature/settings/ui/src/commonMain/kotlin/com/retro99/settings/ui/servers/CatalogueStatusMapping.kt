package com.retro99.settings.ui.servers

import com.retro99.server.api.*
import com.retro99.settings.ui.servers.model.ServerUiModel
import com.retro99.settings.ui.servers.model.toUiModel

/** Kept separate from the legacy auth-only cards until Phase 4 supplies catalogue screens. */
data class CatalogueSourceUiModel(val server: ServerUiModel, val status: CatalogueAccessStatus) {
    val offersSignInAgain: Boolean get() = false
}

internal fun mapCatalogueSource(source: ServerConfig, status: CatalogueAccessStatus): CatalogueSourceUiModel =
    CatalogueSourceUiModel(source.toUiModel(), if (source.enabled) status else status.copy(access = ServerAccessState.TurnedOff))
