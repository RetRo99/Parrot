package com.retro99.catalogue.ui.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retro99.server.api.CatalogueAccessStore
import com.retro99.server.api.CatalogueErrorKind
import com.retro99.server.api.OpdsCredentialStore
import com.retro99.server.api.ServerAccessState
import com.retro99.server.api.ServerConfig
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import com.retro99.user.api.UserRegistry
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.ui.add.activeOrFailedCatalogueDownloads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.Provided
import resources.catalogue.ui.Res

data class CatalogueSourceRow(
    val config: ServerConfig,
    val host: String,
    val account: CatalogueAccountState,
    val lastError: CatalogueErrorKind?,
)

sealed interface CatalogueAccountState {
    data object Public : CatalogueAccountState
    data class SignedIn(val username: String) : CatalogueAccountState
    data object SignInNeeded : CatalogueAccountState
    data object SignInUnsupported : CatalogueAccountState
    data object TurnedOff : CatalogueAccountState
}

data class CatalogueSourcesViewState(
    val catalogues: List<CatalogueSourceRow> = emptyList(),
    val presets: List<CataloguePreset> = emptyList(),
    val isLoadingPresets: Boolean = true,
    val activeOrFailedDownloads: Int = 0,
)

@KoinViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueSourcesViewModel(
    @Provided private val registry: ServerRegistry,
    @Provided private val access: CatalogueAccessStore,
    @Provided private val credentials: OpdsCredentialStore,
    @Provided private val users: UserRegistry,
    @Provided private val acquisitions: CatalogueAcquisitionManager,
) : ViewModel() {
    private val presetState = MutableStateFlow<List<CataloguePreset>?>(null)
    private val _state = MutableStateFlow(CatalogueSourcesViewState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val sources = users.observeActiveProfile()
                .distinctUntilChanged()
                .flatMapLatest { profile ->
                    if (profile == null) {
                        flowOf(emptyList())
                    } else {
                        combine(registry.observeAllServers(), access.observe(profile.id)) { servers, statuses ->
                            servers.filter { it.type == ServerType.Opds }.map { config ->
                                val status = statuses[config.id]
                                val account = when (val accessState = status?.access ?: ServerAccessState.Public) {
                                    is ServerAccessState.SignedIn -> CatalogueAccountState.SignedIn(accessState.name)
                                    ServerAccessState.SignInNeeded -> CatalogueAccountState.SignInNeeded
                                    ServerAccessState.SignInUnsupported -> CatalogueAccountState.SignInUnsupported
                                    ServerAccessState.TurnedOff -> CatalogueAccountState.TurnedOff
                                    ServerAccessState.Public -> credentials.get(profile.id, config.id)?.let {
                                        CatalogueAccountState.SignedIn(it.username)
                                    } ?: CatalogueAccountState.Public
                                }
                                CatalogueSourceRow(
                                    config = config,
                                    host = catalogueDisplayHost(config.baseUrl),
                                    account = account,
                                    lastError = status?.lastCheck?.lastError,
                                )
                            }
                        }
                    }
                }
            combine(sources, presetState, acquisitions.observeAcquisitions()) { catalogues, presets, downloads ->
                CatalogueSourcesViewState(
                    catalogues = catalogues,
                    presets = availableCataloguePresets(presets.orEmpty(), catalogues.mapTo(mutableSetOf()) { it.config.baseUrl }),
                    isLoadingPresets = presets == null,
                    activeOrFailedDownloads = activeOrFailedCatalogueDownloads(downloads.map { it.state }),
                )
            }.collect { _state.value = it }
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoadingPresets = true)
            presetState.value = loadCataloguePresetData()
        }
    }

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun loadCataloguePresetData(): List<CataloguePreset> = try {
        parseCataloguePresets(Res.readBytes("files/catalogue-presets.json").decodeToString())
    } catch (_: Exception) {
        emptyList()
    }
}
