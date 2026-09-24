package com.retro99.books.data.transfer

import com.retro99.base.AppInitializer
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.Sha256Digest
import com.retro99.books.data.calculateFileContentHash
import com.retro99.books.data.toHexString
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileDownloadRequest
import com.retro99.books.domain.BookFileDownloadTransport
import com.retro99.books.domain.BookFileDeletionTransport
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.BookFileTransferTransport
import com.retro99.books.domain.BookFileUploadRequest
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.BookFileTransferSessionExpiredException
import com.retro99.books.domain.BookFileTransferDownloadIncompleteException
import com.retro99.books.domain.BackupAllResult
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.UploadReservation
import com.retro99.books.domain.UploadReservationResult
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.books.domain.model.BookType
import com.retro99.sync.domain.FileTransferStatus
import com.retro99.sync.domain.FileTransferStatusSource
import com.retro99.user.api.UserRegistry
import com.retro99.sync.domain.SyncPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [BookFileTransferManager::class, FileTransferStatusSource::class])
class BookFileTransferEngine(
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val transports: List<BookFileTransferTransport>,
    @Provided private val downloadTransports: List<BookFileDownloadTransport> = emptyList(),
    @Provided private val deletionTransports: List<BookFileDeletionTransport> = emptyList(),
    @Provided private val downloadFinalizer: DownloadTransferFinalizer? = null,
    @Provided private val fileStore: BookFileTransferFileStore? = null,
) : BookFileTransferManager, FileTransferStatusSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val enqueueMutex = Mutex()
    private val jobsMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val retryTimers = mutableMapOf<String, Job>()
    private val transportByServer = transports.associateBy(BookFileTransferTransport::serverId)
    private val downloadTransportByServer = downloadTransports.associateBy(BookFileDownloadTransport::serverId)
    private val deletionTransportByServer = deletionTransports.associateBy(BookFileDeletionTransport::serverId)
    private val json = Json { ignoreUnknownKeys = true }

    override fun supportsUpload(serverId: String): Boolean =
        transportByServer[serverId]?.capabilities?.supportsUpload == true

    override fun supportsDownload(serverId: String): Boolean =
        downloadTransportByServer[serverId]?.supportsDownload == true

    override fun supportsDeletion(serverId: String): Boolean =
        deletionTransportByServer.containsKey(serverId)

    override suspend fun enqueueDownload(
        serverId: String,
        libraryBookId: String,
        mediaType: String,
    ): String = enqueueMutex.withLock {
        enqueueDownloadLocked(serverId, libraryBookId, mediaType)
    }

    private suspend fun enqueueDownloadLocked(
        serverId: String,
        libraryBookId: String,
        mediaType: String,
    ): String {
        check(supportsDownload(serverId)) { "Book restore is not enabled for this server" }
        require(mediaType.equals(BookType.EBOOK.value, ignoreCase = true) ||
            mediaType.equals(BookType.READALOUD.value, ignoreCase = true)
        ) { "Only EPUB cloud files can be restored" }
        val file = cloudFilesDatabase.getFileStates(libraryBookId).firstOrNull { candidate ->
            candidate.mediaType.equals(mediaType, ignoreCase = true) &&
                candidate.relativePath.isEmpty() && candidate.status == FILE_STATUS_AVAILABLE
        } ?: error("No available cloud file was found for this book")
        require(file.sizeBytes > 0L) { "Cannot restore an empty cloud file" }
        val prior = cloudFilesDatabase.observeTransfers(serverId, libraryBookId)
            .first()
            .firstOrNull { candidate ->
                candidate.direction == DIRECTION_DOWNLOAD &&
                    candidate.mediaType.equals(mediaType, ignoreCase = true) &&
                    candidate.cloudBookFileId == file.cloudBookFileId
            }
        prior?.let { transfer ->
            if (transfer.state in ACTIVE_STATES) {
                scheduleExistingTransfer(transfer)
                return transfer.transferId
            }
            if (transfer.state == STATE_COMPLETED) {
                val localBook = transfer.localSourceUuid?.let { localUuid ->
                    importedBooksDatabase.getImportedBookByUuid(localUuid)
                }
                if (localBook != null &&
                    localBook.bookType.equals(file.mediaType, ignoreCase = true) &&
                    localBook.contentHash == file.contentHash &&
                    localBook.contentHashAlgorithm == file.contentHashAlgorithm &&
                    fileStore?.exists(localBook.filePath) == true &&
                    fileStore.size(localBook.filePath) == file.sizeBytes &&
                    withContext(Dispatchers.Default) {
                        calculateFileContentHash(localBook.filePath) == file.contentHash
                    }
                ) return transfer.transferId
            }
        }

        val fileStore = requireNotNull(fileStore) { "Cloud download storage is unavailable" }
        val now = now()
        val transferId = prior?.transferId ?: Uuid.random().toString()
        val localUuid = prior?.localSourceUuid ?: Uuid.random().toString()
        val stagingPath = prior?.stagingPath ?: fileStore.stagingPath(transferId)
        val stagedBytes = if (fileStore.exists(stagingPath)) fileStore.size(stagingPath) else 0L
        val existingBytes = if (stagedBytes > file.sizeBytes) 0L else stagedBytes
        if (stagedBytes > file.sizeBytes) {
            fileStore.truncate(stagingPath)
        }
        val transfer = CloudFileTransferEntity(
            transferId = transferId,
            serverId = serverId,
            direction = DIRECTION_DOWNLOAD,
            libraryBookId = libraryBookId,
            cloudBookId = file.cloudBookId,
            cloudBookFileId = file.cloudBookFileId,
            mediaType = file.mediaType,
            localSourceUuid = localUuid,
            stagingPath = stagingPath,
            sizeBytes = file.sizeBytes,
            bytesTransferred = existingBytes,
            contentHash = file.contentHash,
            contentHashAlgorithm = file.contentHashAlgorithm,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            tusExpiresAt = null,
            rightsAttestation = null,
            state = STATE_PENDING,
            attemptCount = 0,
            nextAttemptAt = null,
            lastError = null,
            createdAt = prior?.createdAt ?: now,
            updatedAt = now,
        )
        cloudFilesDatabase.insertTransfer(transfer)
        schedule(transferId)
        return transferId
    }

    override suspend fun removeDownload(serverId: String, libraryBookId: String, mediaType: String) {
        val transfer = cloudFilesDatabase.observeTransfers(serverId, libraryBookId)
            .first()
            .firstOrNull { candidate ->
                candidate.direction == DIRECTION_DOWNLOAD &&
                    candidate.mediaType.equals(mediaType, ignoreCase = true)
            } ?: return
        if (transfer.state in NON_TERMINAL_STATES) cancelTransfer(transfer.transferId)
        val latest = cloudFilesDatabase.getTransfer(transfer.transferId) ?: transfer
        val fileStore = requireNotNull(fileStore) { "Cloud download storage is unavailable" }
        latest.localSourceUuid?.let { localUuid ->
            val localBook = importedBooksDatabase.getImportedBookByUuid(localUuid)
            if (localBook != null) {
                if (localBook.origin == ORIGIN_CLOUD_DOWNLOAD) {
                    fileStore.delete(localBook.filePath)
                    localBook.coverPath?.let { coverPath -> fileStore.delete(coverPath) }
                    importedBooksDatabase.deleteImportedBook(localUuid)
                }
            } else {
                fileStore.delete(fileStore.importedFilePath(localUuid, latest.mediaType))
            }
        }
        latest.stagingPath?.let { path -> fileStore.delete(path) }
        cloudFilesDatabase.deleteTransfer(transfer.transferId)
    }

    override suspend fun deleteRemoteBackup(serverId: String, libraryBookId: String, mediaType: String) {
        check(supportsDeletion(serverId)) { "Cloud backup deletion is not enabled for this server" }
        val cloudFile = cloudFilesDatabase.getFileStates(libraryBookId).firstOrNull { candidate ->
            candidate.mediaType.equals(mediaType, ignoreCase = true) &&
                candidate.relativePath.isEmpty()
        } ?: return
        deletionTransportByServer.getValue(serverId).delete(cloudFile.cloudBookFileId)
        invalidateCloudFile(cloudFile.cloudBookFileId)
        cloudFilesDatabase.deleteFileState(libraryBookId, cloudFile.mediaType, cloudFile.relativePath)
    }

    override suspend fun invalidateCloudFile(cloudBookFileId: String) {
        val store = requireNotNull(fileStore) { "Cloud download storage is unavailable" }
        val transfers = cloudFilesDatabase.getTransfersForCloudFile(cloudBookFileId).filter { transfer ->
            transfer.direction == DIRECTION_DOWNLOAD
        }
        transfers.forEach { transfer ->
            if (transfer.state in NON_TERMINAL_STATES) cancelTransfer(transfer.transferId)
            val latest = cloudFilesDatabase.getTransfer(transfer.transferId)
                ?: transfer.copy(state = STATE_CANCELLED)
            val localBook = latest.localSourceUuid?.let { localUuid ->
                importedBooksDatabase.getImportedBookByUuid(localUuid)
            }
            if (localBook?.origin == ORIGIN_CLOUD_DOWNLOAD && localBook.cloudBookFileId == cloudBookFileId) {
                store.delete(localBook.filePath)
                localBook.coverPath?.let { coverPath -> store.delete(coverPath) }
                importedBooksDatabase.deleteImportedBook(localBook.uuid)
            }
            latest.stagingPath?.let { path -> store.delete(path) }
            if (localBook == null) {
                latest.localSourceUuid?.let { localUuid ->
                    store.delete(store.importedFilePath(localUuid, latest.mediaType))
                }
            }
            cloudFilesDatabase.deleteTransfer(latest.transferId)
        }
    }

    override suspend fun enqueueUpload(
        serverId: String,
        localBookUuid: String,
        rightsAttestation: UploadRightsAttestation,
    ): String = enqueueMutex.withLock {
        enqueueUploadLocked(serverId, localBookUuid, rightsAttestation)
    }

    private suspend fun enqueueUploadLocked(
        serverId: String,
        localBookUuid: String,
        rightsAttestation: UploadRightsAttestation,
    ): String {
        val transport = transport(serverId)
        check(transport.capabilities.supportsUpload) { "Book backup is not enabled for this server" }

        val importedBook = importedBooksDatabase.getImportedBookByUuid(localBookUuid)
            ?: error("Imported book was not found")
        require(importedBook.fileSize > 0) { "Cannot back up an empty file" }
        val fileStore = requireNotNull(fileStore) { "Cloud backup storage is unavailable" }
        val actualHash = withContext(Dispatchers.Default) {
            fileStore.contentHash(importedBook.filePath)
        }
        val contentHash = importedBook.contentHash ?: actualHash
        require(contentHash == actualHash) { "Imported file content no longer matches its saved hash" }
        val algorithm = importedBook.contentHashAlgorithm ?: CONTENT_HASH_ALGORITHM
        val libraryBookId = "$algorithm:$contentHash"
        val libraryBook = libraryBooksDatabase.getLibraryBookById(libraryBookId)
            ?: error("Book metadata must sync before its file can be backed up")
        val cloudBookId = libraryBook.cloudBookId
            ?: error("Book metadata must sync before its file can be backed up")

        val priorTransfers = cloudFilesDatabase
            .observeTransfers(serverId, libraryBookId)
            .first()
        val prior = priorTransfers.firstOrNull { transfer ->
            transfer.mediaType == importedBook.bookType &&
                transfer.contentHash == contentHash &&
                transfer.contentHashAlgorithm == algorithm &&
                transfer.state in (ACTIVE_STATES + STATE_COMPLETED)
        }
        prior?.let { transfer ->
            if (transfer.state in ACTIVE_STATES) scheduleExistingTransfer(transfer)
            return transfer.transferId
        }

        val now = Clock.System.now().toString()
        val transferId = Uuid.random().toString()
        val alreadyAvailable = cloudFilesDatabase.getFileStates(libraryBookId)
            .firstOrNull { file ->
                file.mediaType == importedBook.bookType &&
                    file.relativePath.isEmpty() &&
                    file.status == "available" &&
                    file.contentHash == contentHash &&
                    file.contentHashAlgorithm == algorithm
            }
        val transfer = CloudFileTransferEntity(
            transferId = transferId,
            serverId = serverId,
            direction = DIRECTION_UPLOAD,
            libraryBookId = libraryBookId,
            cloudBookId = cloudBookId,
            cloudBookFileId = alreadyAvailable?.cloudBookFileId,
            mediaType = importedBook.bookType,
            localSourceUuid = localBookUuid,
            stagingPath = null,
            sizeBytes = importedBook.fileSize,
            bytesTransferred = if (alreadyAvailable != null) importedBook.fileSize else 0,
            contentHash = contentHash,
            contentHashAlgorithm = algorithm,
            uploadId = null,
            storagePath = null,
            tusUploadUrl = null,
            tusExpiresAt = null,
            rightsAttestation = json.encodeToString(rightsAttestation),
            state = if (alreadyAvailable != null) STATE_COMPLETED else STATE_PENDING,
            attemptCount = 0,
            nextAttemptAt = null,
            lastError = null,
            createdAt = now,
            updatedAt = now,
        )
        cloudFilesDatabase.insertTransfer(transfer)
        if (alreadyAvailable == null) schedule(transfer.transferId)
        return transferId
    }

    override suspend fun backupAll(
        serverId: String,
        rightsAttestation: UploadRightsAttestation,
    ): BackupAllResult {
        if (!supportsUpload(serverId)) return BackupAllResult(queuedCount = 0, failedCount = 0)
        val books = importedBooksDatabase.getAllImportedBooks().first()
        val knownTransferIds = cloudFilesDatabase.observeAllTransfers()
            .first()
            .mapTo(mutableSetOf(), CloudFileTransferEntity::transferId)
        var queued = 0
        var failed = 0
        for (book in books) {
            if (book.origin != ORIGIN_IMPORTED) continue
            try {
                val transferId = enqueueUpload(serverId, book.uuid, rightsAttestation)
                val isNewTransfer = knownTransferIds.add(transferId)
                val transferState = cloudFilesDatabase.getTransfer(transferId)?.state
                if (isNewTransfer && transferState != STATE_COMPLETED) queued++
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                failed++
            }
        }
        return BackupAllResult(queuedCount = queued, failedCount = failed)
    }

    override suspend fun cancel(serverId: String, libraryBookId: String) {
        val transfer = cloudFilesDatabase.observeTransfers(serverId, libraryBookId)
            .first()
            .firstOrNull { candidate ->
                candidate.direction == DIRECTION_UPLOAD && candidate.state in NON_TERMINAL_STATES
            }
            ?: return
        cancelTransfer(transfer.transferId)
    }

    override suspend fun cancelTransfer(transferId: String) {
        val transfer = cloudFilesDatabase.getTransfer(transferId) ?: return
        if (transfer.state !in NON_TERMINAL_STATES) return
        val job = jobsMutex.withLock {
            retryTimers.remove(transfer.transferId)?.cancel()
            jobs[transfer.transferId]
        }
        job?.cancelAndJoin()
        val latestTransfer = cloudFilesDatabase.getTransfer(transfer.transferId) ?: return
        if (latestTransfer.state !in NON_TERMINAL_STATES) return
        val cancelled = latestTransfer.copy(
            state = STATE_CANCELLED,
            bytesTransferred = 0L,
            nextAttemptAt = null,
            updatedAt = now(),
        )
        cloudFilesDatabase.updateTransfer(cancelled)
        if (latestTransfer.direction == DIRECTION_DOWNLOAD) {
            latestTransfer.stagingPath?.let { path -> fileStore?.delete(path) }
        } else {
            cloudFilesDatabase.deleteFileState(
                libraryBookId = latestTransfer.libraryBookId,
                mediaType = latestTransfer.mediaType,
                relativePath = "",
            )
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    transport(latestTransfer.serverId).cancel(
                        latestTransfer.toReservationOrNull(),
                        latestTransfer.tusUploadUrl,
                    )
                }
            }
        }
        cloudFilesDatabase.deleteTransfer(latestTransfer.transferId)
    }

    override suspend fun retry(transferId: String) {
        val transfer = cloudFilesDatabase.getTransfer(transferId) ?: return
        if (transfer.direction == DIRECTION_UPLOAD) {
            check(transport(transfer.serverId).capabilities.supportsUpload) {
                "Book backup is not enabled for this server"
            }
        } else {
            check(supportsDownload(transfer.serverId)) { "Book restore is not enabled for this server" }
        }
        jobsMutex.withLock {
            val latest = cloudFilesDatabase.getTransfer(transferId) ?: return@withLock
            val wasFailed = latest.state == STATE_FAILED
            val isBackoffPending = latest.state == STATE_PENDING && latest.nextAttemptAt != null
            if (!wasFailed && !isBackoffPending) return@withLock
            // If the scheduled attempt has already started, let it finish. The
            // retry action remains available only while the row is in backoff.
            if (jobs[transferId]?.isActive == true) return@withLock

            retryTimers.remove(transferId)?.cancel()
            cloudFilesDatabase.updateTransfer(
                latest.copy(
                    state = STATE_PENDING,
                    cloudBookFileId = if (wasFailed && latest.direction == DIRECTION_UPLOAD) {
                        null
                    } else {
                        latest.cloudBookFileId
                    },
                    uploadId = if (wasFailed) null else latest.uploadId,
                    storagePath = if (wasFailed) null else latest.storagePath,
                    tusUploadUrl = if (wasFailed) null else latest.tusUploadUrl,
                    tusExpiresAt = if (wasFailed) null else latest.tusExpiresAt,
                    bytesTransferred = if (wasFailed && latest.direction == DIRECTION_UPLOAD) {
                        0L
                    } else {
                        latest.bytesTransferred
                    },
                    attemptCount = 0,
                    nextAttemptAt = null,
                    lastError = null,
                    updatedAt = now(),
                ),
            )
            scheduleLocked(transferId)
        }
    }

    override fun observeForBook(
        serverId: String,
        libraryBookId: String,
    ): Flow<List<BookFileTransfer>> = cloudFilesDatabase
        .observeTransfers(serverId, libraryBookId)
        .map { transfers -> transfers.map { transfer -> transfer.toDomain() } }

    override fun observe(): Flow<FileTransferStatus?> = cloudFilesDatabase
        .observeAllTransfers()
        .map { transfers -> transfers.toAggregateStatus() }

    suspend fun recoverPendingTransfers() {
        (transportByServer.values.filter { transport -> transport.capabilities.supportsUpload }
            .map(BookFileTransferTransport::serverId) +
            downloadTransportByServer.values.filter(BookFileDownloadTransport::supportsDownload)
                .map(BookFileDownloadTransport::serverId))
            .forEach { serverId ->
                val pending = cloudFilesDatabase.getTransfers(
                    serverId = serverId,
                    states = RECOVERABLE_STATES,
                )
                pending.forEach { transfer ->
                    val nextAttemptAt = transfer.nextAttemptAt
                    if (nextAttemptAt == null) {
                        schedule(transfer.transferId)
                    } else {
                        scheduleRetryTimer(
                            transferId = transfer.transferId,
                            nextAttemptAt = nextAttemptAt,
                            delayMillis = remainingDelayMillis(nextAttemptAt),
                        )
                    }
                }
            }
    }

    private suspend fun schedule(transferId: String) {
        jobsMutex.withLock {
            retryTimers.remove(transferId)?.cancel()
            scheduleLocked(transferId)
        }
    }

    private suspend fun scheduleExistingTransfer(transfer: CloudFileTransferEntity) {
        val nextAttemptAt = transfer.nextAttemptAt
        if (transfer.state == STATE_PENDING && nextAttemptAt != null) {
            scheduleRetryTimer(
                transferId = transfer.transferId,
                nextAttemptAt = nextAttemptAt,
                delayMillis = remainingDelayMillis(nextAttemptAt),
            )
        } else {
            schedule(transfer.transferId)
        }
    }

    private fun scheduleLocked(transferId: String) {
        if (jobs[transferId]?.isActive == true) return
        jobs[transferId] = scope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
                processTransfer(transferId)
            } finally {
                jobsMutex.withLock {
                    if (jobs[transferId] === thisJob) jobs.remove(transferId)
                }
            }
        }
    }

    private suspend fun scheduleRetryTimer(
        transferId: String,
        nextAttemptAt: String,
        delayMillis: Long,
    ) {
        jobsMutex.withLock {
            retryTimers.remove(transferId)?.cancel()
            val timerJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMillis.coerceAtLeast(0L))
                jobsMutex.withLock {
                    if (retryTimers[transferId] !== currentCoroutineContext()[Job]) return@withLock
                    retryTimers.remove(transferId)
                    val latest = cloudFilesDatabase.getTransfer(transferId) ?: return@withLock
                    if (latest.state == STATE_PENDING && latest.nextAttemptAt == nextAttemptAt) {
                        scheduleLocked(transferId)
                    }
                }
            }
            retryTimers[transferId] = timerJob
            timerJob.start()
        }
    }

    internal suspend fun processTransfer(transferId: String) {
        var transfer = cloudFilesDatabase.getTransfer(transferId) ?: return
        if (transfer.state !in RECOVERABLE_STATES) return
        if (transfer.nextAttemptAt != null || transfer.lastError != null) {
            transfer = transfer.copy(
                nextAttemptAt = null,
                lastError = null,
                updatedAt = now(),
            )
            cloudFilesDatabase.updateTransfer(transfer)
        }
        if (transfer.direction == DIRECTION_DOWNLOAD) {
            processDownloadTransfer(transfer)
            return
        }
        val transport = transport(transfer.serverId)
        if (!transport.capabilities.supportsUpload) return

        try {
            val request = createRequest(transfer)
            if (transfer.uploadId != null && transfer.tusUploadUrl != null) {
                val priorReservation = transfer.toReservationOrNull()
                if (priorReservation != null) {
                    val finalized = try {
                        transport.finalize(priorReservation, request)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: BookFileTransferRejectedException) {
                        if (exception.retryAfterMillis != null) {
                            scheduleRetry(transfer, exception.reason, exception.retryAfterMillis)
                            return
                        }
                        if (exception.reason in RECOVERABLE_FINALIZE_REJECTIONS) null else throw exception
                    }
                    finalized?.let { record ->
                        complete(transfer, record)
                        return
                    }
                }
            }

            when (val reserveResult = transport.reserve(request)) {
                is UploadReservationResult.AlreadyAvailable -> {
                    complete(transfer, reserveResult.file)
                    return
                }

                is UploadReservationResult.Rejected -> {
                    if (reserveResult.retryAfterMillis != null) {
                        scheduleRetry(transfer, reserveResult.reason, reserveResult.retryAfterMillis)
                    } else {
                        failPermanently(transfer, reserveResult.reason)
                    }
                    return
                }

                is UploadReservationResult.Reserved -> {
                    val reservation = reserveResult.reservation
                    transfer = transfer.copy(
                        cloudBookId = request.cloudBookId,
                        cloudBookFileId = reservation.cloudBookFileId,
                        uploadId = reservation.uploadId,
                        storagePath = reservation.storagePath,
                        state = STATE_TRANSFERRING,
                        lastError = null,
                        nextAttemptAt = null,
                        updatedAt = now(),
                    )
                    cloudFilesDatabase.updateTransfer(transfer)
                    updateFileState(transfer, request, "upload_pending", reservation.cloudBookFileId)

                    var digest = Sha256Digest()
                    val uploadResult = try {
                        transport.upload(
                            request = request,
                            reservation = reservation,
                            resumeUrl = transfer.tusUploadUrl,
                            resumeOffset = transfer.bytesTransferred,
                            onSession = { url, expiresAt ->
                                val sameSession = transfer.tusUploadUrl == url
                                transfer = transfer.copy(
                                    tusUploadUrl = url,
                                    tusExpiresAt = expiresAt ?: transfer.tusExpiresAt.takeIf { sameSession },
                                    bytesTransferred = if (sameSession) {
                                        transfer.bytesTransferred
                                    } else {
                                        0L
                                    },
                                    updatedAt = now(),
                                )
                                cloudFilesDatabase.updateTransfer(transfer)
                            },
                            onHashReset = { digest = Sha256Digest() },
                            onChunkHashed = { bytes -> digest.update(bytes, 0, bytes.size) },
                            onProgress = { bytesTransferred ->
                                transfer = transfer.copy(
                                    state = STATE_TRANSFERRING,
                                    bytesTransferred = bytesTransferred,
                                    updatedAt = now(),
                                )
                                cloudFilesDatabase.updateTransfer(transfer)
                            },
                        )
                    } catch (exception: BookFileTransferSessionExpiredException) {
                        transfer = transfer.copy(
                            tusUploadUrl = null,
                            tusExpiresAt = null,
                            bytesTransferred = 0,
                            state = STATE_PENDING,
                            updatedAt = now(),
                        )
                        cloudFilesDatabase.updateTransfer(transfer)
                        throw exception
                    }
                    val streamedHash = digest.digest().toHexString()
                    if (streamedHash != request.contentHash) {
                        throw BookFileTransferRejectedException(ERROR_VERIFY_FAILED)
                    }
                    transfer = transfer.copy(
                        state = STATE_FINALIZING,
                        bytesTransferred = uploadResult.bytesTransferred,
                        tusUploadUrl = uploadResult.uploadUrl,
                        tusExpiresAt = uploadResult.expiresAt ?: transfer.tusExpiresAt,
                        updatedAt = now(),
                    )
                    cloudFilesDatabase.updateTransfer(transfer)
                    val finalized = try {
                        transport.finalize(reservation, request)
                    } catch (exception: BookFileTransferRejectedException) {
                        if (exception.retryAfterMillis != null ||
                            exception.reason in RECOVERABLE_FINALIZE_REJECTIONS
                        ) {
                            scheduleRetry(transfer, exception.reason, exception.retryAfterMillis)
                            return
                        }
                        throw exception
                    }
                    complete(transfer, finalized)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception is BookFileTransferRejectedException) {
                if (exception.retryAfterMillis != null) {
                    scheduleRetry(transfer, exception.reason, exception.retryAfterMillis)
                } else {
                    failPermanently(transfer, exception.reason)
                }
            } else {
                scheduleRetry(transfer, exception.message ?: "Transfer failed")
            }
        }
    }

    private suspend fun processDownloadTransfer(initialTransfer: CloudFileTransferEntity) {
        var transfer = cloudFilesDatabase.getTransfer(initialTransfer.transferId) ?: return
        if (transfer.state !in RECOVERABLE_STATES) return
        val transport = downloadTransportByServer[transfer.serverId]
            ?.takeIf(BookFileDownloadTransport::supportsDownload) ?: return
        val fileStore = fileStore
        if (fileStore == null || downloadFinalizer == null) {
            failPermanently(transfer, ERROR_RESTORE_UNAVAILABLE)
            return
        }

        try {
            val request = createDownloadRequest(transfer)
            val stagingPath = requireNotNull(transfer.stagingPath) { "Download staging path was lost" }
            var offset = if (fileStore.exists(stagingPath)) fileStore.size(stagingPath) else 0L
            if (offset > request.sizeBytes) {
                fileStore.truncate(stagingPath)
                offset = 0L
            }
            transfer = transfer.copy(
                state = STATE_TRANSFERRING,
                bytesTransferred = offset,
                updatedAt = now(),
            )
            cloudFilesDatabase.updateTransfer(transfer)

            if (offset < request.sizeBytes) {
                transport.download(
                    request = request,
                    resumeOffset = offset,
                    onResponseOffset = { responseOffset ->
                        if (responseOffset != offset) fileStore.truncate(stagingPath)
                        offset = responseOffset
                        transfer = transfer.copy(
                            state = STATE_TRANSFERRING,
                            bytesTransferred = offset,
                            updatedAt = now(),
                        )
                        cloudFilesDatabase.updateTransfer(transfer)
                    },
                    onChunk = { bytes ->
                        fileStore.write(stagingPath, offset, bytes)
                        offset += bytes.size
                        transfer = transfer.copy(
                            state = STATE_TRANSFERRING,
                            bytesTransferred = offset,
                            updatedAt = now(),
                        )
                        cloudFilesDatabase.updateTransfer(transfer)
                    },
                )
            }
            if (!fileStore.exists(stagingPath) || fileStore.size(stagingPath) != request.sizeBytes) {
                throw BookFileTransferDownloadIncompleteException()
            }
            transfer = transfer.copy(
                state = STATE_VERIFYING,
                bytesTransferred = request.sizeBytes,
                updatedAt = now(),
            )
            cloudFilesDatabase.updateTransfer(transfer)
            transfer = downloadFinalizer.finalize(transfer, request)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: BookFileTransferRejectedException) {
            if (exception.retryAfterMillis != null) {
                scheduleRetry(transfer, exception.reason, exception.retryAfterMillis)
            } else {
                failPermanently(transfer, exception.reason)
            }
        } catch (_: DownloadHashMismatchException) {
            transfer.stagingPath?.let { path -> fileStore.delete(path) }
            failPermanently(transfer, ERROR_VERIFY_FAILED)
        } catch (exception: Exception) {
            scheduleRetry(transfer, exception.message ?: "Download failed")
        }
    }

    private suspend fun createDownloadRequest(transfer: CloudFileTransferEntity): BookFileDownloadRequest {
        val file = cloudFilesDatabase.getFileStates(transfer.libraryBookId).firstOrNull { candidate ->
            candidate.cloudBookFileId == transfer.cloudBookFileId &&
                candidate.mediaType.equals(transfer.mediaType, ignoreCase = true)
        } ?: throw BookFileTransferRejectedException("cloud_file_unavailable")
        if (file.status != FILE_STATUS_AVAILABLE) {
            throw BookFileTransferRejectedException("cloud_file_unavailable")
        }
        if (file.libraryBookId != transfer.libraryBookId ||
            file.cloudBookId != transfer.cloudBookId ||
            file.sizeBytes != transfer.sizeBytes ||
            file.contentHash != transfer.contentHash ||
            file.contentHashAlgorithm != transfer.contentHashAlgorithm
        ) {
            throw BookFileTransferRejectedException("cloud_file_changed")
        }
        if (file.contentHashAlgorithm != CONTENT_HASH_ALGORITHM) {
            throw BookFileTransferRejectedException("unsupported_hash_algorithm")
        }
        if (file.libraryBookId != "${file.contentHashAlgorithm}:${file.contentHash}") {
            throw BookFileTransferRejectedException("cloud_file_identity_mismatch")
        }
        return BookFileDownloadRequest(
            transferId = transfer.transferId,
            serverId = transfer.serverId,
            libraryBookId = transfer.libraryBookId,
            cloudBookId = transfer.cloudBookId ?: file.cloudBookId,
            cloudBookFileId = file.cloudBookFileId,
            mediaType = file.mediaType,
            fileName = file.fileName,
            sizeBytes = file.sizeBytes,
            contentHash = file.contentHash,
            contentHashAlgorithm = file.contentHashAlgorithm,
        )
    }

    private suspend fun createRequest(transfer: CloudFileTransferEntity): BookFileUploadRequest {
        val bookUuid = requireNotNull(transfer.localSourceUuid) { "Upload source was lost" }
        val importedBook = importedBooksDatabase.getImportedBookByUuid(bookUuid)
            ?: error("Upload source was removed")
        val bookHash = requireNotNull(transfer.contentHash)
        val hashAlgorithm = requireNotNull(transfer.contentHashAlgorithm)
        val attestation = transfer.rightsAttestation
            ?.let { raw -> json.decodeFromString<UploadRightsAttestation>(raw) }
            ?: error("Upload rights attestation is missing")
        return BookFileUploadRequest(
            transferId = transfer.transferId,
            serverId = transfer.serverId,
            libraryBookId = transfer.libraryBookId,
            cloudBookId = requireNotNull(transfer.cloudBookId),
            localBookUuid = bookUuid,
            mediaType = transfer.mediaType,
            relativePath = "",
            fileName = importedBook.filePath.substringAfterLast('/').substringAfterLast('\\'),
            localPath = importedBook.filePath,
            sizeBytes = transfer.sizeBytes,
            contentHash = bookHash,
            contentHashAlgorithm = hashAlgorithm,
            rightsAttestation = attestation,
        )
    }

    private suspend fun complete(transfer: CloudFileTransferEntity, file: CloudBookFileRecord) {
        val updated = transfer.copy(
            cloudBookFileId = file.cloudBookFileId,
            state = STATE_COMPLETED,
            bytesTransferred = transfer.sizeBytes,
            nextAttemptAt = null,
            lastError = null,
            updatedAt = now(),
        )
        cloudFilesDatabase.updateTransfer(updated)
        cloudFilesDatabase.upsertFileState(
            CloudBookFileEntity(
                libraryBookId = transfer.libraryBookId,
                cloudBookId = file.cloudBookId,
                cloudBookFileId = file.cloudBookFileId,
                mediaType = file.mediaType,
                relativePath = file.relativePath,
                fileName = file.fileName,
                status = file.status,
                sizeBytes = file.sizeBytes,
                contentHash = file.contentHash,
                contentHashAlgorithm = file.contentHashAlgorithm,
                remoteRevision = file.remoteRevision,
                updatedAt = now(),
            ),
        )
    }

    private suspend fun updateFileState(
        transfer: CloudFileTransferEntity,
        request: BookFileUploadRequest,
        status: String,
        fileId: String,
    ) {
        cloudFilesDatabase.upsertFileState(
            CloudBookFileEntity(
                libraryBookId = transfer.libraryBookId,
                cloudBookId = request.cloudBookId,
                cloudBookFileId = fileId,
                mediaType = request.mediaType,
                relativePath = request.relativePath,
                fileName = request.fileName,
                status = status,
                sizeBytes = request.sizeBytes,
                contentHash = request.contentHash,
                contentHashAlgorithm = request.contentHashAlgorithm,
                remoteRevision = 0,
                updatedAt = now(),
            ),
        )
    }

    private suspend fun failPermanently(transfer: CloudFileTransferEntity, reason: String) {
        val failed = transfer.copy(
            state = STATE_FAILED,
            lastError = reason,
            nextAttemptAt = null,
            updatedAt = now(),
        )
        cloudFilesDatabase.updateTransfer(failed)
        if (transfer.direction == DIRECTION_DOWNLOAD) return
        val transport = transportByServer[transfer.serverId]
        if (transport != null) {
            try {
                transport.cancel(transfer.toReservationOrNull(), transfer.tusUploadUrl)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // The final local failure remains visible; reservation expiry releases server quota.
            }
        }
        val cloudBookId = transfer.cloudBookId ?: return
        val fileId = transfer.cloudBookFileId ?: return
        val hash = transfer.contentHash ?: return
        val algorithm = transfer.contentHashAlgorithm ?: return
        val fileName = transfer.localSourceUuid
            ?.let { uuid -> importedBooksDatabase.getImportedBookByUuid(uuid)?.filePath }
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            .orEmpty()
        cloudFilesDatabase.upsertFileState(
            CloudBookFileEntity(
                libraryBookId = transfer.libraryBookId,
                cloudBookId = cloudBookId,
                cloudBookFileId = fileId,
                mediaType = transfer.mediaType,
                relativePath = "",
                fileName = fileName,
                status = "upload_failed",
                sizeBytes = transfer.sizeBytes,
                contentHash = hash,
                contentHashAlgorithm = algorithm,
                remoteRevision = 0,
                updatedAt = now(),
            ),
        )
    }

    private suspend fun scheduleRetry(
        transfer: CloudFileTransferEntity,
        error: String,
        retryAfterMillis: Long? = null,
    ) {
        val attempt = transfer.attemptCount + 1
        if (attempt >= MAX_TRANSFER_ATTEMPTS) {
            failPermanently(transfer.copy(attemptCount = attempt), error)
            return
        }
        val delayMillis = retryAfterMillis?.coerceAtLeast(0L)
            ?: ((1L shl transfer.attemptCount.coerceAtLeast(0).coerceAtMost(MAX_BACKOFF_EXPONENT)) * 1_000L)
        val nextAttempt = Clock.System.now() + delayMillis.milliseconds
        val pending = transfer.copy(
            state = STATE_PENDING,
            attemptCount = attempt,
            nextAttemptAt = nextAttempt.toString(),
            lastError = error,
            updatedAt = now(),
        )
        cloudFilesDatabase.updateTransfer(pending)
        scheduleRetryTimer(transfer.transferId, pending.nextAttemptAt!!, delayMillis)
    }

    private fun transport(serverId: String): BookFileTransferTransport =
        transportByServer[serverId]
            ?: error("No file-transfer transport is registered for server $serverId")

    private fun CloudFileTransferEntity.toReservationOrNull(): UploadReservation? {
        val id = uploadId ?: return null
        return UploadReservation(
            uploadId = id,
            cloudBookFileId = cloudBookFileId.orEmpty(),
            storagePath = storagePath.orEmpty(),
            uploadEndpoint = "",
        )
    }

    private fun CloudFileTransferEntity.toDomain() = BookFileTransfer(
        transferId = transferId,
        serverId = serverId,
        libraryBookId = libraryBookId,
        direction = direction,
        mediaType = mediaType,
        state = state,
        bytesTransferred = bytesTransferred,
        totalBytes = sizeBytes,
        attemptCount = attemptCount,
        lastError = lastError,
    )

    private fun List<CloudFileTransferEntity>.toAggregateStatus(): FileTransferStatus? {
        val supportedServerIds = transportByServer.keys + downloadTransportByServer.keys
        val relevant = filter { transfer -> transfer.serverId in supportedServerIds }
        val active = relevant.filter { transfer -> transfer.state in ACTIVE_STATES }
        if (active.isEmpty()) return null
        val directions = active.mapTo(mutableSetOf(), CloudFileTransferEntity::direction)
        val phase = when {
            directions.size > 1 -> SyncPhase.TRANSFERRING_FILES
            DIRECTION_DOWNLOAD in directions -> SyncPhase.DOWNLOADING_FILES
            else -> SyncPhase.UPLOADING_FILES
        }
        return FileTransferStatus(
            phase = phase,
            activeItems = active.size,
            totalItems = active.size,
            bytesTransferred = active.sumOf(CloudFileTransferEntity::bytesTransferred),
            totalBytes = active.sumOf(CloudFileTransferEntity::sizeBytes),
            canRetry = false,
        )
    }

    private fun remainingDelayMillis(value: String): Long = runCatching {
        (kotlin.time.Instant.parse(value).toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds())
            .coerceAtLeast(0L)
    }.getOrDefault(0L)

    private fun now(): String = Clock.System.now().toString()

    private companion object {
        const val DIRECTION_UPLOAD = "upload"
        const val DIRECTION_DOWNLOAD = "download"
        const val STATE_PENDING = "pending"
        const val STATE_TRANSFERRING = "transferring"
        const val STATE_FINALIZING = "finalizing"
        const val STATE_VERIFYING = "verifying"
        const val STATE_COMPLETED = "completed"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
        const val FILE_STATUS_AVAILABLE = "available"
        const val ORIGIN_CLOUD_DOWNLOAD = "cloud_download"
        const val ORIGIN_IMPORTED = "import"
        const val ERROR_VERIFY_FAILED = "verify_failed"
        const val ERROR_RESTORE_UNAVAILABLE = "restore_unavailable"
        const val MAX_TRANSFER_ATTEMPTS = 10
        val RECOVERABLE_FINALIZE_REJECTIONS = setOf("upload_incomplete", "upload_expired")
        const val MAX_BACKOFF_EXPONENT = 6
        val ACTIVE_STATES = setOf(STATE_PENDING, STATE_TRANSFERRING, "verifying", STATE_FINALIZING)
        val NON_TERMINAL_STATES = ACTIVE_STATES
        val RECOVERABLE_STATES = ACTIVE_STATES.toList()
    }
}

@Single(binds = [AppInitializer::class])
class BookFileTransferRecoveryInitializer(
    private val transferEngine: BookFileTransferEngine,
    @Provided private val userRegistry: UserRegistry,
) : AppInitializer {
    override fun initialize() {
        scope.launch {
            userRegistry.observeActiveProfile().filterNotNull().first()
            transferEngine.recoverPendingTransfers()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
