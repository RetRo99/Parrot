package com.retro99.reader.ui.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What makes the controller re-read a chapter's prepared and cloud state. The transfer
 * engine settling is the one that was missing: an upload that finished on the server and a
 * download that was installed both left the row saying what it had said before.
 */
class PreparedAudioTriggersTest {

    @Test fun `the engine settling is a reason to re-read`() = runTest {
        val preparation = MutableStateFlow<TtsChapterPreparationState>(TtsChapterPreparationState.Idle)
        val revision = MutableStateFlow(0)
        val settled = MutableStateFlow(0)
        val seen = mutableListOf<Unit>()
        val collecting = launch { preparedAudioTriggers(preparation, revision, settled).toList(seen) }
        advanceUntilIdle()
        assertEquals(1, seen.size, "a collector is answered at once")

        settled.value += 1
        advanceUntilIdle()
        assertEquals(2, seen.size, "the engine settled, so the row has to read again")

        settled.value += 1
        advanceUntilIdle()
        assertEquals(3, seen.size)
        collecting.cancel()
    }

    @Test fun `the sheet opened again, or the chapter left and come back, reads afresh`() = runTest {
        val preparation = MutableStateFlow<TtsChapterPreparationState>(TtsChapterPreparationState.Idle)
        val revision = MutableStateFlow(0)
        val settled = MutableStateFlow(0)
        // The transfer finished while nobody was looking.
        settled.value = 4

        val seen = mutableListOf<Unit>()
        val collecting = launch { preparedAudioTriggers(preparation, revision, settled).toList(seen) }
        advanceUntilIdle()

        assertEquals(1, seen.size, "a new collector is answered without waiting for a change")
        collecting.cancel()
    }

    @Test fun `the preparation job and the controller's own doings still count`() = runTest {
        val preparation = MutableStateFlow<TtsChapterPreparationState>(TtsChapterPreparationState.Idle)
        val revision = MutableStateFlow(0)
        val settled = MutableStateFlow(0)
        val seen = mutableListOf<Unit>()
        val collecting = launch { preparedAudioTriggers(preparation, revision, settled).toList(seen) }
        advanceUntilIdle()

        preparation.value = TtsChapterPreparationState.Running("c1.xhtml", 1, 9)
        advanceUntilIdle()
        revision.value += 1
        advanceUntilIdle()

        assertEquals(3, seen.size)
        collecting.cancel()
    }
}
