package com.retro99.reader.ui.tts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * When the controller re-reads what is prepared for a chapter, and what the cloud holds for
 * it. Every source is a state flow, so a fresh collector is answered at once with what is
 * true now: that is what makes the row right when the Listening sheet is opened again, or
 * when the reader leaves the chapter and comes back.
 *
 * @param preparation the one preparation job moving.
 * @param revision bumped by the controller after anything it did itself.
 * @param settled bumped when the transfer engine moves a prepared chapter of its own
 *   accord. Without it an upload that finished on the server, or a download that was
 *   installed, left the row saying what it said before.
 */
internal fun preparedAudioTriggers(
    preparation: Flow<*>,
    revision: Flow<*>,
    settled: Flow<*>,
): Flow<Unit> = combine(preparation, revision, settled) { _, _, _ -> Unit }
