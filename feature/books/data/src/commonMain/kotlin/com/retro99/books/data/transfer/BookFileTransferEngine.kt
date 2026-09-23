package com.retro99.books.data.transfer

import com.retro99.base.AppInitializer
import com.retro99.books.data.CONTENT_HASH_ALGORITHM
import com.retro99.books.data.Sha256Digest
import com.retro99.books.data.calculateFileContentHash
import com.retro99.books.data.toHexString
import com.retro99.books.domain.BookFileTransfer
import com.retro99.books.domain.BookFileTransferManager
import com.retro99.books.domain.BookFileTransferTransport
import com.retro99.books.domain.BookFileUploadRequest
import com.retro99.books.domain.BookFileTransferRejectedException
import com.retro99.books.domain.BookFileTransferSessionExpiredException
import com.retro99.books.domain.CloudBookFileRecord
import com.retro99.books.domain.UploadReservation
import com.retro99.books.domain.UploadReservationResult
import com.retro99.books.domain.UploadRightsAttestation
import com.retro99.database.api.cloudfiles.CloudBookFileEntity
import com.retro99.database.api.cloudfiles.CloudFileTransferEntity
import com.retro99.database.api.cloudfiles.CloudFilesDatabase
import com.retro99.database.api.importedbooks.ImportedBooksDatabase
import com.retro99.database.api.library.LibraryBooksDatabase
import com.retro99.sync.domain.FileTransferStatus
import com.retro99.sync.domain.FileTransferStatusSource
import com.retro99.sync.domain.SyncPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
@Single(binds = [BookFileTransferManager::class, FileTransferStatusSource::class])
class BookFileTransferEngine(
    @Provided private val cloudFilesDatabase: CloudFilesDatabase,
    @Provided private val importedBooksDatabase: ImportedBooksDatabase,
    @Provided private val libraryBooksDatabase: LibraryBooksDatabase,
    @Provided private val transports: List<BookFileTransferTransport>,
) : BookFileTransferManager, FileTransferStatusSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobsMutex = Mutex()
    private val jobs = mutableMapOf<String, Job>()
    private val transportByServer = transports.associateBy(BookFileTransferTransport::serverId)
    private val json = Json { ignoreUnknownKeys = true }

    override fun supportsUpload(serverId: String): Boolean =
        transportByServer[serverId]?.capabilities?.supportsUpload == true

    override suspend fun enqueueUpload(
        serverId: String,
        localBookUuid: String,
        rightsAttestation: UploadRightsAttestation,
    ): String {
        val transport = transport(serverId)
        check(transport.capabilities.supportsUpload) { "Book backup is not enabled for this server" }

        val importedBook = importedBooksDatabase.getImportedBookByUuid(localBookUuid)
            ?: error("Imported book was not found")
        require(importedBook.fileSize > 0) { "Cannot back up an empty file" }
        val actualHash = withContext(Dispatchers.Default) {
            calculateFileContentHash(importedBook.filePath)
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
        priorTransfers.firstOrNull { transfer ->
            transfer.mediaType == importedBook.bookType &&
                transfer.contentHash == contentHash &&
                transfer.contentHashAlgorithm == algorithm &&
                transfer.state in (ACTIVE_STATES + STATE_COMPLETED)
        }?.let { return it.transferId }

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
    ): Int {
        if (!supportsUpload(serverId)) return 0
        val books = importedBooksDatabase.getAllImportedBooks().first()
        val knownTransferIds = cloudFilesDatabase.observeAllTransfers()
            .first()
            .mapTo(mutableSetOf(), CloudFileTransferEntity::transferId)
        var queued = 0
        for (book in books) {
            try {
                val transferId = enqueueUpload(serverId, book.uuid, rightsAttestation)
                val isNewTransfer = knownTransferIds.add(transferId)
                val transferState = cloudFilesDatabase.getTransfer(transferId)?.state
                if (isNewTransfer && transferState != STATE_COMPLETED) queued++
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Books without a synced cloud identity remain local and can be queued later.
            }
        }
        return queued
    }

    override suspend fun cancel(serverId: String, libraryBookId: String) {
        val transfer = cloudFilesDatabase.observeTransfers(serverId, libraryBookId)
            .first()
            .firstOrNull { candidate -> candidate.state in NON_TERMINAL_STATES }
            ?: return
        val transport = transport(serverId)
        val job = jobsMutex.withLock { jobs[transfer.transferId] }
        job?.cancelAndJoin()
        val latestTransfer = cloudFilesDatabase.getTransfer(transfer.transferId) ?: return
        if (latestTransfer.state !in NON_TERMINAL_STATES) return
        val cancelled = latestTransfer.copy(
            state = STATE_CANCELLED,
            bytesTransferred = 0,
            nextAttemptAt = null,
            updatedAt = now(),
        )
        cloudFilesDatabase.updateTransfer(cancelled)
        cloudFilesDatabase.deleteFileState(
            libraryBookId = latestTransfer.libraryBookId,
            mediaType = latestTransfer.mediaType,
            relativePath = "",
        )
        withContext(kotlinx.coroutines.NonCancellable) {
            runCatching {
                transport.cancel(latestTransfer.toReservationOrNull(), latestTransfer.tusUploadUrl)
            }
        }
    }

    override suspend fun retry(transferId: String) {
        val transfer = cloudFilesDatabase.getTransfer(transferId) ?: return
        check(transport(transfer.serverId).capabilities.supportsUpload) {
            "Book backup is not enabled for this server"
        }
        if (transfer.state != STATE_FAILED) return
        cloudFilesDatabase.updateTransfer(
            transfer.copy(
                state = STATE_PENDING,
                cloudBookFileId = null,
                uploadId = null,
                storagePath = null,
                tusUploadUrl = null,
                tusExpiresAt = null,
                bytesTransferred = 0,
                attemptCount = 0,
                nextAttemptAt = null,
                lastError = null,
                updatedAt = now(),
            ),
        )
        schedule(transferId)
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
        transportByServer.values
            .filter { transport -> transport.capabilities.supportsUpload }
            .forEach { transport ->
                val pending = cloudFilesDatabase.getTransfers(
                    serverId = transport.serverId,
                    states = RECOVERABLE_STATES,
                )
                pending.forEach { transfer ->
                    val delayMillis = transfer.nextAttemptAt
                        ?.let(::remainingDelayMillis)
                        ?: 0L
                    schedule(transfer.transferId, delayMillis)
                }
            }
    }

    private suspend fun schedule(transferId: String, delayMillis: Long = 0L) {
        jobsMutex.withLock {
            if (jobs[transferId]?.isActive == true) return
            jobs[transferId] = scope.launch {
                val thisJob = currentCoroutineContext()[Job]
                try {
                    if (delayMillis > 0) delay(delayMillis)
                    processTransfer(transferId)
                } finally {
                    jobsMutex.withLock {
                        if (jobs[transferId] === thisJob) jobs.remove(transferId)
                    }
                }
            }
        }
    }

    private suspend fun processTransfer(transferId: String) {
        var transfer = cloudFilesDatabase.getTransfer(transferId) ?: return
        if (transfer.state !in RECOVERABLE_STATES) return
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
                    failPermanently(transfer, reserveResult.reason)
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

                    val digest = Sha256Digest()
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
                    check(streamedHash == request.contentHash) { "Uploaded file hash changed" }
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
                        if (exception.reason in RECOVERABLE_FINALIZE_REJECTIONS) {
                            scheduleRetry(transfer, exception.reason)
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
                failPermanently(transfer, exception.reason)
            } else {
                scheduleRetry(transfer, exception.message ?: "Transfer failed")
            }
        }
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

    private suspend fun scheduleRetry(transfer: CloudFileTransferEntity, error: String) {
        val attempt = transfer.attemptCount + 1
        val waitSeconds = 1L shl attempt.coerceAtMost(MAX_BACKOFF_EXPONENT)
        val nextAttempt = Clock.System.now() + waitSeconds.seconds
        val pending = transfer.copy(
            state = STATE_PENDING,
            attemptCount = attempt,
            nextAttemptAt = nextAttempt.toString(),
            lastError = error,
            updatedAt = now(),
        )
        cloudFilesDatabase.updateTransfer(pending)
        scope.launch {
            delay(waitSeconds * 1_000)
            schedule(transfer.transferId)
        }
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
        state = state,
        bytesTransferred = bytesTransferred,
        totalBytes = sizeBytes,
        attemptCount = attemptCount,
        lastError = lastError,
    )

    private fun List<CloudFileTransferEntity>.toAggregateStatus(): FileTransferStatus? {
        val relevant = filter { transfer -> transfer.serverId in transportByServer }
        val active = relevant.filter { transfer -> transfer.state in ACTIVE_STATES }
        val failed = relevant.filter { transfer -> transfer.state == STATE_FAILED }
        if (active.isEmpty() && failed.isEmpty()) return null
        return FileTransferStatus(
            phase = SyncPhase.UPLOADING_FILES,
            activeItems = active.size,
            totalItems = active.size + failed.size,
            bytesTransferred = active.sumOf(CloudFileTransferEntity::bytesTransferred),
            totalBytes = (active + failed).sumOf(CloudFileTransferEntity::sizeBytes),
            error = failed.firstOrNull()?.lastError,
            canRetry = failed.isNotEmpty(),
        )
    }

    private fun remainingDelayMillis(value: String): Long = runCatching {
        (kotlin.time.Instant.parse(value).toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds())
            .coerceAtLeast(0L)
    }.getOrDefault(0L)

    private fun now(): String = Clock.System.now().toString()

    private companion object {
        const val DIRECTION_UPLOAD = "upload"
        const val STATE_PENDING = "pending"
        const val STATE_TRANSFERRING = "transferring"
        const val STATE_FINALIZING = "finalizing"
        const val STATE_COMPLETED = "completed"
        const val STATE_FAILED = "failed"
        const val STATE_CANCELLED = "cancelled"
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
) : AppInitializer {
    override fun initialize() {
        scope.launch { transferEngine.recoverPendingTransfers() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
