package com.retro99.database.implementation.dao.books

import com.retro99.database.api.books.BookEntity
import com.retro99.database.api.books.BookSeriesEntity
import com.retro99.database.api.books.BooksDatabase
import com.retro99.database.api.books.CollectionEntity
import com.retro99.database.api.books.MediaFileEntity
import com.retro99.database.api.books.PersonEntity
import com.retro99.database.api.books.PositionDatabase
import com.retro99.database.api.books.PositionEntity
import com.retro99.database.api.books.ReadaloudEntity
import com.retro99.database.api.books.SeriesEntity
import com.retro99.database.api.books.SeriesWithPositionEntity
import com.retro99.database.api.books.StatusEntity
import com.retro99.database.api.books.TagEntity
import com.retro99.database.api.sync.SyncOutboxEntry
import kotlinx.coroutines.flow.Flow

internal class BooksDatabaseImpl(
    private val sqlDelightDao: BooksSqlDelightDao,
) : BooksDatabase, PositionDatabase {

    // ==================== BOOK OPERATIONS ====================

    override suspend fun upsertBook(book: BookEntity) {
        saveBookWithRelations(book)
    }

    override suspend fun getAllBooks(): List<BookEntity> {
        val books = sqlDelightDao.getAllBooks()
        return books.map { book -> loadBookWithRelations(book) }
    }

    override suspend fun getBookByUuid(uuid: String): BookEntity? {
        val book = sqlDelightDao.getBookByUuid(uuid) ?: return null
        return loadBookWithRelations(book)
    }

    override suspend fun deleteAllBooks() {
        sqlDelightDao.deleteAllBooks()
    }

    override suspend fun deleteBook(uuid: String) {
        sqlDelightDao.deleteBook(uuid)
    }

    override suspend fun getBooksCount(): Int {
        return sqlDelightDao.getBooksCount().toInt()
    }

    override suspend fun getBooksByServer(serverId: String): List<BookEntity> {
        val books = sqlDelightDao.getBooksByServer(serverId)
        return books.map { book -> loadBookWithRelations(book) }
    }

    override suspend fun getBookByServerAndUuid(serverId: String, uuid: String): BookEntity? {
        val book = sqlDelightDao.getBookByServerAndUuid(serverId, uuid) ?: return null
        return loadBookWithRelations(book)
    }

    override suspend fun deleteBooksByServer(serverId: String) {
        sqlDelightDao.deleteBooksByServer(serverId)
    }

    override suspend fun getBooksCountByServer(serverId: String): Int {
        return sqlDelightDao.getBooksCountByServer(serverId).toInt()
    }

    // ==================== PERSON OPERATIONS ====================

    override suspend fun upsertPerson(person: PersonEntity) {
        sqlDelightDao.upsertPerson(person.toSqlDelightEntity())
    }

    override suspend fun getAuthorsByBookUuid(bookUuid: String): List<PersonEntity> {
        return sqlDelightDao.getAuthorsByBookUuid(bookUuid)
    }

    override suspend fun getNarratorsByBookUuid(bookUuid: String): List<PersonEntity> {
        return sqlDelightDao.getNarratorsByBookUuid(bookUuid)
    }

    override suspend fun getCreatorsByBookUuid(bookUuid: String): List<PersonEntity> {
        return sqlDelightDao.getCreatorsByBookUuid(bookUuid)
    }

    override suspend fun insertBookAuthor(bookUuid: String, personUuid: String) {
        sqlDelightDao.insertBookAuthor(bookUuid, personUuid)
    }

    override suspend fun insertBookNarrator(bookUuid: String, personUuid: String) {
        sqlDelightDao.insertBookNarrator(bookUuid, personUuid)
    }

    override suspend fun insertBookCreator(bookUuid: String, personUuid: String) {
        sqlDelightDao.insertBookCreator(bookUuid, personUuid)
    }

    override suspend fun deleteBookAuthors(bookUuid: String) {
        sqlDelightDao.deleteBookAuthors(bookUuid)
    }

    override suspend fun deleteBookNarrators(bookUuid: String) {
        sqlDelightDao.deleteBookNarrators(bookUuid)
    }

    override suspend fun deleteBookCreators(bookUuid: String) {
        sqlDelightDao.deleteBookCreators(bookUuid)
    }

    // ==================== SERIES OPERATIONS ====================

    override suspend fun upsertSeries(series: SeriesEntity) {
        sqlDelightDao.upsertSeries(series.toSqlDelightEntity())
    }

    override suspend fun getSeriesByBookUuid(bookUuid: String): List<BookSeriesEntity> {
        return sqlDelightDao.getSeriesByBookUuid(bookUuid)
    }

    override suspend fun getSeriesEntityByUuid(uuid: String): SeriesEntity? {
        return sqlDelightDao.getSeriesEntityByUuid(uuid)
    }

    override suspend fun insertBookSeries(bookUuid: String, seriesUuid: String, position: Double?) {
        sqlDelightDao.insertBookSeries(bookUuid, seriesUuid, position)
    }

    override suspend fun deleteBookSeries(bookUuid: String) {
        sqlDelightDao.deleteBookSeries(bookUuid)
    }

    override suspend fun getAllSeries(): List<SeriesEntity> {
        return sqlDelightDao.getAllSeries()
    }

    override suspend fun deleteAllSeries() {
        sqlDelightDao.deleteAllSeries()
    }

    // ==================== TAG OPERATIONS ====================

    override suspend fun upsertTag(tag: TagEntity) {
        sqlDelightDao.upsertTag(tag.toSqlDelightEntity())
    }

    override suspend fun getTagsByBookUuid(bookUuid: String): List<TagEntity> {
        return sqlDelightDao.getTagsByBookUuid(bookUuid)
    }

    override suspend fun insertBookTag(bookUuid: String, tagUuid: String) {
        sqlDelightDao.insertBookTag(bookUuid, tagUuid)
    }

    override suspend fun deleteBookTags(bookUuid: String) {
        sqlDelightDao.deleteBookTags(bookUuid)
    }

    // ==================== COLLECTION OPERATIONS ====================

    override suspend fun upsertCollection(collection: CollectionEntity) {
        sqlDelightDao.upsertCollection(collection.toSqlDelightEntity())
    }

    override suspend fun getCollectionsByBookUuid(bookUuid: String): List<CollectionEntity> {
        return sqlDelightDao.getCollectionsByBookUuid(bookUuid)
    }

    override suspend fun insertBookCollection(bookUuid: String, collectionUuid: String) {
        sqlDelightDao.insertBookCollection(bookUuid, collectionUuid)
    }

    override suspend fun deleteBookCollections(bookUuid: String) {
        sqlDelightDao.deleteBookCollections(bookUuid)
    }

    // ==================== STATUS OPERATIONS ====================

    override suspend fun upsertStatus(status: StatusEntity) {
        sqlDelightDao.upsertStatus(status.toSqlDelightEntity())
    }

    override suspend fun getStatusByUuid(uuid: String): StatusEntity? {
        return sqlDelightDao.getStatusByUuid(uuid)
    }

    // ==================== MEDIA FILE OPERATIONS ====================

    override suspend fun upsertMediaFile(mediaFile: MediaFileEntity) {
        sqlDelightDao.upsertMediaFile(mediaFile.toSqlDelightEntity())
    }

    override suspend fun getMediaFilesByBookUuid(bookUuid: String): List<MediaFileEntity> {
        return sqlDelightDao.getMediaFilesByBookUuid(bookUuid)
    }

    override suspend fun deleteMediaFilesByBookUuid(bookUuid: String) {
        sqlDelightDao.deleteMediaFilesByBookUuid(bookUuid)
    }

    // ==================== READALOUD OPERATIONS ====================

    override suspend fun upsertReadaloud(readaloud: ReadaloudEntity) {
        sqlDelightDao.upsertReadaloud(readaloud.toSqlDelightEntity())
    }

    override suspend fun getReadaloudByBookUuid(bookUuid: String): ReadaloudEntity? {
        return sqlDelightDao.getReadaloudByBookUuid(bookUuid)
    }

    override suspend fun deleteReadaloudByBookUuid(bookUuid: String) {
        sqlDelightDao.deleteReadaloudByBookUuid(bookUuid)
    }

    // ==================== POSITION OPERATIONS ====================

    override suspend fun upsertPosition(position: PositionEntity) {
        sqlDelightDao.upsertPosition(position.toSqlDelightEntity())
    }

    override suspend fun upsertPositionWithMutation(
        position: PositionEntity,
        mutation: SyncOutboxEntry,
    ) {
        sqlDelightDao.upsertPositionWithMutation(
            position = position.toSqlDelightEntity(),
            mutation = mutation,
        )
    }

    override suspend fun updateRemoteRevision(
        bookUuid: String,
        remoteRevision: Long,
        expectedLocalGeneration: Long?,
    ) {
        sqlDelightDao.updateRemoteRevision(
            bookUuid,
            remoteRevision,
            expectedLocalGeneration,
        )
    }

    override suspend fun upsertRemotePosition(position: PositionEntity) {
        sqlDelightDao.upsertRemotePosition(position.toSqlDelightEntity())
    }

    override suspend fun getRemotePositionByBookUuid(bookUuid: String): PositionEntity? {
        return sqlDelightDao.getRemotePositionByBookUuid(bookUuid)
    }

    override suspend fun deleteRemotePosition(bookUuid: String) {
        sqlDelightDao.deleteRemotePosition(bookUuid)
    }

    override suspend fun getPositionByBookUuid(bookUuid: String): PositionEntity? {
        return sqlDelightDao.getPositionByBookUuid(bookUuid)
    }

    override suspend fun getPositionByLibraryBookId(libraryBookId: String): PositionEntity? {
        return sqlDelightDao.getPositionByLibraryBookId(libraryBookId)
    }

    override suspend fun getAllPositions(): List<PositionEntity> {
        return sqlDelightDao.getAllPositions()
    }

    override suspend fun deletePosition(bookUuid: String) {
        sqlDelightDao.deletePosition(bookUuid)
    }

    override fun observePositionByBookUuid(bookUuid: String): Flow<PositionEntity?> {
        return sqlDelightDao.observePositionByBookUuid(bookUuid)
    }

    override fun observePositionByLibraryBookId(libraryBookId: String): Flow<PositionEntity?> {
        return sqlDelightDao.observePositionByLibraryBookId(libraryBookId)
    }

    override fun observeAllPositions(): Flow<List<PositionEntity>> {
        return sqlDelightDao.observeAllPositions()
    }

    // ==================== TRANSACTION SUPPORT ====================

    override suspend fun clearAllData() {
        sqlDelightDao.deleteAllRelatedData()
    }

    // ==================== ENTITY CONVERSIONS ====================

    private fun PersonEntity.toSqlDelightEntity(): PersonSqlDelightEntity {
        return PersonSqlDelightEntity(
            uuid = uuid,
            name = name,
            fileAs = fileAs,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun SeriesEntity.toSqlDelightEntity(): SeriesSqlDelightEntity {
        return SeriesSqlDelightEntity(
            uuid = uuid,
            name = name,
            featured = featured,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun TagEntity.toSqlDelightEntity(): TagSqlDelightEntity {
        return TagSqlDelightEntity(
            uuid = uuid,
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun CollectionEntity.toSqlDelightEntity(): CollectionSqlDelightEntity {
        return CollectionSqlDelightEntity(
            uuid = uuid,
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun StatusEntity.toSqlDelightEntity(): StatusSqlDelightEntity {
        return StatusSqlDelightEntity(
            uuid = uuid,
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun MediaFileEntity.toSqlDelightEntity(): MediaFileSqlDelightEntity {
        return MediaFileSqlDelightEntity(
            uuid = uuid,
            bookUuid = bookUuid,
            type = type,
            filepath = filepath,
            missing = missing,
            size = size,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun ReadaloudEntity.toSqlDelightEntity(): ReadaloudSqlDelightEntity {
        return ReadaloudSqlDelightEntity(
            uuid = uuid,
            bookUuid = bookUuid,
            filepath = filepath,
            missing = missing,
            status = status,
            currentStage = currentStage,
            stageProgress = stageProgress,
            queuePosition = queuePosition,
            restartPending = restartPending,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun PositionEntity.toSqlDelightEntity(): PositionSqlDelightEntity {
        return PositionSqlDelightEntity(
            bookUuid = bookUuid,
            libraryBookId = libraryBookId,
            localGeneration = localGeneration,
            remoteRevision = remoteRevision,
            timestamp = timestamp,
            createdAt = createdAt,
            updatedAt = updatedAt,
            locatorHref = locatorHref,
            locatorType = locatorType,
            locatorTitle = locatorTitle,
            locatorTarget = locatorTarget,
            cssSelector = cssSelector,
            audioTimestampMs = audioTimestampMs,
            chapterIndex = chapterIndex,
            progression = progression,
            totalChapters = totalChapters,
            totalDurationMs = totalDurationMs,
            totalProgression = totalProgression,
            position = position,
        )
    }

    // ==================== RELATION LOADING ====================

    private suspend fun loadBookWithRelations(
        book: BookSqlDelightEntity,
    ): BookEntity {
        val authors = sqlDelightDao.getAuthorsByBookUuid(book.uuid)
        val narrators = sqlDelightDao.getNarratorsByBookUuid(book.uuid)
        val creators = sqlDelightDao.getCreatorsByBookUuid(book.uuid)
        val seriesRelations = sqlDelightDao.getSeriesByBookUuid(book.uuid)
        val tags = sqlDelightDao.getTagsByBookUuid(book.uuid)
        val collections = sqlDelightDao.getCollectionsByBookUuid(book.uuid)
        val status = book.statusUuid?.let { sqlDelightDao.getStatusByUuid(it) }
        val mediaFiles = sqlDelightDao.getMediaFilesByBookUuid(book.uuid)
        val readaloud = sqlDelightDao.getReadaloudByBookUuid(book.uuid)

        val seriesList = seriesRelations.mapNotNull { relation ->
            sqlDelightDao.getSeriesEntityByUuid(relation.seriesUuid)?.let { series ->
                SeriesWithPositionEntityImpl(
                    uuid = series.uuid,
                    name = series.name,
                    featured = series.featured,
                    position = relation.position,
                    createdAt = series.createdAt,
                    updatedAt = series.updatedAt,
                )
            }
        }

        return BookEntityImpl(
            uuid = book.uuid,
            serverId = book.serverId,
            serverType = book.serverType,
            id = book.id,
            title = book.title,
            subtitle = book.subtitle,
            language = book.language,
            publicationDate = book.publicationDate,
            description = book.description,
            rating = book.rating,
            suffix = book.suffix,
            createdAt = book.createdAt,
            updatedAt = book.updatedAt,
            authors = authors,
            narrators = narrators,
            creators = creators,
            series = seriesList,
            tags = tags,
            collections = collections,
            status = status,
            coverUrl = book.coverUrl,
            ebook = mediaFiles.find { it.type == "ebook" },
            audiobook = mediaFiles.find { it.type == "audiobook" },
            readaloud = readaloud,
            isbn = book.isbn,
            asin = book.asin,
        )
    }

    // ==================== RELATION SAVING ====================

    private suspend fun saveBookWithRelations(book: BookEntity) {
        // Save status first if present
        book.status?.let { status ->
            sqlDelightDao.upsertStatus(status.toSqlDelightEntity())
        }

        // Save the book
        sqlDelightDao.upsertBook(book.toSqlDelightEntity())

        // Clear existing relations
        sqlDelightDao.deleteBookAuthors(book.uuid)
        sqlDelightDao.deleteBookNarrators(book.uuid)
        sqlDelightDao.deleteBookCreators(book.uuid)
        sqlDelightDao.deleteBookSeries(book.uuid)
        sqlDelightDao.deleteBookTags(book.uuid)
        sqlDelightDao.deleteBookCollections(book.uuid)
        sqlDelightDao.deleteMediaFilesByBookUuid(book.uuid)
        sqlDelightDao.deleteReadaloudByBookUuid(book.uuid)

        // Save authors and relations
        book.authors.forEach { author ->
            sqlDelightDao.upsertPerson(author.toSqlDelightEntity())
            sqlDelightDao.insertBookAuthor(book.uuid, author.uuid)
        }

        // Save narrators and relations
        book.narrators.forEach { narrator ->
            sqlDelightDao.upsertPerson(narrator.toSqlDelightEntity())
            sqlDelightDao.insertBookNarrator(book.uuid, narrator.uuid)
        }

        // Save creators and relations
        book.creators.forEach { creator ->
            sqlDelightDao.upsertPerson(creator.toSqlDelightEntity())
            sqlDelightDao.insertBookCreator(book.uuid, creator.uuid)
        }

        // Save series and relations
        book.series.forEach { series ->
            sqlDelightDao.upsertSeries(series.toSqlDelightEntity())
            sqlDelightDao.insertBookSeries(book.uuid, series.uuid, series.position)
        }

        // Save tags and relations
        book.tags.forEach { tag ->
            sqlDelightDao.upsertTag(tag.toSqlDelightEntity())
            sqlDelightDao.insertBookTag(book.uuid, tag.uuid)
        }

        // Save collections and relations
        book.collections.forEach { collection ->
            sqlDelightDao.upsertCollection(collection.toSqlDelightEntity())
            sqlDelightDao.insertBookCollection(book.uuid, collection.uuid)
        }

        // Save media files
        book.ebook?.let { ebook ->
            sqlDelightDao.upsertMediaFile(ebook.toSqlDelightEntity())
        }
        book.audiobook?.let { audiobook ->
            sqlDelightDao.upsertMediaFile(audiobook.toSqlDelightEntity())
        }

        // Save readaloud
        book.readaloud?.let { readaloud ->
            sqlDelightDao.upsertReadaloud(readaloud.toSqlDelightEntity())
        }
    }

    private fun BookEntity.toSqlDelightEntity(): BookSqlDelightEntity {
        return BookSqlDelightEntity(
            uuid = uuid,
            serverId = serverId,
            serverType = serverType,
            id = id,
            title = title,
            subtitle = subtitle,
            language = language,
            publicationDate = publicationDate,
            description = description,
            rating = rating,
            suffix = suffix,
            statusUuid = status?.uuid,
            coverUrl = coverUrl,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isbn = isbn,
            asin = asin,
        )
    }

    private fun SeriesWithPositionEntity.toSqlDelightEntity(): SeriesSqlDelightEntity {
        return SeriesSqlDelightEntity(
            uuid = uuid,
            name = name,
            featured = featured,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

// ==================== ENTITY IMPLEMENTATIONS ====================

private data class BookEntityImpl(
    override val uuid: String,
    override val serverId: String,
    override val serverType: String?,
    override val id: Long,
    override val title: String,
    override val subtitle: String?,
    override val language: String?,
    override val publicationDate: String?,
    override val description: String?,
    override val rating: Float?,
    override val suffix: String?,
    override val createdAt: String?,
    override val updatedAt: String?,
    override val authors: List<PersonEntity>,
    override val narrators: List<PersonEntity>,
    override val creators: List<PersonEntity>,
    override val series: List<SeriesWithPositionEntity>,
    override val tags: List<TagEntity>,
    override val collections: List<CollectionEntity>,
    override val status: StatusEntity?,
    override val coverUrl: String?,
    override val ebook: MediaFileEntity?,
    override val audiobook: MediaFileEntity?,
    override val readaloud: ReadaloudEntity?,
    override val isbn: String? = null,
    override val asin: String? = null,
) : BookEntity

private data class SeriesWithPositionEntityImpl(
    override val uuid: String,
    override val name: String,
    override val featured: Int?,
    override val position: Double?,
    override val createdAt: String?,
    override val updatedAt: String?,
) : SeriesWithPositionEntity
