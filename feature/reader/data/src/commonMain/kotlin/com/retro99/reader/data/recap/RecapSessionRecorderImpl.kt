package com.retro99.reader.data.recap

import com.retro99.database.api.recap.SessionRecapCapture
import com.retro99.database.api.recap.SessionRecapDatabase
import com.retro99.database.api.recap.SessionRecapEntity
import com.retro99.reader.domain.recap.RecapChapter
import com.retro99.reader.domain.recap.RecapEligibility
import com.retro99.reader.domain.recap.RecapEligibilityDecision
import com.retro99.reader.domain.recap.RecapExcerptBuffer
import com.retro99.reader.domain.recap.RecapLimits
import com.retro99.reader.domain.recap.RecapPosition
import com.retro99.reader.domain.recap.RecapSessionRecorder
import com.retro99.reader.domain.recap.RecapSessionStats
import com.retro99.reader.domain.recap.RecapSettings
import com.retro99.reader.domain.recap.RecapStatus
import com.retro99.reader.domain.recap.RecapTextSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Records sessions for recaps. Every call is queued and handled in order
 * on one app-scoped coroutine, so the reader never waits on the database
 * and a closing ViewModel can't cancel the final write.
 */
class RecapSessionRecorderImpl(
    private val database: SessionRecapDatabase,
    private val settings: RecapSettings,
    private val diagnostics: RecapDiagnostics,
    private val onSessionReady: () -> Unit,
    private val clock: Clock = Clock.System,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : RecapSessionRecorder {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val live = mutableMapOf<String, LiveSession>()

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.failure(e, stage = "record")
                }
            }
        }
    }

    override fun onSessionStarted(
        sessionId: String,
        serverId: String,
        bookId: String,
        startPosition: RecapPosition,
        chapter: RecapChapter?,
        language: String?,
    ) = enqueue {
        if (sessionId in live) return@enqueue
        // No consent, no capture: nothing is stored for this session.
        if (!settings.isCloudRecapsEnabled()) {
            live[sessionId] = LiveSession.Disabled
            return@enqueue
        }
        val now = clock.now().toEpochMilliseconds()
        val session = LiveSession.Capturing(
            last = startPosition,
            furthest = startPosition.totalProgression,
            endChapter = chapter,
        )
        live[sessionId] = session
        database.insertCapturing(
            SessionRecapEntity(
                sessionId = sessionId,
                serverId = serverId,
                bookUuid = bookId,
                status = RecapStatus.CAPTURING.name,
                startHref = startPosition.href,
                startProgression = startPosition.progression,
                startTotalProgression = startPosition.totalProgression,
                startChapterIndex = chapter?.index,
                startChapterTitle = chapter?.title,
                furthestTotalProgression = startPosition.totalProgression,
                language = language,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override fun appendReadText(
        sessionId: String,
        text: String,
        chapter: RecapChapter?,
        position: RecapPosition?,
        source: RecapTextSource,
    ) = enqueue {
        val session = live[sessionId] as? LiveSession.Capturing ?: return@enqueue
        val added = session.buffer.append(text)
        when (source) {
            RecapTextSource.TTS_SENTENCE -> if (added) session.ttsSentences++
            RecapTextSource.PAGE -> if (position != null && session.isAdvance(position)) {
                session.pageAdvances++
            }
        }
        position?.let(session::moveTo)
        chapter?.let { session.endChapter = it }
        database.updateCapture(sessionId, session.capture(), clock.now().toEpochMilliseconds())
    }

    override fun onSessionEnded(
        sessionId: String,
        endPosition: RecapPosition?,
        lastSentence: String?,
        activeReadingMs: Long,
    ) = enqueue {
        val session = live.remove(sessionId) as? LiveSession.Capturing ?: return@enqueue
        endPosition?.let(session::moveTo)
        val row = database.getRecap(sessionId) ?: return@enqueue
        finish(row, session.capture(), lastSentence, activeReadingMs)
    }

    /**
     * Ends rows left CAPTURING by a previous process (killed or crashed).
     * Uses only what was persisted; queued so it can't race a new session.
     * Throws when the database isn't available yet.
     */
    suspend fun recoverAbandoned() {
        val done = CompletableDeferred<Unit>()
        enqueue {
            val result = runCatching {
                database.getCapturing()
                    .filter { row -> row.sessionId !in live }
                    .forEach { row ->
                        val capture = SessionRecapCapture(
                            excerpt = row.excerpt,
                            endHref = row.endHref,
                            endProgression = row.endProgression,
                            endTotalProgression = row.endTotalProgression,
                            endChapterIndex = row.endChapterIndex,
                            endChapterTitle = row.endChapterTitle,
                            furthestTotalProgression = row.furthestTotalProgression,
                            pageAdvances = row.pageAdvances,
                            ttsSentences = row.ttsSentences,
                        )
                        // Last persisted write approximates the end of reading.
                        finish(row, capture, null, row.updatedAt - row.createdAt)
                    }
            }
            result.exceptionOrNull()?.let { done.completeExceptionally(it) } ?: done.complete(Unit)
        }
        done.await()
    }

    private suspend fun finish(
        row: SessionRecapEntity,
        capture: SessionRecapCapture,
        lastSentence: String?,
        activeReadingMs: Long,
    ) {
        val excerpt = capture.excerpt?.trim()?.takeIf { it.isNotEmpty() }
        val previous = database.getPreviousEnded(row.bookUuid, row.sessionId, row.createdAt)
        val decision = RecapEligibility.evaluate(
            RecapSessionStats(
                activeReadingMs = activeReadingMs,
                pageAdvances = capture.pageAdvances,
                ttsSentences = capture.ttsSentences,
                excerpt = excerpt,
                startTotalProgression = row.startTotalProgression,
                endTotalProgression = capture.endTotalProgression,
                furthestTotalProgression = capture.furthestTotalProgression,
                previousExcerpt = previous?.excerpt,
                previousExcerptHash = previous?.excerptHash,
            ),
        )
        val eligible = decision is RecapEligibilityDecision.Eligible
        val reason = (decision as? RecapEligibilityDecision.Ineligible)?.reason
        val finished = database.finishCapture(
            sessionId = row.sessionId,
            status = if (eligible) RecapStatus.PENDING.name else RecapStatus.SKIPPED_INELIGIBLE.name,
            // A skipped session keeps only the fingerprint, never the text.
            capture = capture.copy(excerpt = excerpt.takeIf { eligible }),
            excerptHash = excerpt?.let(RecapEligibility::fingerprint),
            lastSentence = lastSentence?.trim()?.take(RecapLimits.MAX_LAST_SENTENCE_CHARS)
                ?.takeIf { eligible && it.isNotEmpty() },
            activeReadingMs = activeReadingMs.coerceAtLeast(0),
            lastError = reason?.name,
            endedAt = clock.now().toEpochMilliseconds(),
        )
        if (!finished) return
        diagnostics.breadcrumb(
            stage = "session_end",
            outcome = if (eligible) "pending" else "skipped",
            reasonCode = reason?.name,
        )
        if (eligible) onSessionReady()
    }

    private fun enqueue(command: suspend () -> Unit) {
        commands.trySend(command)
    }

    private sealed interface LiveSession {
        /** Consent was off at start; ignore the session entirely. */
        data object Disabled : LiveSession

        class Capturing(
            var last: RecapPosition,
            var furthest: Double?,
            var endChapter: RecapChapter?,
        ) : LiveSession {
            val buffer = RecapExcerptBuffer()
            var pageAdvances = 0
            var ttsSentences = 0

            /** A forward move to a new place counts as one page advance. */
            fun isAdvance(position: RecapPosition): Boolean {
                val to = position.totalProgression ?: return position.href != last.href
                val from = last.totalProgression ?: return true
                return to > from + RecapEligibility.PROGRESSION_EPSILON
            }

            fun moveTo(position: RecapPosition) {
                last = position
                position.totalProgression?.let { to ->
                    furthest = maxOf(furthest ?: to, to)
                }
            }

            fun capture() = SessionRecapCapture(
                excerpt = buffer.text().takeIf { it.isNotEmpty() },
                endHref = last.href,
                endProgression = last.progression,
                endTotalProgression = last.totalProgression,
                endChapterIndex = endChapter?.index,
                endChapterTitle = endChapter?.title,
                furthestTotalProgression = furthest,
                pageAdvances = pageAdvances,
                ttsSentences = ttsSentences,
            )
        }
    }
}
