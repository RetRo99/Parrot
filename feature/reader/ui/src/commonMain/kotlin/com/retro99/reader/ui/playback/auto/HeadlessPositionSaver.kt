package com.retro99.reader.ui.playback.auto

import com.retro99.reader.domain.model.PositionDomainModel
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/** Why a headless (Android Auto, background) session saves its position. */
enum class HeadlessSaveReason(val propagatesToLinkedCopies: Boolean) {
    Periodic(propagatesToLinkedCopies = false),
    ChapterEnd(propagatesToLinkedCopies = false),

    /** Pausing ends a listening stretch. */
    Pause(propagatesToLinkedCopies = true),

    /** The session's final position. */
    Close(propagatesToLinkedCopies = true),
}

/**
 * Saves a headless session's position and, when listening stops (pause or close), moves the
 * other linked copies there too, as the in-app players do. [propagate] is
 * `PropagateToLinkedCopiesUseCase`, which respects the "Update linked copies" setting.
 * Never throws, except for cancellation.
 *
 * @param save returns whether the position was saved; a failed save doesn't propagate.
 */
class HeadlessPositionSaver(
    private val save: suspend (PositionDomainModel) -> Boolean,
    private val propagate: suspend (PositionDomainModel) -> Unit,
    private val observedAt: () -> String = { Clock.System.now().toString() },
    private val onError: (Exception, String) -> Unit,
) {
    suspend fun save(position: PositionDomainModel, reason: HeadlessSaveReason) {
        val saved = try {
            save(position)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e, "HeadlessSession: Failed to save position")
            false
        }
        if (!saved || !reason.propagatesToLinkedCopies) return
        try {
            propagate(position.copy(observedAt = observedAt()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e, "HeadlessSession: Failed to update linked copies")
        }
    }
}
