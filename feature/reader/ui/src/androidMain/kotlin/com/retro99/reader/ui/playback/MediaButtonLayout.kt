package com.retro99.reader.ui.playback

/**
 * Which buttons the media notification and the lock screen offer, decided without
 * touching Media3 or Android so it can be tested on the host.
 * [MediaPlaybackService.createMediaButtonPreferences] turns these into `CommandButton`s.
 *
 * Play and pause are not listed: Media3 adds them itself from the session's player
 * commands, which is exactly what was missing before (TTS-F31).
 */
internal enum class MediaButtonAction { SEEK_BACK_10, SEEK_FORWARD_10, PREVIOUS, NEXT }

/** Media3's button slots. `BACK` and `FORWARD` are the two beside play/pause. */
internal enum class MediaButtonSlot { BACK, FORWARD, OVERFLOW }

internal data class MediaButtonSpec(
    val action: MediaButtonAction,
    val slot: MediaButtonSlot,
    val displayName: String,
)

/**
 * Read-aloud moves by sentence, so previous and next take the slots beside play/pause,
 * where the notification and the lock screen show them; a ten second seek inside one
 * sentence is of no use there and is not offered. Recorded narration keeps the seeks it
 * has always had, with chapter navigation in the overflow.
 */
internal fun mediaButtonSpecs(isTts: Boolean): List<MediaButtonSpec> {
    val target = if (isTts) "sentence" else "chapter"
    val previous = MediaButtonSpec(
        action = MediaButtonAction.PREVIOUS,
        slot = if (isTts) MediaButtonSlot.BACK else MediaButtonSlot.OVERFLOW,
        displayName = "Previous $target",
    )
    val next = MediaButtonSpec(
        action = MediaButtonAction.NEXT,
        slot = if (isTts) MediaButtonSlot.FORWARD else MediaButtonSlot.OVERFLOW,
        displayName = "Next $target",
    )
    if (isTts) return listOf(previous, next)
    return listOf(
        MediaButtonSpec(
            action = MediaButtonAction.SEEK_FORWARD_10,
            slot = MediaButtonSlot.FORWARD,
            displayName = "Seek forward 10 seconds",
        ),
        MediaButtonSpec(
            action = MediaButtonAction.SEEK_BACK_10,
            slot = MediaButtonSlot.BACK,
            displayName = "Seek back 10 seconds",
        ),
        previous,
        next,
    )
}
