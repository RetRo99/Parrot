package com.retro99.reader.ui.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the media notification and the lock screen offer. Read-aloud moves by sentence,
 * so its previous and next must sit in the two slots beside play/pause rather than in the
 * overflow, where the owner asked for them (TTS-F31). Recorded narration keeps the ten
 * second seeks it has always had.
 */
class MediaButtonLayoutTest {

    @Test
    fun `read aloud offers previous and next sentence beside play and pause`() {
        val specs = mediaButtonSpecs(isTts = true)

        assertEquals(
            listOf(
                MediaButtonSpec(MediaButtonAction.PREVIOUS, MediaButtonSlot.BACK, "Previous sentence"),
                MediaButtonSpec(MediaButtonAction.NEXT, MediaButtonSlot.FORWARD, "Next sentence"),
            ),
            specs,
        )
    }

    @Test
    fun `read aloud offers no ten second seek inside one sentence`() {
        val specs = mediaButtonSpecs(isTts = true)

        assertTrue(
            specs.none { spec ->
                spec.action == MediaButtonAction.SEEK_BACK_10 ||
                    spec.action == MediaButtonAction.SEEK_FORWARD_10
            },
            "read-aloud offered a ten second seek: $specs",
        )
    }

    @Test
    fun `recorded narration keeps its seeks and its chapter buttons in the overflow`() {
        val specs = mediaButtonSpecs(isTts = false)

        assertEquals(
            listOf(
                MediaButtonSpec(MediaButtonAction.SEEK_FORWARD_10, MediaButtonSlot.FORWARD, "Seek forward 10 seconds"),
                MediaButtonSpec(MediaButtonAction.SEEK_BACK_10, MediaButtonSlot.BACK, "Seek back 10 seconds"),
                MediaButtonSpec(MediaButtonAction.PREVIOUS, MediaButtonSlot.OVERFLOW, "Previous chapter"),
                MediaButtonSpec(MediaButtonAction.NEXT, MediaButtonSlot.OVERFLOW, "Next chapter"),
            ),
            specs,
        )
    }
}
