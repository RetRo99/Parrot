package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.epub.api.EpubFileCheck
import com.retro99.epub.api.EpubFileChecker
import com.retro99.epub.api.EpubFileProblem
import com.retro99.server.api.CatalogueAcquisitionLocator
import com.retro99.server.api.CatalogueDownloadFailure
import com.retro99.server.api.CatalogueDownloadOutcome
import com.retro99.server.api.CatalogueFileSink
import kotlinx.coroutines.CancellationException

internal sealed interface DownloadStep {
    data class Downloaded(val bytes: Long, val declaredLength: Long?) : DownloadStep
    data class Failed(val reason: AcquisitionFailureReason, val bytes: Long, val declaredLength: Long?) : DownloadStep
}

internal sealed interface CheckStep {
    data class Staged(val path: String, val contentHash: String, val sizeBytes: Long) : CheckStep
    data class Failed(val reason: AcquisitionFailureReason) : CheckStep
}

/**
 * The two slow steps of one download: bytes to a staging file, then checking that file.
 * It holds no lock and writes nothing to the database; the queue does that around it.
 */
internal class AcquisitionWorker(
    private val source: AcquisitionFileSource,
    private val files: CatalogueStagingFiles,
    private val checker: EpubFileChecker,
) {
    private class NotEnoughSpace : Exception()

    /** Always from zero: the file at [partPath] is emptied first. There is no range resume. */
    suspend fun download(
        profileId: String,
        sourceId: String,
        locator: CatalogueAcquisitionLocator,
        partPath: String,
        onProgress: suspend (bytes: Long, declaredLength: Long?) -> Unit,
    ): DownloadStep {
        var bytes = 0L
        var declared: Long? = null
        fun failed(reason: AcquisitionFailureReason) = DownloadStep.Failed(reason, bytes, declared)

        if (!hasRoomFor(MIN_FREE_BYTES)) return failed(AcquisitionFailureReason.Storage)
        val writer = try {
            files.openForWriting(partPath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(AcquisitionFailureReason.Storage)
        }
        var sinceSpaceCheck = 0L
        val sink = object : CatalogueFileSink {
            override suspend fun start(declaredLength: Long?) {
                declared = declaredLength
                if (declaredLength != null && !hasRoomFor(declaredLength + MIN_FREE_BYTES)) throw NotEnoughSpace()
                onProgress(0, declaredLength)
            }

            override suspend fun write(buffer: ByteArray, length: Int) {
                writer.write(buffer, length)
                bytes += length
                sinceSpaceCheck += length
                if (sinceSpaceCheck >= SPACE_CHECK_INTERVAL_BYTES) {
                    sinceSpaceCheck = 0
                    if (!hasRoomFor(MIN_FREE_BYTES)) throw NotEnoughSpace()
                }
                onProgress(bytes, declared)
            }
        }
        val outcome = try {
            source.download(profileId, sourceId, locator, sink)
        } finally {
            runCatching { writer.close() }
        }
        return when (outcome) {
            is CatalogueDownloadOutcome.Complete -> when {
                // A length that does not match what arrived is an incomplete file, whoever noticed.
                outcome.declaredLength != null && outcome.declaredLength != outcome.bytes ->
                    failed(AcquisitionFailureReason.Connection)
                outcome.bytes != bytes || files.size(partPath) != bytes -> failed(AcquisitionFailureReason.Storage)
                else -> DownloadStep.Downloaded(bytes, outcome.declaredLength)
            }
            is CatalogueDownloadOutcome.Failed -> failed(
                when (outcome.kind) {
                    CatalogueDownloadFailure.SignInNeeded -> AcquisitionFailureReason.SignIn
                    CatalogueDownloadFailure.Refused -> AcquisitionFailureReason.Refused
                    CatalogueDownloadFailure.TooLarge -> AcquisitionFailureReason.TooLarge
                    CatalogueDownloadFailure.Connection -> AcquisitionFailureReason.Connection
                },
            )
            // The disk is full, or the staging file could not be written.
            is CatalogueDownloadOutcome.SinkFailed -> failed(AcquisitionFailureReason.Storage)
        }
    }

    /**
     * Checks the downloaded file, hashes it and gives it its `.epub` staging name. The server's
     * content type and the file name play no part: only the bytes on disk are looked at.
     */
    suspend fun checkAndStage(partPath: String): CheckStep {
        when (val check = checker.check(partPath)) {
            EpubFileCheck.Valid -> Unit
            EpubFileCheck.Protected -> return CheckStep.Failed(AcquisitionFailureReason.Protected)
            is EpubFileCheck.NotAnEpub -> return CheckStep.Failed(
                // The file we just wrote cannot be opened: that is this device, not the book.
                if (check.problem == EpubFileProblem.Unreadable) AcquisitionFailureReason.Storage
                else AcquisitionFailureReason.Invalid,
            )
        }
        return try {
            val hash = files.sha256(partPath)
            val size = files.size(partPath)
            val stagedPath = CatalogueStagingFiles.stagedPathFor(partPath)
            if (!files.rename(partPath, stagedPath)) return CheckStep.Failed(AcquisitionFailureReason.Storage)
            CheckStep.Staged(stagedPath, hash, size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CheckStep.Failed(AcquisitionFailureReason.Storage)
        }
    }

    private suspend fun hasRoomFor(bytes: Long): Boolean {
        val free = files.freeSpaceBytes() ?: return true
        return free >= bytes
    }

    companion object {
        /** Space that must stay free besides the file itself. */
        const val MIN_FREE_BYTES: Long = 16L * 1024 * 1024

        /** Free space is looked at again each time this many bytes have been written. */
        const val SPACE_CHECK_INTERVAL_BYTES: Long = 4L * 1024 * 1024
    }
}
