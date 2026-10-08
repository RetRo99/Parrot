package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionEvent
import com.retro99.catalogue.domain.AcquisitionFailureReason
import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.AcquisitionStateMachine
import com.retro99.catalogue.domain.AcquisitionTransition
import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.domain.CatalogueAcquisitionLimits
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.catalogue.domain.CatalogueBookAdder
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.catalogue.domain.StagedCatalogueBook
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.server.api.CatalogueAcquisitionLocator
import com.retro99.server.api.CatalogueWorkController
import com.retro99.user.api.ProfileWorkRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The catalogue download queue.
 *
 * Rules it keeps:
 * - At most [CatalogueAcquisitionLimits.MAX_RUNNING] downloads run; the rest wait and start in
 *   queue order. The order is a stored column, so it survives a restart.
 * - Every change of a request goes through [AcquisitionStateMachine] and is written inside
 *   `ProfileDatabaseSession.withProfile` for the profile the request was made in. That lock is
 *   taken for the write only, never around a network call or a file check.
 * - A download belongs to the profile that asked for it. When that profile closes, its running
 *   downloads stop and are stored as interrupted; nothing is written for a profile that is not
 *   the open one.
 * - Nothing starts by itself. After a restart or a profile switch the queue waits for a user
 *   action ([request], [retry], [startAgain], [signedIn]) or [start].
 * - A checked file is handed to [adder]. Once the book is in the library the request is
 *   finished in a fixed order, each step safe to repeat: the library book id is stored, the
 *   provenance row is written, then the request becomes done and loses its listing address
 *   and staging path. A request found half-way through that after a restart is finished, not
 *   downloaded again.
 */
class CatalogueAcquisitionQueue internal constructor(
    private val session: ProfileDatabaseSession,
    private val database: CatalogueAcquisitionsDatabase,
    private val sources: CatalogueBookSourcesDatabase,
    private val activeProfileId: () -> String?,
    private val profileWork: ProfileWorkRegistry,
    private val worker: AcquisitionWorker,
    private val files: CatalogueStagingFiles,
    private val adder: CatalogueBookAdder,
    /** The registered address of a catalogue, or null when it is no longer registered. Must not suspend on a lock. */
    private val sourceAddress: (profileId: String, sourceId: String) -> String?,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val newRequestId: () -> String,
) : CatalogueAcquisitionManager, CatalogueWorkController {

    /** Work for a profile that is no longer the open one. It unwinds like a cancellation. */
    private class ProfileClosed : CancellationException("Profile is no longer open")

    private data class Progress(val bytes: Long, val declaredLength: Long?)

    private sealed interface Advance {
        data class Moved(val row: CatalogueAcquisitionEntity) : Advance
        data class Removed(val row: CatalogueAcquisitionEntity) : Advance
        data object Rejected : Advance
    }

    /** Guards everything below it. Held for bookkeeping and database writes only. */
    private val lock = Mutex()
    private var loadedProfileId: String? = null
    private var resumed = false
    private val jobs = mutableMapOf<String, Job>()

    private val snapshot = MutableStateFlow<List<CatalogueAcquisition>>(emptyList())
    private val progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    private val lastLiveProgressAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val lastStoredProgressAt = MutableStateFlow<Map<String, Long>>(emptyMap())

    override fun observeAcquisitions(): Flow<List<CatalogueAcquisition>> = flow {
        try {
            activeProfileId()?.let { profileId -> lock.withLock { ensureLoaded(profileId) } }
        } catch (_: ProfileClosed) {
            // No open profile to load; the list below stays empty until one is.
        }
        emitAll(
            combine(snapshot, progress) { acquisitions, live ->
                acquisitions.map { acquisition ->
                    val current = live[acquisition.requestId]
                    if (current == null || acquisition.state != AcquisitionState.Downloading) {
                        acquisition
                    } else {
                        acquisition.copy(
                            bytesSoFar = maxOf(acquisition.bytesSoFar, current.bytes),
                            expectedSizeBytes = current.declaredLength ?: acquisition.expectedSizeBytes,
                        )
                    }
                }
            },
        )
    }

    override suspend fun request(request: CatalogueAcquisitionRequest): CatalogueRequestOutcome {
        val profileId = requireProfile()
        val row = lock.withLock {
            ensureLoaded(profileId)
            val stored = inProfile(profileId) {
                // Already acquired, whichever file of it and whichever entry led to it: the
                // catalogue may have regenerated the file, and that must not add a second book.
                val acquired = sources.findInLibrary(
                    request.sourceId,
                    listOfNotNull(request.publicationKey, request.detailIdentity),
                ).firstOrNull()
                if (acquired != null) return@inProfile null to acquired.libraryBookId
                val row = database.findUnfinished(request.sourceId, request.publicationKey, request.representationKey)
                    ?: request.toWaitingEntity(newRequestId(), database.nextQueuePosition(), now())
                        .also { waiting -> database.insert(waiting) }
                row to null
            }
            val queued = stored.first ?: return CatalogueRequestOutcome.InLibrary(checkNotNull(stored.second))
            resumed = true
            refresh(profileId)
            queued
        }
        pump(profileId)
        return CatalogueRequestOutcome.Queued(
            snapshot.value.firstOrNull { it.requestId == row.requestId }
                ?: checkNotNull(row.toAcquisition()) { "Stored download has an unknown state" },
        )
    }

    override suspend fun cancel(requestId: String): Boolean = userEvent(requestId, AcquisitionEvent.Cancel)

    override suspend fun retry(requestId: String): Boolean = userEvent(requestId, AcquisitionEvent.Retry)

    override suspend fun dismiss(requestId: String): Boolean = userEvent(requestId, AcquisitionEvent.Dismiss)

    override suspend fun startAgain(requestId: String): Boolean = userEvent(requestId, AcquisitionEvent.StartAgain)

    override suspend fun signedIn(sourceId: String) {
        val profileId = requireProfile()
        lock.withLock {
            ensureLoaded(profileId)
            inProfile(profileId) {
                database.getBySource(sourceId)
                    .filter { row ->
                        val state = row.acquisitionState() ?: return@filter false
                        AcquisitionStateMachine.transition(state, AcquisitionEvent.SignedIn) is AcquisitionTransition.To
                    }
                    .forEach { row -> requeue(row) }
            }
            resumed = true
            refresh(profileId)
        }
        pump(profileId)
    }

    override suspend fun start() {
        val profileId = requireProfile()
        lock.withLock {
            ensureLoaded(profileId)
            resumed = true
        }
        pump(profileId)
    }

    override suspend fun restoreAfterRestart() {
        val profileId = activeProfileId() ?: return
        try {
            lock.withLock { ensureLoaded(profileId) }
        } catch (_: ProfileClosed) {
            // Closed again before it was loaded. It is loaded the next time it is opened.
        }
    }

    override suspend fun finishedInLast24Hours(): List<CatalogueAcquisition> {
        val profileId = requireProfile()
        return lock.withLock {
            ensureLoaded(profileId)
            inProfile(profileId) {
                database.getCompletedSince(now() - CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS)
            }.mapNotNull { row -> row.toAcquisition() }
        }
    }

    override suspend fun purgeFinished() {
        val profileId = requireProfile()
        lock.withLock {
            ensureLoaded(profileId)
            inProfile(profileId) { database.deleteCompleted() }
            refresh(profileId)
        }
    }

    override suspend fun purgeExpired() {
        val profileId = requireProfile()
        lock.withLock {
            ensureLoaded(profileId)
            inProfile(profileId) {
                database.deleteCompletedBefore(now() - CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS)
            }
            refresh(profileId)
        }
    }

    /**
     * The source was turned off or removed, or its account details changed. Its unfinished
     * requests are cancelled. A request that is waiting for sign-in is kept: saving account
     * details is exactly what it waits for.
     */
    override suspend fun cancel(profileId: String, sourceId: String) {
        // Another profile's database is closed; its rows are dealt with when it opens again.
        if (activeProfileId() != profileId) return
        val stopped = mutableListOf<Job>()
        val leftovers = mutableListOf<String>()
        try {
            lock.withLock {
                ensureLoaded(profileId)
                inProfile(profileId) {
                    database.getBySource(sourceId).forEach { row ->
                        val state = row.acquisitionState() ?: return@forEach
                        if (state == AcquisitionState.Failed(AcquisitionFailureReason.SignIn)) return@forEach
                        if (AcquisitionStateMachine.transition(state, AcquisitionEvent.Cancel) != AcquisitionTransition.Removed) {
                            return@forEach
                        }
                        database.delete(row.requestId)
                        forget(row.requestId)?.let(stopped::add)
                        row.stagingPath?.let(leftovers::add)
                    }
                }
                refresh(profileId)
            }
        } catch (_: ProfileClosed) {
            return
        }
        stopped.forEach { job -> job.cancelAndJoin() }
        leftovers.forEach { path -> deleteStaged(path) }
        pump(profileId)
    }

    private suspend fun userEvent(requestId: String, event: AcquisitionEvent): Boolean {
        val profileId = requireProfile()
        var stopped: Job? = null
        var leftover: String? = null
        val changed = lock.withLock {
            ensureLoaded(profileId)
            val outcome = inProfile(profileId) {
                val row = database.get(requestId) ?: return@inProfile Advance.Rejected
                val state = row.acquisitionState() ?: return@inProfile Advance.Rejected
                when (AcquisitionStateMachine.transition(state, event)) {
                    AcquisitionTransition.Rejected -> Advance.Rejected
                    AcquisitionTransition.Removed -> {
                        database.delete(requestId)
                        Advance.Removed(row)
                    }
                    // Retry, start again: back to waiting, behind everything already waiting.
                    is AcquisitionTransition.To -> Advance.Moved(requeue(row))
                }
            }
            when (outcome) {
                is Advance.Removed -> {
                    stopped = forget(requestId)
                    leftover = outcome.row.stagingPath
                }
                is Advance.Moved -> resumed = true
                Advance.Rejected -> Unit
            }
            refresh(profileId)
            outcome != Advance.Rejected
        }
        stopped?.cancelAndJoin()
        leftover?.let { path -> deleteStaged(path) }
        if (changed) pump(profileId)
        return changed
    }

    /** Must run inside [inProfile]. */
    private suspend fun requeue(row: CatalogueAcquisitionEntity): CatalogueAcquisitionEntity {
        val waiting = row.withState(AcquisitionState.Waiting, now()).copy(
            queuePosition = database.nextQueuePosition(),
            stagingPath = null,
            bytesSoFar = 0,
            localHash = null,
            neededBytes = null,
        )
        database.update(waiting)
        return waiting
    }

    /** Starts waiting requests while a slot is free. Does nothing until the queue is resumed. */
    private suspend fun pump(profileId: String) {
        try {
            lock.withLock {
                if (loadedProfileId != profileId || !resumed) return
                while (jobs.size < CatalogueAcquisitionLimits.MAX_RUNNING) {
                    val started = inProfile(profileId) {
                        val next = database.getByStates(listOf(AcquisitionState.Waiting.key))
                            .firstOrNull { row -> row.requestId !in jobs }
                            ?: return@inProfile null
                        val downloading = next.withState(AcquisitionState.Downloading, now()).copy(
                            stagingPath = files.newPartPath(profileId),
                            bytesSoFar = 0,
                            localHash = null,
                            attempts = next.attempts + 1,
                        )
                        database.update(downloading)
                        downloading
                    } ?: break
                    // Lazy, so the job is in the table before its first line runs.
                    val job = scope.launch(start = CoroutineStart.LAZY) { run(profileId, started) }
                    jobs[started.requestId] = job
                    lastStoredProgressAt.update { it + (started.requestId to now()) }
                    job.start()
                }
                refresh(profileId)
            }
        } catch (_: ProfileClosed) {
            // The profile closed while we were starting work for it. Nothing more to do.
        }
    }

    private suspend fun run(profileId: String, row: CatalogueAcquisitionEntity) {
        val requestId = row.requestId
        val self = currentCoroutineContext()[Job]
        val partPath = checkNotNull(row.stagingPath)
        // Whatever is still listed here when the request stops is ours to delete.
        var onDisk: String? = partPath
        try {
            val listingUrl = row.detailUrl
            val download = if (listingUrl == null) {
                DownloadStep.Failed(AcquisitionFailureReason.Refused, 0, null)
            } else {
                worker.download(
                    profileId = profileId,
                    sourceId = row.sourceId,
                    locator = CatalogueAcquisitionLocator(listingUrl, row.publicationKey, row.representationKey),
                    partPath = partPath,
                ) { bytes, declaredLength -> onProgress(profileId, requestId, bytes, declaredLength) }
            }
            when (download) {
                is DownloadStep.Failed -> {
                    fail(profileId, requestId, download.reason) { failed ->
                        failed.copy(
                            bytesSoFar = download.bytes,
                            // For "too large" this is the size that was over the limit.
                            expectedSizeBytes = download.declaredLength ?: failed.expectedSizeBytes,
                            neededBytes = download.neededBytes,
                        )
                    }
                    return
                }
                is DownloadStep.Downloaded -> {
                    val moved = advance(profileId, requestId, AcquisitionEvent.DownloadFinished) { downloaded ->
                        downloaded.copy(bytesSoFar = download.bytes, expectedSizeBytes = download.declaredLength ?: download.bytes)
                    }
                    if (moved !is Advance.Moved) return
                }
            }

            val staged = when (val check = worker.checkAndStage(partPath)) {
                is CheckStep.Failed -> {
                    fail(profileId, requestId, check.reason)
                    return
                }
                is CheckStep.Staged -> check
            }
            onDisk = staged.path
            val adding = advance(profileId, requestId, AcquisitionEvent.CheckPassed) { checked ->
                checked.copy(stagingPath = staged.path, localHash = staged.contentHash, bytesSoFar = staged.sizeBytes)
            }
            if (adding !is Advance.Moved) return

            val book = StagedCatalogueBook(
                requestId = requestId,
                path = staged.path,
                contentHash = staged.contentHash,
                sizeBytes = staged.sizeBytes,
                sourceId = row.sourceId,
                publicationKey = row.publicationKey,
                representationKey = row.representationKey,
                detailIdentity = row.detailIdentity,
                catalogueName = row.catalogueName,
            )
            when (val added = adder.add(profileId, book)) {
                is CatalogueBookAddResult.Added -> {
                    // The adder consumed the file.
                    onDisk = null
                    finishAdded(profileId, requestId, added.libraryBookId)
                }
                is CatalogueBookAddResult.Failed ->
                    fail(profileId, requestId, added.reason) { failed -> failed.copy(neededBytes = added.neededBytes) }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled is ProfileClosed || !currentCoroutineContext().isActive) throw cancelled
            // Not our cancellation: the source's session was closed under the download (turned
            // off, removed, account details changed). The row, if it still exists, can be retried.
            withContext(NonCancellable) { runCatching { fail(profileId, requestId, AcquisitionFailureReason.Connection) } }
        } catch (_: Exception) {
            withContext(NonCancellable) { runCatching { fail(profileId, requestId, AcquisitionFailureReason.Storage) } }
        } finally {
            withContext(NonCancellable) {
                onDisk?.let { path -> deleteStaged(path) }
                lock.withLock { if (jobs[requestId] === self) forget(requestId) }
                pump(profileId)
            }
        }
    }

    /** The book is in the library: finish the request. A request cancelled meanwhile has no row. */
    private suspend fun finishAdded(profileId: String, requestId: String, libraryBookId: String) {
        lock.withLock {
            if (loadedProfileId != profileId) throw ProfileClosed()
            inProfile(profileId) { completeAdd(profileId, requestId, libraryBookId) }
            progress.update { it - requestId }
            refresh(profileId)
        }
    }

    /**
     * Must run inside [inProfile]. The three writes are separate on purpose, in this order.
     * A process that dies between them leaves a request that is still "adding" and already
     * names its book, which [recoverRunning] finishes by running this again: the id is the
     * same, the provenance row is keyed by the request, and "done" is written last.
     *
     * @return the staging path the request still named, for the caller to delete
     */
    private suspend fun completeAdd(profileId: String, requestId: String, libraryBookId: String): String? {
        val row = database.get(requestId) ?: return null
        if (row.acquisitionState() != AcquisitionState.Adding) return null
        val time = now()
        val withBook = row.copy(libraryBookId = libraryBookId, updatedAt = time)
        database.update(withBook)
        sources.insertIfAbsent(row.toBookSource(libraryBookId, sourceAddress(profileId, row.sourceId), time))
        database.update(
            withBook.withState(AcquisitionState.Done, time).copy(
                completedAt = time,
                stagingPath = null,
                detailUrl = null,
            ),
        )
        return row.stagingPath
    }

    private suspend fun fail(
        profileId: String,
        requestId: String,
        reason: AcquisitionFailureReason,
        edit: (CatalogueAcquisitionEntity) -> CatalogueAcquisitionEntity = { it },
    ) {
        val failed = advance(profileId, requestId, AcquisitionEvent.Fail(reason)) { row -> edit(row).copy(stagingPath = null) }
        // Storage is a legal failure in every running state, so a request is never left
        // "running" with nothing running it.
        if (failed == Advance.Rejected && reason != AcquisitionFailureReason.Storage) {
            advance(profileId, requestId, AcquisitionEvent.Fail(AcquisitionFailureReason.Storage)) { row ->
                edit(row).copy(stagingPath = null)
            }
        }
    }

    /**
     * One step of a running request. A request that was cancelled meanwhile has no row, and a
     * move the state machine does not allow changes nothing: both come back as [Advance.Rejected].
     */
    private suspend fun advance(
        profileId: String,
        requestId: String,
        event: AcquisitionEvent,
        edit: (CatalogueAcquisitionEntity) -> CatalogueAcquisitionEntity = { it },
    ): Advance = lock.withLock {
        if (loadedProfileId != profileId) throw ProfileClosed()
        val outcome = inProfile(profileId) {
            val row = database.get(requestId) ?: return@inProfile Advance.Rejected
            val state = row.acquisitionState() ?: return@inProfile Advance.Rejected
            when (val transition = AcquisitionStateMachine.transition(state, event)) {
                AcquisitionTransition.Rejected -> Advance.Rejected
                AcquisitionTransition.Removed -> {
                    database.delete(requestId)
                    Advance.Removed(row)
                }
                is AcquisitionTransition.To -> {
                    val next = edit(row).withState(transition.state, now())
                    database.update(next)
                    Advance.Moved(next)
                }
            }
        }
        if (outcome != Advance.Rejected) progress.update { it - requestId }
        refresh(profileId)
        outcome
    }

    /**
     * Byte counts arrive per chunk. The screen hears about them at most every
     * [LIVE_PROGRESS_INTERVAL_MILLIS]; the database at most every [STORED_PROGRESS_INTERVAL_MILLIS].
     */
    private suspend fun onProgress(profileId: String, requestId: String, bytes: Long, declaredLength: Long?) {
        val time = now()
        val lastLive = lastLiveProgressAt.value[requestId]
        if (lastLive == null || time - lastLive >= LIVE_PROGRESS_INTERVAL_MILLIS || time < lastLive) {
            lastLiveProgressAt.update { it + (requestId to time) }
            progress.update { it + (requestId to Progress(bytes, declaredLength)) }
        }
        val lastStored = lastStoredProgressAt.value[requestId] ?: time
        if (time - lastStored >= STORED_PROGRESS_INTERVAL_MILLIS) {
            lastStoredProgressAt.update { it + (requestId to time) }
            lock.withLock {
                if (loadedProfileId != profileId) throw ProfileClosed()
                inProfile(profileId) { database.updateProgress(requestId, bytes, declaredLength, time) }
            }
        }
    }

    /**
     * First touch of a profile in this process, or after it was closed: whatever its database
     * says is running is not running, so it becomes interrupted. Must hold [lock].
     */
    private suspend fun ensureLoaded(profileId: String) {
        if (loadedProfileId == profileId) return
        jobs.values.forEach { job -> job.cancel() }
        jobs.clear()
        clearProgress()
        loadedProfileId = null
        resumed = false
        if (!profileWork.register(profileId, this) { profileClosed(profileId) }) throw ProfileClosed()
        inProfile(profileId) {
            database.deleteCompletedBefore(now() - CatalogueAcquisitionLimits.FINISHED_RETENTION_MILLIS)
        }
        val leftovers = recoverRunning(profileId)
        loadedProfileId = profileId
        refresh(profileId)
        leftovers.forEach { path -> deleteStaged(path) }
        sweepStaging(profileId)
    }

    /**
     * Nothing is running for [profileId] any more; make its rows say so. Must hold [lock].
     *
     * A request that was being added is finished when its book is in the library: either the
     * row already names it, or the library holds a file with the request's bytes. Everything
     * else that was running becomes interrupted and waits for "Start again", and so does a row
     * whose state this version does not know.
     *
     * @return the staging files these requests no longer need
     */
    private suspend fun recoverRunning(profileId: String): List<String> {
        // The library first: it finishes or undoes a half-done import, and only then can it
        // be asked which books it has. Outside the database lock: this is file work.
        val library = activeProfileId() == profileId
        if (library) quietly { adder.settleInterruptedAdds(profileId) }
        val adding = inProfile(profileId) { database.getByStates(listOf(AcquisitionState.Adding.key)) }
        val added = adding.mapNotNull { row ->
            val bookId = row.libraryBookId
                ?: row.localHash?.takeIf { library }?.let { hash -> quietly { adder.findAddedBook(profileId, hash) } }
            bookId?.let { id -> row.requestId to id }
        }
        return inProfile(profileId) {
            val leftovers = added.mapNotNull { (requestId, bookId) -> completeAdd(profileId, requestId, bookId) }
            leftovers + interruptRunning()
        }
    }

    /** Must run inside [inProfile]. Returns the staging files the interrupted requests left. */
    private suspend fun interruptRunning(): List<String> {
        val running = database.getByStates(
            listOf(AcquisitionState.Downloading.key, AcquisitionState.Checking.key, AcquisitionState.Adding.key),
        )
        running.forEach { row ->
            val state = row.acquisitionState() ?: return@forEach
            val transition = AcquisitionStateMachine.transition(state, AcquisitionEvent.AppRestarted)
            if (transition is AcquisitionTransition.To) {
                database.update(row.withState(transition.state, now()).copy(stagingPath = null, localHash = null))
            }
        }
        // Written by a newer Parrot, or damaged. Hidden rows would sit here forever, so they
        // become something the user can start again or cancel.
        val unknown = database.getAll().filter { row -> row.acquisitionState() == null }
        unknown.forEach { row ->
            database.update(row.withState(AcquisitionState.Interrupted, now()).copy(stagingPath = null, localHash = null))
        }
        return (running + unknown).mapNotNull { row -> row.stagingPath }
    }

    /**
     * Deletes files in the profile's staging folder that no request refers to: what a crash
     * or a cleared row left behind. Must hold [lock], so no download can be creating one.
     */
    private suspend fun sweepStaging(profileId: String) {
        val referenced = inProfile(profileId) { database.getAll() }
            .mapNotNull { row -> row.stagingPath }
            .flatMap { path -> listOf(path, CatalogueStagingFiles.stagedPathFor(path)) }
            .toSet()
        files.list(profileId).filterNot { path -> path in referenced }.forEach { path -> files.delete(path) }
    }

    /** For work that must not stop the queue from loading: a failure counts as "nothing found". */
    private suspend fun <T> quietly(work: suspend () -> T): T? = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * The profile is being switched away from or deleted. The registry calls this before the
     * profile's database closes, so its running requests can still be stored as interrupted.
     */
    private suspend fun profileClosed(profileId: String) {
        val stopped: List<Job>
        var leftovers = emptyList<String>()
        lock.withLock {
            if (loadedProfileId != profileId) return
            stopped = jobs.values.toList()
            stopped.forEach { job -> job.cancel() }
            jobs.clear()
            clearProgress()
            loadedProfileId = null
            resumed = false
            snapshot.value = emptyList()
            leftovers = try {
                // An add that was in its last step may have put its book in the library.
                recoverRunning(profileId)
            } catch (_: ProfileClosed) {
                // Already closed. The rows are caught the next time this profile is loaded.
                emptyList()
            }
        }
        stopped.forEach { job -> job.join() }
        leftovers.forEach { path -> deleteStaged(path) }
    }

    /** Must hold [lock]. */
    private fun forget(requestId: String): Job? {
        progress.update { it - requestId }
        lastLiveProgressAt.update { it - requestId }
        lastStoredProgressAt.update { it - requestId }
        return jobs.remove(requestId)
    }

    private fun clearProgress() {
        progress.value = emptyMap()
        lastLiveProgressAt.value = emptyMap()
        lastStoredProgressAt.value = emptyMap()
    }

    /** Must hold [lock]. */
    private suspend fun refresh(profileId: String) {
        if (loadedProfileId != profileId) return
        snapshot.value = inProfile(profileId) { database.getAll() }.mapNotNull { row -> row.toAcquisition() }
    }

    /** A request's file is either still `.epub.part` or already `.epub`; remove both names. */
    private suspend fun deleteStaged(path: String) {
        files.delete(path)
        if (path.endsWith(CatalogueStagingFiles.PART_SUFFIX)) {
            files.delete(CatalogueStagingFiles.stagedPathFor(path))
        }
    }

    private fun requireProfile(): String = activeProfileId() ?: throw ProfileClosed()

    /**
     * The only way this class reaches the database. `withProfile` refuses a profile that is
     * not the open one; that refusal ends the work instead of surfacing as an error.
     */
    private suspend fun <T> inProfile(profileId: String, operation: suspend () -> T): T = try {
        session.withProfile(profileId, operation)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalStateException) {
        throw ProfileClosed()
    }

    companion object {
        const val LIVE_PROGRESS_INTERVAL_MILLIS: Long = 250
        const val STORED_PROGRESS_INTERVAL_MILLIS: Long = 2_000
    }
}
