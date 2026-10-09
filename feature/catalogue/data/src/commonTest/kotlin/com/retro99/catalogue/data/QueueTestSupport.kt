package com.retro99.catalogue.data

import com.retro99.catalogue.domain.AcquisitionState
import com.retro99.catalogue.domain.CatalogueAcquisition
import com.retro99.catalogue.domain.CatalogueAcquisitionManager
import com.retro99.catalogue.domain.CatalogueAcquisitionRequest
import com.retro99.catalogue.domain.CatalogueRequestOutcome
import com.retro99.catalogue.domain.CatalogueBookAddResult
import com.retro99.catalogue.domain.CatalogueBookAdder
import com.retro99.catalogue.domain.StagedCatalogueBook
import com.retro99.database.api.ProfileDatabaseSession
import com.retro99.database.api.catalogue.CatalogueAcquisitionEntity
import com.retro99.database.api.catalogue.CatalogueAcquisitionsDatabase
import com.retro99.database.api.catalogue.CatalogueBookSourceEntity
import com.retro99.database.api.catalogue.CatalogueBookSourcesDatabase
import com.retro99.database.api.catalogue.CatalogueLibraryMatch
import com.retro99.epub.api.EpubFileCheck
import com.retro99.epub.api.EpubFileChecker
import com.retro99.server.api.CatalogueAcquisitionLocator
import com.retro99.server.api.CatalogueDownloadOutcome
import com.retro99.server.api.CatalogueFileSink
import com.retro99.user.implementation.ProfileWorkRegistryImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared between the fakes: which profile is open, and whether the database lock is held. */
internal class TestWorld {
    var activeProfile: String? = "p1"
    var databaseLocked = false
    var time = 1_000_000L

    /** Books in each profile's library, by id. A book that is deleted is taken out of here. */
    val libraryBooks = mutableMapOf<String, MutableSet<String>>()

    /** Books that have a file on this device, by profile and then by the file's hash. */
    val booksOnDevice = mutableMapOf<String, MutableMap<String, String>>()

    fun addToLibrary(profileId: String, contentHash: String, libraryBookId: String) {
        libraryBooks.getOrPut(profileId) { mutableSetOf() } += libraryBookId
        booksOnDevice.getOrPut(profileId) { mutableMapOf() }[contentHash] = libraryBookId
    }

    /** Filled when slow work runs while the database lock is held. Must stay empty. */
    val lockViolations = mutableListOf<String>()

    fun slowWork(what: String) {
        if (databaseLocked) lockViolations += what
    }
}

/** Behaves like the real session: refuses a profile that is not open, one operation at a time. */
internal class FakeSession(private val world: TestWorld) : ProfileDatabaseSession {
    private val mutex = Mutex()

    override suspend fun <T> withProfile(localProfileId: String, operation: suspend () -> T): T = mutex.withLock {
        check(world.activeProfile == localProfileId) { "Database operation started for an inactive profile" }
        world.databaseLocked = true
        try {
            val result = operation()
            check(world.activeProfile == localProfileId) { "Database operation completed for an inactive profile" }
            result
        } finally {
            world.databaseLocked = false
        }
    }
}

/** One table per profile, like one database file per profile. Refuses calls outside the lock. */
internal class FakeAcquisitionsDatabase(private val world: TestWorld) : CatalogueAcquisitionsDatabase {
    private val tables = mutableMapOf<String, LinkedHashMap<String, CatalogueAcquisitionEntity>>()
    var progressWrites = 0
    var writes = 0

    /** For assertions only: looks at a profile's table without going through the session. */
    fun peek(profileId: String): List<CatalogueAcquisitionEntity> =
        tables[profileId].orEmpty().values.sortedBy { it.queuePosition }

    private fun table(): LinkedHashMap<String, CatalogueAcquisitionEntity> {
        check(world.databaseLocked) { "The database was used outside withProfile" }
        return tables.getOrPut(checkNotNull(world.activeProfile)) { LinkedHashMap() }
    }

    override suspend fun insert(acquisition: CatalogueAcquisitionEntity) {
        writes++
        check(table().put(acquisition.requestId, acquisition) == null) { "Duplicate request id" }
    }

    override suspend fun update(acquisition: CatalogueAcquisitionEntity) {
        writes++
        val table = table()
        // A row that is not in the open profile's table would be a write into another profile.
        if (acquisition.requestId in table) table[acquisition.requestId] = acquisition
    }

    override suspend fun updateProgress(requestId: String, bytesSoFar: Long, expectedSizeBytes: Long?, updatedAt: Long) {
        writes++
        progressWrites++
        val table = table()
        val row = table[requestId] ?: return
        if (row.state == CatalogueAcquisitionEntity.STATE_DOWNLOADING) {
            table[requestId] = row.copy(bytesSoFar = bytesSoFar, expectedSizeBytes = expectedSizeBytes, updatedAt = updatedAt)
        }
    }

    override suspend fun get(requestId: String) = table()[requestId]

    override suspend fun findUnfinished(sourceId: String, publicationKey: String, representationKey: String) =
        table().values.sortedBy { it.queuePosition }.firstOrNull { row ->
            row.sourceId == sourceId && row.publicationKey == publicationKey &&
                row.representationKey == representationKey && row.state != CatalogueAcquisitionEntity.STATE_DONE
        }

    override suspend fun getAll() = table().values.sortedBy { it.queuePosition }

    override suspend fun getBySource(sourceId: String) = getAll().filter { it.sourceId == sourceId }

    override suspend fun getByStates(states: List<String>) = getAll().filter { it.state in states }

    override suspend fun getCompletedSince(sinceMillis: Long) = table().values
        .filter { it.state == CatalogueAcquisitionEntity.STATE_DONE && (it.completedAt ?: Long.MIN_VALUE) >= sinceMillis }
        .sortedByDescending { it.completedAt }

    override suspend fun nextQueuePosition() = (table().values.maxOfOrNull { it.queuePosition } ?: 0L) + 1

    /** Runs after a row is deleted: the instant a cancel has landed. */
    var afterDelete: (String) -> Unit = {}

    override suspend fun delete(requestId: String) {
        writes++
        table().remove(requestId)
        afterDelete(requestId)
    }

    override suspend fun deleteCompleted() {
        writes++
        table().values.removeAll { it.state == CatalogueAcquisitionEntity.STATE_DONE }
    }

    override suspend fun deleteCompletedBefore(beforeMillis: Long) {
        table().values.removeAll { row ->
            row.state == CatalogueAcquisitionEntity.STATE_DONE && (row.completedAt ?: Long.MAX_VALUE) < beforeMillis
        }
    }
}

/** Staging files as sizes and a running checksum, so large "files" cost no memory. */
internal class FakeStagingFiles(private val world: TestWorld) : CatalogueStagingFiles {
    class Stored(var size: Long = 0, var checksum: Long = 0)

    val files = mutableMapOf<String, Stored>()
    val created = mutableListOf<String>()
    var freeSpace: Long? = 10L * 1024 * 1024 * 1024

    /** Free space reported once this many bytes have been written in total. */
    var freeSpaceAfterWrites: Pair<Long, Long>? = null
    var failWritesAt: Long? = null
    var failOpen = false
    var failRename = false
    private var counter = 0
    private var written = 0L

    override fun newPartPath(profileId: String): String =
        "/staging/$profileId/generated-${++counter}${CatalogueStagingFiles.PART_SUFFIX}"

    override suspend fun openForWriting(path: String): StagingWriter {
        world.slowWork("open staging file")
        if (failOpen) error("read-only file system")
        val stored = Stored()
        files[path] = stored
        created += path
        return object : StagingWriter {
            override fun write(buffer: ByteArray, length: Int) {
                failWritesAt?.let { limit -> if (stored.size + length > limit) error("no space left on device") }
                stored.size += length
                written += length
                for (index in 0 until length) stored.checksum += buffer[index]
            }

            override fun close() = Unit
        }
    }

    override suspend fun freeSpaceBytes(): Long? {
        val change = freeSpaceAfterWrites
        return if (change != null && written >= change.first) change.second else freeSpace
    }

    override suspend fun size(path: String) = files[path]?.size ?: 0L

    override suspend fun sha256(path: String): String {
        world.slowWork("hash")
        val stored = files[path] ?: error("no such file")
        return "sha-${stored.size}-${stored.checksum}"
    }

    override suspend fun rename(from: String, to: String): Boolean {
        if (failRename) return false
        val stored = files.remove(from) ?: return false
        files[to] = stored
        return true
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }

    override suspend fun list(profileId: String): List<String> =
        files.keys.filter { path -> path.startsWith("/staging/$profileId/") }

    override suspend fun deleteFoldersExcept(profileIds: Set<String>) {
        files.keys.removeAll { path -> path.removePrefix("/staging/").substringBefore('/') !in profileIds }
    }
}

/** Provenance rows per profile. "In the library" is decided by [TestWorld.libraryBooks], like the real join. */
internal class FakeBookSourcesDatabase(private val world: TestWorld) : CatalogueBookSourcesDatabase {
    private val tables = mutableMapOf<String, MutableList<CatalogueBookSourceEntity>>()

    /** The process stops right after a provenance row is written. */
    var dieAfterInsert = false

    /** Runs just before a row is written, to look at what else is stored at that moment. */
    var beforeInsert: (CatalogueBookSourceEntity) -> Unit = {}

    fun peek(profileId: String = "p1"): List<CatalogueBookSourceEntity> = tables[profileId].orEmpty().toList()

    private fun table(): MutableList<CatalogueBookSourceEntity> {
        check(world.databaseLocked) { "The database was used outside withProfile" }
        return tables.getOrPut(checkNotNull(world.activeProfile)) { mutableListOf() }
    }

    override suspend fun insert(source: CatalogueBookSourceEntity) {
        table() += source
    }

    override suspend fun insertIfAbsent(source: CatalogueBookSourceEntity) {
        val table = table()
        beforeInsert(source)
        if (table.none { it.id == source.id }) table += source
        if (dieAfterInsert) awaitCancellation()
    }

    override suspend fun findInLibrary(sourceId: String, identities: Collection<String>): List<CatalogueLibraryMatch> {
        val books = world.libraryBooks[world.activeProfile].orEmpty()
        return table()
            .filter { row -> row.sourceId == sourceId && row.libraryBookId in books }
            .filter { row -> row.publicationKey in identities || row.detailIdentity in identities }
            .sortedBy { row -> row.acquiredAt }
            .map { row -> CatalogueLibraryMatch(row.publicationKey, row.detailIdentity, row.libraryBookId) }
    }

    override suspend fun getForBook(libraryBookId: String) = table().filter { it.libraryBookId == libraryBookId }

    override suspend fun getForSource(sourceId: String) = table().filter { it.sourceId == sourceId }

    override suspend fun countBooksForSource(sourceId: String) =
        table().filter { it.sourceId == sourceId }.distinctBy { it.libraryBookId }.size.toLong()
}

internal class FakeFileSource(private val world: TestWorld) : AcquisitionFileSource {
    class Call(val profileId: String, val sourceId: String, val locator: CatalogueAcquisitionLocator)

    val calls = mutableListOf<Call>()
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var holdEverything = false
    private val running = mutableSetOf<String>()
    var mostRunningAtOnce = 0
    val cancelled = mutableListOf<String>()

    /** Chunks sent for a publication key; 3 x 1000 bytes unless a test says otherwise. */
    val chunks = mutableMapOf<String, List<Int>>()

    /** The Content-Length to declare; the true size unless a test says otherwise. */
    val declared = mutableMapOf<String, Long?>()
    val undeclared = mutableSetOf<String>()

    /** What to answer after the bytes went out, when not a plain success. */
    val outcomes = mutableMapOf<String, ArrayDeque<CatalogueDownloadOutcome>>()

    /** Runs after each chunk; a test advances the clock or switches the profile here. */
    var afterChunk: suspend (publicationKey: String, chunkIndex: Int) -> Unit = { _, _ -> }

    fun holdAll() {
        holdEverything = true
    }

    fun hold(publicationKey: String) {
        gates[publicationKey] = CompletableDeferred()
    }

    fun release(publicationKey: String) {
        gates.getOrPut(publicationKey) { CompletableDeferred() }.complete(Unit)
    }

    fun releaseAll() {
        holdEverything = false
        gates.values.forEach { it.complete(Unit) }
    }

    fun failNext(publicationKey: String, outcome: CatalogueDownloadOutcome) {
        outcomes.getOrPut(publicationKey) { ArrayDeque() }.addLast(outcome)
    }

    override suspend fun download(
        profileId: String,
        sourceId: String,
        locator: CatalogueAcquisitionLocator,
        sink: CatalogueFileSink,
    ): CatalogueDownloadOutcome {
        world.slowWork("network")
        val key = locator.publicationKey
        calls += Call(profileId, sourceId, locator)
        running += key
        mostRunningAtOnce = maxOf(mostRunningAtOnce, running.size)
        try {
            val scripted = outcomes[key]?.removeFirstOrNull()
            if (scripted is CatalogueDownloadOutcome.Failed) return scripted
            val sizes = chunks[key] ?: listOf(1000, 1000, 1000)
            val total = sizes.sum().toLong()
            val length = if (key in undeclared) null else declared[key] ?: total
            try {
                sink.start(length)
                if (holdEverything && key !in gates) gates[key] = CompletableDeferred()
                gates[key]?.await()
                sizes.forEachIndexed { index, size ->
                    world.slowWork("network")
                    sink.write(ByteArray(size) { 1 }, size)
                    afterChunk(key, index)
                }
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelled += key
                throw cancellation
            } catch (error: Exception) {
                return CatalogueDownloadOutcome.SinkFailed(error)
            }
            return scripted ?: CatalogueDownloadOutcome.Complete(total, length)
        } finally {
            running -= key
        }
    }
}

internal class FakeChecker(private val world: TestWorld) : EpubFileChecker {
    var result: EpubFileCheck = EpubFileCheck.Valid
    var gate: CompletableDeferred<Unit>? = null
    val checked = mutableListOf<String>()

    override suspend fun check(filePath: String): EpubFileCheck {
        world.slowWork("file check")
        checked += filePath
        gate?.await()
        return result
    }
}

/**
 * Stands in for the library import. A book it adds is in [TestWorld.libraryBooks] and on the
 * device under the file's hash, which is all the queue can ask the real library either.
 */
internal class FakeAdder(private val world: TestWorld, private val files: FakeStagingFiles) : CatalogueBookAdder {
    class Call(val profileId: String, val book: StagedCatalogueBook)

    val calls = mutableListOf<Call>()
    var gate: CompletableDeferred<Unit>? = null
    var settled = 0

    /** Null: the import never comes back, so the request stays "adding" with its file staged. */
    var result: ((StagedCatalogueBook) -> CatalogueBookAddResult)? = { book -> CatalogueBookAddResult.Added("lib-${book.publicationKey}") }

    /** The process stops before the library touched anything. */
    var dieBeforeAdding = false

    /** The process stops after the book is in the library and before the queue hears of it. */
    var dieAfterAdding = false

    /**
     * Runs once the book is in the library and before the queue hears of it. The real import
     * cannot be cancelled at that point, so this runs even when the request was.
     */
    var whileCommitting: (suspend () -> Unit)? = null

    override suspend fun add(profileId: String, book: StagedCatalogueBook): CatalogueBookAddResult {
        world.slowWork("add to library")
        calls += Call(profileId, book)
        gate?.await()
        if (dieBeforeAdding) awaitCancellation()
        val answer = result?.invoke(book) ?: awaitCancellation()
        if (answer is CatalogueBookAddResult.Added) {
            // A real import consumes the staged file.
            files.delete(book.path)
            world.addToLibrary(profileId, book.contentHash, answer.libraryBookId)
            whileCommitting?.let { step -> withContext(NonCancellable) { step() } }
        }
        if (dieAfterAdding) awaitCancellation()
        return answer
    }

    override suspend fun settleInterruptedAdds(profileId: String) {
        world.slowWork("settle the library")
        settled++
    }

    override suspend fun findAddedBook(profileId: String, contentHash: String): String? {
        world.slowWork("look in the library")
        return world.booksOnDevice[profileId]?.get(contentHash)
    }
}

/** A queue over fakes. [restart] gives a new queue over the same database and files. */
internal class QueueHarness(private val scope: CoroutineScope, val world: TestWorld = TestWorld()) {
    var session = FakeSession(world)
        private set
    val database = FakeAcquisitionsDatabase(world)
    val sources = FakeBookSourcesDatabase(world)
    val files = FakeStagingFiles(world)
    val source = FakeFileSource(world)
    val checker = FakeChecker(world)
    val adder = FakeAdder(world, files)
    val profileWork = ProfileWorkRegistryImpl()

    /** The registered address of each catalogue. A removed catalogue has none. */
    val sourceAddresses = mutableMapOf("source-1" to "https://books.example/opds/secret-key-in-path?token=1")
    private var ids = 0
    var queue = newQueue()
        private set

    private fun newQueue() = CatalogueAcquisitionQueue(
        session = session,
        database = database,
        sources = sources,
        activeProfileId = { world.activeProfile },
        profileWork = profileWork,
        worker = AcquisitionWorker(source, files, checker),
        files = files,
        adder = adder,
        sourceAddress = { _, sourceId -> sourceAddresses[sourceId] },
        scope = scope,
        now = { world.time },
        newRequestId = { "request-${++ids}" },
    )

    /**
     * Parrot was closed and opened again: memory is gone, the database and files are not.
     * Whatever the old process was in the middle of stays where it stopped, locks included.
     */
    fun restart(): CatalogueAcquisitionQueue {
        world.databaseLocked = false
        session = FakeSession(world)
        adder.dieBeforeAdding = false
        adder.dieAfterAdding = false
        sources.dieAfterInsert = false
        queue = newQueue()
        return queue
    }

    /** What the user registry does on a profile switch: close the old profile's work first. */
    suspend fun switchProfile(to: String) {
        world.activeProfile?.let { old -> profileWork.cancel(old) }
        profileWork.activate(to)
        world.activeProfile = to
    }

    fun row(publicationKey: String, profileId: String = "p1"): CatalogueAcquisitionEntity? =
        database.peek(profileId).firstOrNull { it.publicationKey == publicationKey }

    fun state(publicationKey: String, profileId: String = "p1"): AcquisitionState? =
        row(publicationKey, profileId)?.acquisitionState()

    fun states(profileId: String = "p1"): Map<String, AcquisitionState?> =
        database.peek(profileId).associate { it.publicationKey to it.acquisitionState() }
}

internal fun bookRequest(
    number: Int,
    sourceId: String = "source-1",
    representationKey: String = "application/epub+zip#1",
) = CatalogueAcquisitionRequest(
    sourceId = sourceId,
    publicationKey = "book-$number",
    representationKey = representationKey,
    detailIdentity = "urn:entry:$number",
    listingUrl = "https://books.example/opds/new",
    title = "Book $number",
    author = "A. Writer",
    coverReference = "https://books.example/covers/$number.jpg",
    catalogueName = "Home shelf",
)

/** For tests that expect a stored request and not "already in your library". */
internal suspend fun CatalogueAcquisitionManager.requestQueued(request: CatalogueAcquisitionRequest): CatalogueAcquisition =
    (request(request) as CatalogueRequestOutcome.Queued).acquisition
