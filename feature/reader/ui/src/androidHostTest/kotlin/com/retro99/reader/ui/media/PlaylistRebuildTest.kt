package com.retro99.reader.ui.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the ExoPlayer playlist has to be built again. The cached hrefs and track indexes
 * belong to one player, and `MediaPlaybackService` creates a new player every time it is
 * created — which it is, for instance, when listening is handed from the device voice back
 * to recorded narration. Reusing a cache that describes the old player leaves the new one
 * with no media items at all, so narration sits at 0:00 and no press on Play recovers it.
 */
class PlaylistRebuildTest {

    private val chapter = listOf("Audio/00001-00001.mp4", "Audio/00001-00002.mp4")
    private val indexes = mapOf(chapter[0] to 0, chapter[1] to 1)

    @Test
    fun `the same files on the same player need no rebuild`() {
        assertFalse(
            playlistNeedsRebuild(
                cachedAudioHrefs = chapter,
                chapterAudioHrefs = chapter,
                cachedTrackIndexes = indexes,
                playlistBelongsToCurrentPlayer = true,
            ),
        )
    }

    @Test
    fun `a playlist built on a player that is gone needs a rebuild`() {
        assertTrue(
            playlistNeedsRebuild(
                cachedAudioHrefs = chapter,
                chapterAudioHrefs = chapter,
                cachedTrackIndexes = indexes,
                playlistBelongsToCurrentPlayer = false,
            ),
            "the cached playlist was built on another player and was reused anyway",
        )
    }

    @Test
    fun `different audio files need a rebuild`() {
        assertTrue(
            playlistNeedsRebuild(
                cachedAudioHrefs = listOf("Audio/00000-00001.mp4"),
                chapterAudioHrefs = chapter,
                cachedTrackIndexes = indexes,
                playlistBelongsToCurrentPlayer = true,
            ),
        )
    }

    @Test
    fun `no track indexes mean nothing was ever built`() {
        assertTrue(
            playlistNeedsRebuild(
                cachedAudioHrefs = chapter,
                chapterAudioHrefs = chapter,
                cachedTrackIndexes = emptyMap(),
                playlistBelongsToCurrentPlayer = true,
            ),
        )
    }
}
