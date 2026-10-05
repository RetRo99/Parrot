package com.retro99.reader.domain.usecase

import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.github.michaelbull.result.Err
import com.retro99.base.result.AppError
import com.retro99.books.domain.BookLinksRepository
import com.retro99.books.domain.model.*
import com.retro99.server.api.AuthenticatedRepositoryProvider
import com.retro99.server.api.ServerRegistry
import com.retro99.server.api.ServerType
import kotlinx.coroutines.flow.*
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

data class SeriesBrowseSnapshot(
    val series: List<SeriesBrowseEntry>,
    val failures: List<SeriesSourceFailure>,
    val connectedServerIds: Set<String>,
    val sourceNames: Map<String, String> = emptyMap(),
)

/** Partial source failure is data, not an empty successful catalogue. */
@Factory
class ObserveSeriesBrowseUseCase(
    @Provided private val repositories: AuthenticatedRepositoryProvider,
    @Provided private val progress: ObserveAllBooksWithProgressUseCase,
    @Provided private val links: BookLinksRepository,
    @Provided private val registry: ServerRegistry,
) {
    private val lastKnownSeries = mutableMapOf<String, List<SeriesDomainModel>>()
    operator fun invoke(): Flow<SeriesBrowseSnapshot> = repositories.observeSeriesRepositories()
        .flatMapLatest { sources ->
            val catalogues = sources.map { source -> source.getSeries().catch { emit(Err(AppError.UnknownError(it))) }.map { result ->
                val failures = mutableListOf<SeriesSourceFailure>()
                result.onFailure { failures += SeriesSourceFailure(source.serverId, it) }
                val series = result.getOrElse { emptyList() }.map { record ->
                    SeriesDomainModel(record.uuid, record.name, record.featured, record.position,
                        record.createdAt, record.updatedAt, listOf(SeriesSource(source.serverId, record.uuid)))
                }
                result.onSuccess { lastKnownSeries[source.serverId] = series }
                (if (failures.isEmpty()) series else lastKnownSeries[source.serverId].orEmpty()) to failures
            } }
            val catalogue = if (catalogues.isEmpty()) flowOf(emptyList<SeriesDomainModel>() to emptyList<SeriesSourceFailure>())
                else combine(catalogues) { results -> results.flatMap { it.first } to results.flatMap { it.second } }
            combine(catalogue, progress.observeSnapshot(groupLinked = false), links.observeLinks(), registry.observeAllServers()) { records, books, groups, servers ->
                SeriesBrowseSnapshot(
                    buildSeriesBrowse(records.first, books.books, groups),
                    (records.second + books.failures).distinctBy { it.serverId },
                    servers.filter { it.type == ServerType.Storyteller || it.type == ServerType.Audiobookshelf }.map { it.id }.toSet(),
                    servers.associate { it.id to it.name },
                )
            }
        }
}
