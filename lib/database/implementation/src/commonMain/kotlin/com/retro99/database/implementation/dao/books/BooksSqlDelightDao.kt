package com.retro99.database.implementation.dao.books

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.retro99.database.api.sync.SyncOutboxEntry
import com.retro99.database.implementation.DatabaseManager
import com.retro99.database.implementation.Position
import com.retro99.database.implementation.Remote_position
import com.retro99.database.implementation.dao.sync.enqueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * SQLDelight DAO for books table operations.
 */
internal class BooksSqlDelightDao(
    private val databaseManager: DatabaseManager,
) {
    private val database get() = databaseManager.getDatabase()
    private val bookQueries get() = database.bookQueries
    private val personQueries get() = database.personQueries
    private val seriesQueries get() = database.seriesQueries
    private val tagQueries get() = database.tagQueries
    private val collectionQueries get() = database.collectionQueries
    private val statusQueries get() = database.statusQueries
    private val bookAuthorQueries get() = database.bookAuthorQueries
    private val bookNarratorQueries get() = database.bookNarratorQueries
    private val bookCreatorQueries get() = database.bookCreatorQueries
    private val bookSeriesQueries get() = database.bookSeriesQueries
    private val bookTagQueries get() = database.bookTagQueries
    private val bookCollectionQueries get() = database.bookCollectionQueries
    private val mediaFileQueries get() = database.mediaFileQueries
    private val readaloudQueries get() = database.readaloudQueries
    private val positionQueries get() = database.positionQueries
    private val syncOutboxQueries get() = database.syncOutboxQueries

    // ==================== BOOK OPERATIONS ====================

    suspend fun upsertBook(book: BookSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            bookQueries.upsertBook(
                uuid = book.uuid,
                server_id = book.serverId,
                server_type = book.serverType,
                id = book.id,
                title = book.title,
                subtitle = book.subtitle,
                language = book.language,
                publication_date = book.publicationDate,
                description = book.description,
                rating = book.rating?.toDouble(),
                suffix = book.suffix,
                status_uuid = book.statusUuid,
                cover_url = book.coverUrl,
                created_at = book.createdAt,
                updated_at = book.updatedAt,
                isbn = book.isbn,
                asin = book.asin,
                audio_duration_ms = book.audioDurationMs,
                audio_track_durations_ms = encodeTrackDurations(book.audioTrackDurationsMs),
            )
        }
    }

    suspend fun getAllBooks(): List<BookSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookQueries.getAllBooks().executeAsList().map { row ->
                BookSqlDelightEntity(
                    uuid = row.uuid,
                    serverId = row.server_id,
                    serverType = row.server_type,
                    id = row.id,
                    title = row.title,
                    subtitle = row.subtitle,
                    language = row.language,
                    publicationDate = row.publication_date,
                    description = row.description,
                    rating = row.rating?.toFloat(),
                    suffix = row.suffix,
                    statusUuid = row.status_uuid,
                    coverUrl = row.cover_url,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                    isbn = row.isbn,
                    asin = row.asin,
                    audioDurationMs = row.audio_duration_ms,
                    audioTrackDurationsMs = decodeTrackDurations(row.audio_track_durations_ms),
                )
            }
        }
    }

    suspend fun getBookByUuid(uuid: String): BookSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            bookQueries.getBookByUuid(uuid).executeAsOneOrNull()?.let { row ->
                BookSqlDelightEntity(
                    uuid = row.uuid,
                    serverId = row.server_id,
                    serverType = row.server_type,
                    id = row.id,
                    title = row.title,
                    subtitle = row.subtitle,
                    language = row.language,
                    publicationDate = row.publication_date,
                    description = row.description,
                    rating = row.rating?.toFloat(),
                    suffix = row.suffix,
                    statusUuid = row.status_uuid,
                    coverUrl = row.cover_url,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                    isbn = row.isbn,
                    asin = row.asin,
                    audioDurationMs = row.audio_duration_ms,
                    audioTrackDurationsMs = decodeTrackDurations(row.audio_track_durations_ms),
                )
            }
        }
    }

    suspend fun getBooksByServer(serverId: String): List<BookSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookQueries.getBooksByServer(serverId).executeAsList().map { row ->
                BookSqlDelightEntity(
                    uuid = row.uuid,
                    serverId = row.server_id,
                    serverType = row.server_type,
                    id = row.id,
                    title = row.title,
                    subtitle = row.subtitle,
                    language = row.language,
                    publicationDate = row.publication_date,
                    description = row.description,
                    rating = row.rating?.toFloat(),
                    suffix = row.suffix,
                    statusUuid = row.status_uuid,
                    coverUrl = row.cover_url,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                    isbn = row.isbn,
                    asin = row.asin,
                    audioDurationMs = row.audio_duration_ms,
                    audioTrackDurationsMs = decodeTrackDurations(row.audio_track_durations_ms),
                )
            }
        }
    }

    suspend fun getBookByServerAndUuid(serverId: String, uuid: String): BookSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            bookQueries.getBookByServerAndUuid(serverId, uuid).executeAsOneOrNull()?.let { row ->
                BookSqlDelightEntity(
                    uuid = row.uuid,
                    serverId = row.server_id,
                    serverType = row.server_type,
                    id = row.id,
                    title = row.title,
                    subtitle = row.subtitle,
                    language = row.language,
                    publicationDate = row.publication_date,
                    description = row.description,
                    rating = row.rating?.toFloat(),
                    suffix = row.suffix,
                    statusUuid = row.status_uuid,
                    coverUrl = row.cover_url,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                    isbn = row.isbn,
                    asin = row.asin,
                    audioDurationMs = row.audio_duration_ms,
                    audioTrackDurationsMs = decodeTrackDurations(row.audio_track_durations_ms),
                )
            }
        }
    }

    suspend fun deleteAllBooks() {
        withContext(Dispatchers.IO) {
            bookQueries.deleteAllBooks()
        }
    }

    suspend fun deleteBook(uuid: String) {
        withContext(Dispatchers.IO) {
            bookQueries.deleteBook(uuid)
        }
    }

    suspend fun deleteBooksByServer(serverId: String) {
        withContext(Dispatchers.IO) {
            bookQueries.deleteBooksByServer(serverId)
        }
    }

    suspend fun getBooksCount(): Long {
        return withContext(Dispatchers.IO) {
            bookQueries.getBooksCount().executeAsOne()
        }
    }

    suspend fun getBooksCountByServer(serverId: String): Long {
        return withContext(Dispatchers.IO) {
            bookQueries.getBooksCountByServer(serverId).executeAsOne()
        }
    }

    // ==================== PERSON OPERATIONS ====================

    suspend fun upsertPerson(person: PersonSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            personQueries.upsertPerson(
                uuid = person.uuid,
                name = person.name,
                file_as = person.fileAs,
                created_at = person.createdAt,
                updated_at = person.updatedAt,
            )
        }
    }

    suspend fun getPersonByUuid(uuid: String): PersonSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            personQueries.getPersonByUuid(uuid).executeAsOneOrNull()?.let { row ->
                PersonSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    fileAs = row.file_as,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun getAuthorsByBookUuid(bookUuid: String): List<PersonSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookAuthorQueries.getAuthorsByBookUuid(bookUuid).executeAsList().map { row ->
                PersonSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    fileAs = row.file_as,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun getNarratorsByBookUuid(bookUuid: String): List<PersonSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookNarratorQueries.getNarratorsByBookUuid(bookUuid).executeAsList().map { row ->
                PersonSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    fileAs = row.file_as,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun getCreatorsByBookUuid(bookUuid: String): List<PersonSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookCreatorQueries.getCreatorsByBookUuid(bookUuid).executeAsList().map { row ->
                PersonSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    fileAs = row.file_as,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun insertBookAuthor(bookUuid: String, personUuid: String) {
        withContext(Dispatchers.IO) {
            bookAuthorQueries.insertBookAuthor(bookUuid, personUuid)
        }
    }

    suspend fun insertBookNarrator(bookUuid: String, personUuid: String) {
        withContext(Dispatchers.IO) {
            bookNarratorQueries.insertBookNarrator(bookUuid, personUuid)
        }
    }

    suspend fun insertBookCreator(bookUuid: String, personUuid: String) {
        withContext(Dispatchers.IO) {
            bookCreatorQueries.insertBookCreator(bookUuid, personUuid)
        }
    }

    suspend fun deleteBookAuthors(bookUuid: String) {
        withContext(Dispatchers.IO) {
            bookAuthorQueries.deleteBookAuthors(bookUuid)
        }
    }

    suspend fun deleteBookNarrators(bookUuid: String) {
        withContext(Dispatchers.IO) {
            bookNarratorQueries.deleteBookNarrators(bookUuid)
        }
    }

    suspend fun deleteBookCreators(bookUuid: String) {
        withContext(Dispatchers.IO) {
            bookCreatorQueries.deleteBookCreators(bookUuid)
        }
    }

    // ==================== SERIES OPERATIONS ====================

    suspend fun upsertSeries(series: SeriesSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            seriesQueries.upsertSeries(
                uuid = series.uuid,
                name = series.name,
                featured = series.featured?.toLong(),
                created_at = series.createdAt,
                updated_at = series.updatedAt,
            )
        }
    }

    suspend fun getSeriesByBookUuid(bookUuid: String): List<BookSeriesSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookSeriesQueries.getSeriesByBookUuid(bookUuid).executeAsList().map { row ->
                BookSeriesSqlDelightEntity(
                    bookUuid = bookUuid,
                    seriesUuid = row.uuid,
                    position = row.position,
                )
            }
        }
    }

    suspend fun getSeriesEntityByUuid(uuid: String): SeriesSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            seriesQueries.getSeriesByUuid(uuid).executeAsOneOrNull()?.let { row ->
                SeriesSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    featured = row.featured?.toInt(),
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun insertBookSeries(bookUuid: String, seriesUuid: String, position: Double?) {
        withContext(Dispatchers.IO) {
            bookSeriesQueries.insertBookSeries(bookUuid, seriesUuid, position?.toDouble())
        }
    }

    suspend fun deleteBookSeries(bookUuid: String) {
        withContext(Dispatchers.IO) {
            bookSeriesQueries.deleteBookSeries(bookUuid)
        }
    }

    suspend fun getAllSeries(): List<SeriesSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            seriesQueries.getAllSeries().executeAsList().map { row ->
                SeriesSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    featured = row.featured?.toInt(),
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun deleteAllSeries() {
        withContext(Dispatchers.IO) {
            seriesQueries.deleteAllSeries()
        }
    }

    // ==================== TAG OPERATIONS ====================

    suspend fun upsertTag(tag: TagSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            tagQueries.upsertTag(
                uuid = tag.uuid,
                name = tag.name,
                created_at = tag.createdAt,
                updated_at = tag.updatedAt,
            )
        }
    }

    suspend fun getTagsByBookUuid(bookUuid: String): List<TagSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookTagQueries.getTagsByBookUuid(bookUuid).executeAsList().map { row ->
                TagSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun insertBookTag(bookUuid: String, tagUuid: String) {
        withContext(Dispatchers.IO) {
            bookTagQueries.insertBookTag(bookUuid, tagUuid)
        }
    }

    suspend fun deleteBookTags(bookUuid: String) {
        withContext(Dispatchers.IO) {
            bookTagQueries.deleteBookTags(bookUuid)
        }
    }

    // ==================== COLLECTION OPERATIONS ====================

    suspend fun upsertCollection(collection: CollectionSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            collectionQueries.upsertCollection(
                uuid = collection.uuid,
                name = collection.name,
                created_at = collection.createdAt,
                updated_at = collection.updatedAt,
            )
        }
    }

    suspend fun getCollectionsByBookUuid(bookUuid: String): List<CollectionSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            bookCollectionQueries.getCollectionsByBookUuid(bookUuid).executeAsList().map { row ->
                CollectionSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun insertBookCollection(bookUuid: String, collectionUuid: String) {
        withContext(Dispatchers.IO) {
            bookCollectionQueries.insertBookCollection(bookUuid, collectionUuid)
        }
    }

    suspend fun deleteBookCollections(bookUuid: String) {
        withContext(Dispatchers.IO) {
            bookCollectionQueries.deleteBookCollections(bookUuid)
        }
    }

    // ==================== STATUS OPERATIONS ====================

    suspend fun upsertStatus(status: StatusSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            statusQueries.upsertStatus(
                uuid = status.uuid,
                name = status.name,
                created_at = status.createdAt,
                updated_at = status.updatedAt,
            )
        }
    }

    suspend fun getStatusByUuid(uuid: String): StatusSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            statusQueries.getStatusByUuid(uuid).executeAsOneOrNull()?.let { row ->
                StatusSqlDelightEntity(
                    uuid = row.uuid,
                    name = row.name,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    // ==================== MEDIA FILE OPERATIONS ====================

    suspend fun upsertMediaFile(mediaFile: MediaFileSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            mediaFileQueries.upsertMediaFile(
                uuid = mediaFile.uuid,
                book_uuid = mediaFile.bookUuid,
                type = mediaFile.type,
                filepath = mediaFile.filepath,
                missing = mediaFile.missing?.toLong(),
                size = mediaFile.size,
                created_at = mediaFile.createdAt,
                updated_at = mediaFile.updatedAt,
            )
        }
    }

    suspend fun getMediaFilesByBookUuid(bookUuid: String): List<MediaFileSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            mediaFileQueries.getMediaFilesByBookUuid(bookUuid).executeAsList().map { row ->
                MediaFileSqlDelightEntity(
                    uuid = row.uuid,
                    bookUuid = row.book_uuid,
                    type = row.type,
                    filepath = row.filepath,
                    missing = row.missing?.toInt(),
                    size = row.size,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun deleteMediaFilesByBookUuid(bookUuid: String) {
        withContext(Dispatchers.IO) {
            mediaFileQueries.deleteMediaFilesByBookUuid(bookUuid)
        }
    }

    // ==================== READALOUD OPERATIONS ====================

    suspend fun upsertReadaloud(readaloud: ReadaloudSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            readaloudQueries.upsertReadaloud(
                uuid = readaloud.uuid,
                book_uuid = readaloud.bookUuid,
                filepath = readaloud.filepath,
                missing = readaloud.missing?.toLong(),
                status = readaloud.status,
                current_stage = readaloud.currentStage,
                stage_progress = readaloud.stageProgress,
                queue_position = readaloud.queuePosition?.toLong(),
                restart_pending = if (readaloud.restartPending == true) 1L else 0L,
                created_at = readaloud.createdAt,
                updated_at = readaloud.updatedAt,
            )
        }
    }

    suspend fun getReadaloudByBookUuid(bookUuid: String): ReadaloudSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            readaloudQueries.getReadaloudByBookUuid(bookUuid).executeAsOneOrNull()?.let { row ->
                ReadaloudSqlDelightEntity(
                    uuid = row.uuid,
                    bookUuid = row.book_uuid,
                    filepath = row.filepath,
                    missing = row.missing?.toInt(),
                    status = row.status,
                    currentStage = row.current_stage,
                    stageProgress = row.stage_progress,
                    queuePosition = row.queue_position?.toInt(),
                    restartPending = row.restart_pending == 1L,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                )
            }
        }
    }

    suspend fun deleteReadaloudByBookUuid(bookUuid: String) {
        withContext(Dispatchers.IO) {
            readaloudQueries.deleteReadaloudByBookUuid(bookUuid)
        }
    }

    // ==================== POSITION OPERATIONS ====================

    suspend fun upsertPosition(position: PositionSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            insertPosition(position)
        }
    }

    private fun insertPosition(position: PositionSqlDelightEntity) {
        positionQueries.upsertPosition(
            book_uuid = position.bookUuid,
            library_book_id = position.bookUuid,
            local_generation = position.localGeneration,
            remote_revision = position.remoteRevision,
            timestamp = position.timestamp,
            created_at = position.createdAt,
            updated_at = position.updatedAt,
            locator_href = position.locatorHref,
            locator_type = position.locatorType,
            locator_title = position.locatorTitle,
            locator_target = position.locatorTarget?.toLong(),
            css_selector = position.cssSelector,
            audio_timestamp_ms = position.audioTimestampMs,
            chapter_index = position.chapterIndex?.toLong(),
            progression = position.progression,
            total_chapters = position.totalChapters?.toLong(),
            total_duration_ms = position.totalDurationMs,
            total_progression = position.totalProgression,
            book_time_ms = position.bookTimeMs,
            ebook_location_raw = position.ebookLocationRaw,
            source_device_id = position.sourceDeviceId,
            device_name = position.deviceName,
            position = position.position?.toLong(),
            origin = position.origin,
            observed_at = position.observedAt,
            text_anchor = position.textAnchor,
        )
    }

    suspend fun resolvePositionConflict(
        position: PositionSqlDelightEntity,
        mutation: SyncOutboxEntry?,
        expectedLocalGeneration: Long,
        destinationId: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        database.transactionWithResult {
            val stored = positionQueries.getPositionByBookUuid(position.bookUuid).executeAsOneOrNull()
            if (stored == null || stored.local_generation != expectedLocalGeneration) {
                return@transactionWithResult false
            }
            insertPosition(position)
            syncOutboxQueries.deleteSupersededProgress(position.bookUuid, destinationId)
            positionQueries.deleteRemotePosition(position.bookUuid)
            mutation?.let(syncOutboxQueries::enqueue)
            true
        }
    }

    suspend fun upsertPositionWithMutation(
        position: PositionSqlDelightEntity,
        mutation: SyncOutboxEntry,
    ) {
        withContext(Dispatchers.IO) {
            database.transaction {
                // Two writers can read the same generation before entering this transaction.
                // Allocate it here so a stale conflict response can never match a newer save.
                val stored = positionQueries.getPositionByBookUuid(position.bookUuid).executeAsOneOrNull()
                val generation = maxOf(
                    position.localGeneration,
                    (stored?.local_generation ?: 0L) + 1L,
                )
                insertPosition(position.copy(localGeneration = generation))
                syncOutboxQueries.enqueue(mutation.copy(localGeneration = generation))
            }
        }
    }

    suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) {
        withContext(Dispatchers.IO) {
            if (expectedLocalGeneration == null) {
                positionQueries.updateRemoteRevision(remoteRevision, bookUuid)
            } else {
                positionQueries.updateRemoteRevisionIfGeneration(
                    remoteRevision,
                    bookUuid,
                    expectedLocalGeneration,
                )
            }
        }
    }

    suspend fun upsertRemotePosition(position: PositionSqlDelightEntity) {
        withContext(Dispatchers.IO) {
            positionQueries.upsertRemotePosition(
                book_uuid = position.bookUuid,
                library_book_id = position.libraryBookId,
                remote_revision = position.remoteRevision,
                timestamp = position.timestamp,
                created_at = position.createdAt,
                updated_at = position.updatedAt,
                locator_href = position.locatorHref,
                locator_type = position.locatorType,
                locator_title = position.locatorTitle,
                locator_target = position.locatorTarget?.toLong(),
                css_selector = position.cssSelector,
                audio_timestamp_ms = position.audioTimestampMs,
                chapter_index = position.chapterIndex?.toLong(),
                progression = position.progression,
                total_chapters = position.totalChapters?.toLong(),
                total_duration_ms = position.totalDurationMs,
                total_progression = position.totalProgression,
                book_time_ms = position.bookTimeMs,
                ebook_location_raw = position.ebookLocationRaw,
                source_device_id = position.sourceDeviceId,
                device_name = position.deviceName,
                position = position.position?.toLong(),
            )
        }
    }

    suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            positionQueries.getRemotePositionByBookUuid(bookUuid)
                .executeAsOneOrNull()
                ?.toPositionEntity()
        }
    }

    suspend fun deleteRemotePosition(bookUuid: String) {
        withContext(Dispatchers.IO) {
            positionQueries.deleteRemotePosition(bookUuid)
        }
    }

    suspend fun getPositionByBookUuid(bookUuid: String): PositionSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            positionQueries.getPositionByBookUuid(bookUuid)
                .executeAsOneOrNull()?.let { row ->
                    PositionSqlDelightEntity(
                        bookUuid = row.book_uuid,
                        libraryBookId = row.library_book_id,
                        localGeneration = row.local_generation,
                        remoteRevision = row.remote_revision,
                        timestamp = row.timestamp,
                        createdAt = row.created_at,
                        updatedAt = row.updated_at,
                        locatorHref = row.locator_href,
                        locatorType = row.locator_type,
                        locatorTitle = row.locator_title,
                        locatorTarget = row.locator_target?.toInt(),
                        cssSelector = row.css_selector,
                        audioTimestampMs = row.audio_timestamp_ms,
                        chapterIndex = row.chapter_index?.toInt(),
                        progression = row.progression,
                        totalChapters = row.total_chapters?.toInt(),
                        totalDurationMs = row.total_duration_ms,
                        totalProgression = row.total_progression,
                        bookTimeMs = row.book_time_ms,
                        ebookLocationRaw = row.ebook_location_raw,
                        position = row.position?.toInt(),
                        origin = row.origin,
                        observedAt = row.observed_at,
                        textAnchor = row.text_anchor,
                        sourceDeviceId = row.source_device_id,
                        deviceName = row.device_name,
                    )
                }
        }
    }

    suspend fun getPositionByLibraryBookId(libraryBookId: String): PositionSqlDelightEntity? {
        return withContext(Dispatchers.IO) {
            positionQueries.getPositionByLibraryBookId(libraryBookId)
                .executeAsOneOrNull()
                ?.toPositionEntity()
        }
    }

    suspend fun deletePosition(bookUuid: String) {
        withContext(Dispatchers.IO) {
            positionQueries.deletePosition(bookUuid)
        }
    }

    suspend fun getAllPositions(): List<PositionSqlDelightEntity> {
        return withContext(Dispatchers.IO) {
            positionQueries.getAllPositions().executeAsList().map { row ->
                PositionSqlDelightEntity(
                    bookUuid = row.book_uuid,
                    libraryBookId = row.library_book_id,
                    localGeneration = row.local_generation,
                    remoteRevision = row.remote_revision,
                    timestamp = row.timestamp,
                    createdAt = row.created_at,
                    updatedAt = row.updated_at,
                    locatorHref = row.locator_href,
                    locatorType = row.locator_type,
                    locatorTitle = row.locator_title,
                    locatorTarget = row.locator_target?.toInt(),
                    cssSelector = row.css_selector,
                    audioTimestampMs = row.audio_timestamp_ms,
                    chapterIndex = row.chapter_index?.toInt(),
                    progression = row.progression,
                    totalChapters = row.total_chapters?.toInt(),
                    totalDurationMs = row.total_duration_ms,
                    totalProgression = row.total_progression,
                    bookTimeMs = row.book_time_ms,
                    ebookLocationRaw = row.ebook_location_raw,
                    position = row.position?.toInt(),
                    origin = row.origin,
                    observedAt = row.observed_at,
                    textAnchor = row.text_anchor,
                    sourceDeviceId = row.source_device_id,
                    deviceName = row.device_name,
                )
            }
        }
    }

    /**
     * Observes position changes for a specific book.
     * Emits whenever the position for this book is updated in the database.
     */
    fun observePositionByBookUuid(bookUuid: String): Flow<PositionSqlDelightEntity?> {
        return positionQueries.getPositionByBookUuid(bookUuid)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { row ->
                row?.let {
                    PositionSqlDelightEntity(
                        bookUuid = it.book_uuid,
                        libraryBookId = it.library_book_id,
                        localGeneration = it.local_generation,
                        remoteRevision = it.remote_revision,
                        timestamp = it.timestamp,
                        createdAt = it.created_at,
                        updatedAt = it.updated_at,
                        locatorHref = it.locator_href,
                        locatorType = it.locator_type,
                        locatorTitle = it.locator_title,
                        locatorTarget = it.locator_target?.toInt(),
                        cssSelector = it.css_selector,
                        audioTimestampMs = it.audio_timestamp_ms,
                        chapterIndex = it.chapter_index?.toInt(),
                        progression = it.progression,
                        totalChapters = it.total_chapters?.toInt(),
                        totalDurationMs = it.total_duration_ms,
                        totalProgression = it.total_progression,
                        bookTimeMs = it.book_time_ms,
                        ebookLocationRaw = it.ebook_location_raw,
                        position = it.position?.toInt(),
                        origin = it.origin,
                        observedAt = it.observed_at,
                        textAnchor = it.text_anchor,
                        sourceDeviceId = it.source_device_id,
                        deviceName = it.device_name,
                    )
                }
            }
    }

    fun observePositionByLibraryBookId(libraryBookId: String): Flow<PositionSqlDelightEntity?> {
        return positionQueries.getPositionByLibraryBookId(libraryBookId)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { row -> row?.toPositionEntity() }
    }

    /**
     * Observes all position changes.
     * Emits whenever any position is updated in the database.
     */
    fun observeAllPositions(): Flow<List<PositionSqlDelightEntity>> {
        return positionQueries.getAllPositions()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list ->
                list.map { row ->
                    PositionSqlDelightEntity(
                        bookUuid = row.book_uuid,
                        libraryBookId = row.library_book_id,
                        localGeneration = row.local_generation,
                        remoteRevision = row.remote_revision,
                        timestamp = row.timestamp,
                        createdAt = row.created_at,
                        updatedAt = row.updated_at,
                        locatorHref = row.locator_href,
                        locatorType = row.locator_type,
                        locatorTitle = row.locator_title,
                        locatorTarget = row.locator_target?.toInt(),
                        cssSelector = row.css_selector,
                        audioTimestampMs = row.audio_timestamp_ms,
                        chapterIndex = row.chapter_index?.toInt(),
                        progression = row.progression,
                        totalChapters = row.total_chapters?.toInt(),
                        totalDurationMs = row.total_duration_ms,
                        totalProgression = row.total_progression,
                        bookTimeMs = row.book_time_ms,
                        ebookLocationRaw = row.ebook_location_raw,
                        position = row.position?.toInt(),
                        origin = row.origin,
                        observedAt = row.observed_at,
                        textAnchor = row.text_anchor,
                        sourceDeviceId = row.source_device_id,
                        deviceName = row.device_name,
                    )
                }
            }
    }

    private fun Position.toPositionEntity(): PositionSqlDelightEntity {
        return PositionSqlDelightEntity(
            bookUuid = book_uuid,
            libraryBookId = library_book_id,
            localGeneration = local_generation,
            remoteRevision = remote_revision,
            timestamp = timestamp,
            createdAt = created_at,
            updatedAt = updated_at,
            locatorHref = locator_href,
            locatorType = locator_type,
            locatorTitle = locator_title,
            locatorTarget = locator_target?.toInt(),
            cssSelector = css_selector,
            audioTimestampMs = audio_timestamp_ms,
            chapterIndex = chapter_index?.toInt(),
            progression = progression,
            totalChapters = total_chapters?.toInt(),
            totalDurationMs = total_duration_ms,
            totalProgression = total_progression,
            bookTimeMs = book_time_ms,
            ebookLocationRaw = ebook_location_raw,
            position = position?.toInt(),
            origin = origin,
            observedAt = observed_at,
            textAnchor = text_anchor,
            sourceDeviceId = source_device_id,
            deviceName = device_name,
        )
    }

    private fun Remote_position.toPositionEntity(): PositionSqlDelightEntity {
        return PositionSqlDelightEntity(
            bookUuid = book_uuid,
            libraryBookId = library_book_id,
            remoteRevision = remote_revision,
            timestamp = timestamp,
            createdAt = created_at,
            updatedAt = updated_at,
            locatorHref = locator_href,
            locatorType = locator_type,
            locatorTitle = locator_title,
            locatorTarget = locator_target?.toInt(),
            cssSelector = css_selector,
            audioTimestampMs = audio_timestamp_ms,
            chapterIndex = chapter_index?.toInt(),
            progression = progression,
            totalChapters = total_chapters?.toInt(),
            totalDurationMs = total_duration_ms,
            totalProgression = total_progression,
            bookTimeMs = book_time_ms,
            ebookLocationRaw = ebook_location_raw,
            position = position?.toInt(),
            sourceDeviceId = source_device_id,
            deviceName = device_name,
        )
    }

    // ==================== TRANSACTION SUPPORT ====================

    suspend fun <T> transaction(block: suspend () -> T): T {
        return withContext(Dispatchers.IO) {
            database.transactionWithResult {
                kotlinx.coroutines.runBlocking { block() }
            }
        }
    }

    suspend fun deleteAllRelatedData() {
        withContext(Dispatchers.IO) {
            database.transaction {
                bookAuthorQueries.deleteAllBookAuthors()
                bookNarratorQueries.deleteAllBookNarrators()
                bookCreatorQueries.deleteAllBookCreators()
                bookSeriesQueries.deleteAllBookSeries()
                bookTagQueries.deleteAllBookTags()
                bookCollectionQueries.deleteAllBookCollections()
                mediaFileQueries.deleteAllMediaFiles()
                readaloudQueries.deleteAllReadalouds()
                positionQueries.deleteAllPositions()
                positionQueries.deleteAllRemotePositions()
                personQueries.deleteAllPersons()
                seriesQueries.deleteAllSeries()
                tagQueries.deleteAllTags()
                collectionQueries.deleteAllCollections()
                statusQueries.deleteAllStatuses()
                bookQueries.deleteAllBooks()
            }
        }
    }
}
