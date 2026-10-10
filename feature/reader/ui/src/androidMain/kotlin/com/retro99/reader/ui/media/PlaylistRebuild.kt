package com.retro99.reader.ui.media

/**
 * Whether ExoPlayer's playlist has to be built again for a chapter's audio files,
 * decided without touching Media3 or Android so it can be tested on the host.
 * [MediaOverlayPlayer] caches the hrefs and track indexes of the playlist it last
 * built; `preparePlaylist` is what builds one.
 *
 * The cache describes one particular player. `MediaPlaybackService` owns the player
 * and creates a new one every time it is created, so a cache that outlives the
 * service describes a player that no longer exists.
 */
internal fun <T> playlistNeedsRebuild(
    cachedAudioHrefs: List<T>,
    chapterAudioHrefs: List<T>,
    cachedTrackIndexes: Map<T, Int>,
    @Suppress("UNUSED_PARAMETER") playlistBelongsToCurrentPlayer: Boolean,
): Boolean = cachedAudioHrefs != chapterAudioHrefs || cachedTrackIndexes.isEmpty()
