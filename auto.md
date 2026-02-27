# Android Auto Implementation Guide for StoryTellerKMP

This document provides a comprehensive guide for implementing Android Auto support in the StoryTellerKMP app, allowing users to browse and play read-aloud books from their car's infotainment system.

## Overview

Android Auto integration for media apps requires:
1. **MediaLibraryService** - Extends `MediaSessionService` to support browsable content
2. **MediaLibrarySession.Callback** - Handles content browsing requests
3. **Content hierarchy** - Organized tree structure of media items
4. **Manifest declarations** - Declares app as Android Auto compatible
5. **Headless Playback** - Direct audio playback without opening the phone app

### Key Design Goal: Hands-Free Experience

The primary goal is to enable **true hands-free** audio playback from Android Auto:
- User browses and selects a book from the car's screen
- Playback starts immediately in the car
- No phone interaction required while driving
- Position is saved and synced for later continuation on phone

## Existing Architecture Analysis

The app already has a sophisticated media playback architecture:

### Current Components

| Component | Purpose |
|-----------|---------|
| `MediaPlaybackService` | Foreground service extending `MediaSessionService` |
| `MediaPlaybackController` | Koin singleton bridging service and other components |
| `MediaSessionManager` | Creates `MediaSession`, handles metadata and callbacks |
| `PlayerSessionPair` | Holds player + session together atomically |

### Key Design Patterns

1. **Thread Safety**: `MediaPlaybackController` uses `synchronized(lock)` blocks for atomic operations
2. **Lazy Session Registration**: Sessions can be registered before or after service starts
3. **Session Lifecycle**: Service calls `addSession()` when session is registered
4. **Existing Callbacks**: `MediaSessionManager` already has `MediaSession.Callback` for media buttons

### Important Finding: Session Management

The existing architecture separates concerns:
- `MediaSessionManager` creates the session with a `MediaSession.Callback`
- `MediaPlaybackController` manages registration with the service
- `MediaPlaybackService` just calls `addSession()`

**For Android Auto, we need to:**
1. Change `MediaSessionService` → `MediaLibraryService`
2. Keep `MediaSession` from `MediaSessionManager` (it still works with `MediaLibraryService`)
3. Add a separate browsing-only `MediaLibrarySession` in the service for Android Auto
4. The browsing session handles `onGetLibraryRoot`, `onGetChildren`, etc.
5. The playback session (from `MediaSessionManager`) handles actual playback

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android Auto                             │
│  (Requests content via MediaBrowser protocol)                   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    MediaPlaybackService                          │
│  (Extends MediaLibraryService)                                  │
├─────────────────────────────────────────────────────────────────┤
│  browsingSession (MediaLibrarySession) ──► BooksMediaProvider   │
│                                                                  │
│  activePlaybackSession: HeadlessPlaybackSession?                │
│      ├── EpubPublication                                        │
│      ├── ExoPlayer                                              │
│      ├── MediaOverlayPlayer (reuse existing)                    │
│      ├── SmilLoadingManager (reuse existing)                    │
│      └── Position persistence (via SaveReadingProgressUseCase)  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     BooksMediaProvider                           │
│  • Fetches read-aloud books via GetReadaloudBooksUseCase        │
│  • Converts BookDomainModel → MediaItem                         │
│  • Downloads cover images with auth tokens                      │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Content Hierarchy                            │
│                                                                  │
│  ROOT (not displayed)                                            │
│    └── "Read-Aloud Books" (BROWSABLE)                           │
│          ├── Book 1 (PLAYABLE) → Starts headless playback       │
│          ├── Book 2 (PLAYABLE)                                  │
│          └── Book N (PLAYABLE)                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Headless Playback Flow

When a user selects a book in Android Auto:

```
User taps book in Android Auto
    │
    ▼
onSetMediaItems(bookUuid)
    │
    ▼
HeadlessSessionFactory.createSession()
    ├── Load saved position from ServerReaderRepository
    ├── Open EpubPublication
    ├── Create MediaOverlayPlayer + SmilLoadingManager
    └── Initialize at saved chapter/position
    │
    ▼
HeadlessPlaybackSession.play()
    │
    ▼
Audio plays from car speakers ✓
(No phone interaction needed)
```

## Implementation Steps

### Step 1: Create `automotive_app_desc.xml`

**File:** `androidApp/src/main/res/xml/automotive_app_desc.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

This XML file declares your app as a media app for Android Auto.

---

### Step 2: Update `AndroidManifest.xml`

Add Android Auto metadata to your existing service declaration:

```xml
<service
    android:name="com.retro99.reader.ui.playback.MediaPlaybackService"
    android:foregroundServiceType="mediaPlayback"
    android:exported="true">
    <intent-filter>
        <!-- Media3 MediaLibraryService action (primary) -->
        <action android:name="androidx.media3.session.MediaLibraryService"/>
        <!-- Legacy MediaBrowserService for compatibility -->
        <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
</service>

<!-- Android Auto app declaration -->
<meta-data
    android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc"/>
```

**Important Notes:**
- The `android.media.browse.MediaBrowserService` action is required for compatibility with older Android Auto clients
- The meta-data must be inside `<application>` but outside the `<service>` tag

---

### Step 3: Create `BooksMediaProvider`

This class handles fetching and converting books to MediaItems.

#### Create `GetReadaloudBooksUseCase`

Create a dedicated use case for fetching read-aloud books in the books domain module:

**File:** `feature/books/domain/src/commonMain/kotlin/com/retro99/books/domain/usecase/GetReadaloudBooksUseCase.kt`

```kotlin
package com.retro99.books.domain.usecase

import com.retro99.base.result.AppResult
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Use case for getting all read-aloud books from all authenticated servers.
 * Filters books that have read-aloud capability.
 */
@Factory
class GetReadaloudBooksUseCase(
    @Provided private val getBooksUseCase: GetBooksUseCase,
) {
    operator fun invoke(): Flow<AppResult<List<BookDomainModel>>> {
        return getBooksUseCase().map { result ->
            result.map { books ->
                books.filter { book ->
                    when (book) {
                        is BookDomainModel.StorytellerBook -> book.readaloud != null
                        is BookDomainModel.LocalBook -> book.bookType == BookType.READALOUD
                    }
                }
            }
        }
    }
}
```

Benefits:
- Cleaner separation of concerns
- Reusable across other features that need read-aloud books
- Better testability
- `BooksMediaProvider` stays simple without filtering logic

---

**File:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/playback/auto/BooksMediaProvider.kt`

```kotlin
package com.retro99.reader.ui.playback.auto

import android.net.Uri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.retro99.books.domain.model.BookDomainModel
import com.retro99.books.domain.model.BookType
import com.retro99.books.domain.usecase.GetReadaloudBooksUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Provided

/**
 * Provides media items for Android Auto browsing.
 * Fetches read-aloud books and converts them to MediaItems.
 */
@Factory
class BooksMediaProvider(
    @Provided private val getReadaloudBooksUseCase: GetReadaloudBooksUseCase,
) {
    companion object {
        // Media IDs for content hierarchy
        const val ROOT_ID = "root"
        const val READALOUD_BOOKS_ID = "readaloud_books"

        // Extra keys for MediaItem bundles
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_BOOK_UUID = "book_uuid"
        const val EXTRA_BOOK_TYPE = "book_type"
    }

    /**
     * Returns the root browsable items (categories).
     */
    fun getRootItems(): List<MediaItem> {
        return listOf(
            MediaItem.Builder()
                .setMediaId(READALOUD_BOOKS_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Read-Aloud Books")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                        .build()
                )
                .build()
        )
    }

    /**
     * Fetches all read-aloud books and converts them to MediaItems.
     */
    suspend fun getReadaloudBooks(): List<MediaItem> {
        val booksResult = getReadaloudBooksUseCase().first()

        return booksResult.fold(
            success = { books -> books.map { book -> bookToMediaItem(book) } },
            failure = { emptyList() }
        )
    }

    private fun bookToMediaItem(book: BookDomainModel): MediaItem {
        val authors = when (book) {
            is BookDomainModel.StorytellerBook ->
                book.authors.joinToString(", ") { it.name }
            is BookDomainModel.LocalBook ->
                book.author ?: "Unknown Author"
        }

        return MediaItem.Builder()
            .setMediaId("book:${book.serverId}:${book.uuid}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.title)
                    .setArtist(authors)
                    .setArtworkUri(book.coverUrl?.let { Uri.parse(it) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setExtras(bundleOf(
                        EXTRA_SERVER_ID to book.serverId,
                        EXTRA_BOOK_UUID to book.uuid,
                        EXTRA_BOOK_TYPE to BookType.READALOUD.value
                    ))
                    .build()
            )
            .build()
    }
}
```

---

### Step 4: Convert MediaPlaybackService to MediaLibraryService

The key change is converting `MediaSessionService` to `MediaLibraryService` and implementing the browsing callbacks.

**Important Design Decision:**

The existing architecture uses `MediaSessionManager` to create the `MediaSession` with playback callbacks (media buttons, seek, etc.). We need to:

1. Change `MediaPlaybackService` to extend `MediaLibraryService` instead of `MediaSessionService`
2. Create a browsing-only `MediaLibrarySession` with a stub player for Android Auto content browsing
3. Keep the existing `MediaSession` from `MediaSessionManager` for actual playback
4. Both sessions can coexist - `MediaLibraryService` supports multiple sessions via `addSession()`

**Critical Issue: Browsing Session Requires a Player**

`MediaLibrarySession` requires a `Player` at construction time. However, Android Auto users want to browse content **before** starting playback (i.e., before `MediaSessionManager` creates a player).

**Solution**: Create a minimal "stub" ExoPlayer just for the browsing session. This player:
- Is lightweight and doesn't consume audio resources
- Allows `MediaLibrarySession` to be created immediately in `onCreate()`
- Is separate from the playback ExoPlayer managed by `MediaSessionManager`

The browsing session's player is never used for actual playback - it just satisfies the API requirement.

**File:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/playback/MediaPlaybackService.kt`

```kotlin
package com.retro99.reader.ui.playback

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.retro99.reader.ui.playback.auto.BooksMediaProvider
import com.retro99.reader.ui.playback.auto.HeadlessPlaybackSession
import com.retro99.reader.ui.playback.auto.HeadlessSessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground service for audio playback with MediaSession and Android Auto support.
 *
 * This service:
 * - Runs as a foreground service with a persistent notification
 * - Manages a MediaSession for system integration (lockscreen, Bluetooth, etc.)
 * - Provides content browsing for Android Auto via MediaLibrarySession
 * - Handles audio focus and system audio policy
 *
 * ## Architecture Notes
 *
 * The service manages TWO types of sessions:
 * 1. **Playback session** (from MediaSessionManager): Standard MediaSession for actual audio playback
 * 2. **Browsing session**: MediaLibrarySession for Android Auto content browsing (uses stub player)
 *
 * Both sessions are registered via addSession() and can coexist.
 *
 * The browsing session uses a lightweight "stub" ExoPlayer that is never used for actual
 * playback. This is necessary because MediaLibrarySession requires a Player at construction
 * time, but Android Auto users need to browse content before any playback has started.
 */
class MediaPlaybackService : MediaLibraryService() {

    private val controller: MediaPlaybackController by inject()
    private val booksProvider: BooksMediaProvider by inject()
    private val sessionFactory: HeadlessSessionFactory by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Stub player used only for the browsing session.
     * This is a lightweight ExoPlayer that satisfies the MediaLibrarySession API
     * but is never used for actual audio playback.
     */
    private var stubPlayer: ExoPlayer? = null

    /**
     * Browsing-only session for Android Auto content hierarchy.
     * This is separate from the playback session managed by MediaSessionManager.
     */
    private var browsingSession: MediaLibrarySession? = null

    /**
     * Active headless playback session for Android Auto.
     * Created when user selects a book, manages audio playback without UI.
     */
    private var activePlaybackSession: HeadlessPlaybackSession? = null

    private val taskRemovedTimeoutMs = 30 * 60 * 1000L // 30 minutes

    private val stopSelfRunnable = Runnable {
        val player = controller.currentPlayer
        if (player == null || !player.isPlaying) {
            stopSelf()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                handler.removeCallbacks(stopSelfRunnable)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller.onServiceCreated(this)

        // Create browsing session immediately for Android Auto
        // Uses a stub player since actual playback player may not exist yet
        createBrowsingSession()
    }

    /**
     * Creates the browsing session for Android Auto content hierarchy.
     * Uses a lightweight stub player since this session is only for browsing, not playback.
     */
    private fun createBrowsingSession() {
        if (browsingSession != null) return

        // Create a minimal ExoPlayer just to satisfy MediaLibrarySession API
        stubPlayer = ExoPlayer.Builder(this).build()

        browsingSession = MediaLibrarySession.Builder(this, stubPlayer!!, LibrarySessionCallback())
            .build()
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        // Return the browsing session for Android Auto
        // The playback session is added separately via addSession() from MediaPlaybackController
        return browsingSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = controller.currentPlayer
        if (player == null || player.mediaItemCount == 0) {
            stopSelf()
            return
        }

        if (!player.isPlaying) {
            player.addListener(playerListener)
            handler.postDelayed(stopSelfRunnable, taskRemovedTimeoutMs)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopSelfRunnable)
        controller.currentPlayer?.removeListener(playerListener)
        activePlaybackSession?.close()
        browsingSession?.release()
        stubPlayer?.release()
        serviceScope.cancel()
        controller.onServiceDestroyed()
        super.onDestroy()
    }

    /**
     * Callback for handling Android Auto content browsing requests.
     */
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootItem = MediaItem.Builder()
                .setMediaId(BooksMediaProvider.ROOT_ID)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return serviceScope.future {
                val items = when (parentId) {
                    BooksMediaProvider.ROOT_ID -> booksProvider.getRootItems()
                    BooksMediaProvider.READALOUD_BOOKS_ID -> booksProvider.getReadaloudBooks()
                    else -> emptyList()
                }
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            return serviceScope.future {
                if (mediaId.startsWith("book:")) {
                    val books = booksProvider.getReadaloudBooks()
                    val item = books.find { it.mediaId == mediaId }
                    if (item != null) {
                        LibraryResult.ofItem(item, null)
                    } else {
                        LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                    }
                } else {
                    LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                }
            }
        }

        /**
         * Handle book selection from Android Auto.
         * Starts headless playback directly without opening the phone app.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val selectedItem = mediaItems.getOrNull(startIndex)
            val extras = selectedItem?.mediaMetadata?.extras

            if (extras != null) {
                val serverId = extras.getString(BooksMediaProvider.EXTRA_SERVER_ID)
                val bookUuid = extras.getString(BooksMediaProvider.EXTRA_BOOK_UUID)

                if (serverId != null && bookUuid != null) {
                    serviceScope.launch {
                        // Close existing session if different book
                        if (activePlaybackSession?.bookUuid != bookUuid) {
                            activePlaybackSession?.close()
                        }

                        // Create and start headless playback session
                        val session = sessionFactory.createSession(serverId, bookUuid)
                        activePlaybackSession = session
                        session?.play()
                    }
                }
            }

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }
    }
}
```

---

### Step 5: No Changes to MediaPlaybackController

**Good news!** The existing `MediaPlaybackController` does NOT need changes.

Since we use a stub player for the browsing session (created in `onCreate()`), there's no need to notify the service when the playback player is registered. The browsing session is independent of the playback session.

---

### Step 6: No Changes to MediaSessionManager

**Good news!** The existing `MediaSessionManager` does NOT need changes.

**Why?**
- `MediaSessionManager` creates a standard `MediaSession` for playback control
- This session is added to `MediaLibraryService` via `addSession()`
- `MediaLibraryService` supports multiple sessions
- The browsing session (`MediaLibrarySession`) handles Android Auto content hierarchy
- The playback session (`MediaSession`) handles actual playback, notifications, media buttons

Both sessions coexist and serve different purposes. The existing `MediaSession.Callback` continues to handle media button events, seek operations, and playback state.

---

### Step 7: Create HeadlessPlaybackSession

This class encapsulates the headless audio playback state for Android Auto. It manages the audio player, chapter navigation, and position persistence without requiring any UI components.

**File:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/playback/auto/HeadlessPlaybackSession.kt`

```kotlin
package com.retro99.reader.ui.playback.auto

import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.ui.media.MediaOverlayPlayer
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.readium.EpubPublication
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Manages headless audio playback for Android Auto.
 *
 * This session operates without any UI - no text highlighting, no page turns.
 * It only handles:
 * - Audio playback via MediaOverlayPlayer
 * - Chapter navigation (next/previous)
 * - Position persistence (periodic saving, on pause, on close)
 * - Auto-advance on chapter completion
 */
class HeadlessPlaybackSession(
    val serverId: String,
    val bookUuid: String,
    private val publication: EpubPublication,
    private val player: MediaOverlayPlayer,
    private val smilLoadingManager: SmilLoadingManager,
    private val saveProgressUseCase: SaveReadingProgressUseCase,
    private val initialChapterHref: String?,
    private val initialPositionMs: Long?,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentChapterHref: String? = initialChapterHref

    private val readingOrder: List<String> =
        publication.publication.readingOrder.map { it.href.toString() }

    /**
     * Starts playback from saved position or beginning.
     */
    fun play() {
        val href = currentChapterHref ?: readingOrder.firstOrNull() ?: return
        player.play(
            chapterHref = Url(href),
            initialPositionMs = initialPositionMs,
        )
    }

    /**
     * Plays a specific chapter from the beginning.
     */
    fun playChapter(chapterHref: String) {
        currentChapterHref = chapterHref
        player.play(chapterHref = Url(chapterHref))
    }

    fun pause() = player.pause()
    fun resume() = player.resume()
    fun seekForward() = player.seekForward()
    fun seekBackward() = player.seekBackward()

    fun skipToNextChapter() {
        val next = findNextChapterWithAudio() ?: return
        currentChapterHref = next
        player.play(Url(next))
    }

    fun skipToPreviousChapter() {
        val prev = findPreviousChapterWithAudio() ?: return
        currentChapterHref = prev
        player.play(Url(prev))
    }

    private fun findNextChapterWithAudio(): String? {
        val currentHref = currentChapterHref ?: return null
        return smilLoadingManager.findNextChapterWithAudio(currentHref)
    }

    private fun findPreviousChapterWithAudio(): String? {
        val currentIndex = readingOrder.indexOf(currentChapterHref)
        if (currentIndex <= 0) return null

        // Walk backwards to find chapter with audio
        return readingOrder.take(currentIndex).reversed().firstOrNull { href ->
            smilLoadingManager.hasAudioForChapter(href)
        }
    }

    init {
        // Auto-advance on chapter completion
        player.chapterAudioCompleted
            .onEach { skipToNextChapter() }
            .launchIn(scope)

        // Save position periodically (every 30 seconds while playing)
        player.audioPlaybackState
            .filter { it.isPlaying }
            .sample(30.seconds)
            .onEach { state ->
                saveCurrentPosition(state.currentPositionMs)
            }
            .launchIn(scope)

        // Save position on pause
        player.audioPlaybackState
            .map { it.isPlaying }
            .distinctUntilChanged()
            .filter { !it } // Just paused
            .onEach {
                val position = player.audioPlaybackState.first().currentPositionMs
                saveCurrentPosition(position)
            }
            .launchIn(scope)
    }

    private suspend fun saveCurrentPosition(audioPositionMs: Long) {
        val chapterHref = currentChapterHref ?: return
        saveProgressUseCase(
            bookUuid = bookUuid,
            serverId = serverId,
            locatorHref = chapterHref,
            audioTimestampMs = audioPositionMs,
        )
    }

    override fun close() {
        // Save final position before closing
        scope.launch {
            val position = player.audioPlaybackState.first().currentPositionMs
            saveCurrentPosition(position)
        }
        scope.cancel()
        player.release()
    }
}
```

---

### Step 8: Create HeadlessSessionFactory

This factory creates `HeadlessPlaybackSession` instances from the service context. It handles opening the publication, loading saved position, and creating all necessary audio components.

**File:** `feature/reader/ui/src/androidMain/kotlin/com/retro99/reader/ui/playback/auto/HeadlessSessionFactory.kt`

```kotlin
package com.retro99.reader.ui.playback.auto

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.analytics.api.Analytics
import com.retro99.books.domain.model.BookType
import com.retro99.reader.domain.usecase.SaveReadingProgressUseCase
import com.retro99.reader.ui.media.MediaOverlayPlayer
import com.retro99.reader.ui.media.smil.PublicationSmilContentProvider
import com.retro99.reader.ui.media.smil.SmilLoadingManager
import com.retro99.readium.EpubPublicationService
import com.retro99.server.api.AuthenticatedRepositoryProvider
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

/**
 * Factory for creating HeadlessPlaybackSession instances.
 *
 * Handles all the setup required to start headless playback:
 * 1. Load book info and saved position
 * 2. Open the EPUB publication
 * 3. Create audio playback components (ExoPlayer, MediaOverlayPlayer, SmilLoadingManager)
 * 4. Initialize at the saved chapter/position
 */
@Single
class HeadlessSessionFactory(
    private val context: Context,
    private val publicationService: EpubPublicationService,
    private val repositoryProvider: AuthenticatedRepositoryProvider,
    private val saveReadingProgressUseCase: SaveReadingProgressUseCase,
    private val analytics: Analytics,
) {
    suspend fun createSession(
        serverId: String,
        bookUuid: String,
    ): HeadlessPlaybackSession? {
        // 1. Get book info to find ebook path
        val booksRepo = repositoryProvider.getBooksRepository(serverId) ?: return null
        val book = booksRepo.getBook(bookUuid).first().getOrNull() ?: return null

        // 2. Get saved reading position
        val readerRepo = repositoryProvider.getReaderRepository(serverId)
        val savedPosition = readerRepo?.getPosition(bookUuid)?.getOrNull()

        // 3. Open publication
        val publication = publicationService.openPublication(
            filePath = book.ebookPath ?: return null,
            serverId = serverId,
            bookUuid = bookUuid,
            bookType = BookType.READALOUD,
        ).getOrNull() ?: return null

        // 4. Create ExoPlayer for audio playback
        val exoPlayer = ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 5. Create SMIL loading components
        val smilContentProvider = PublicationSmilContentProvider(publication, analytics)
        val smilLoadingManager = SmilLoadingManager(smilContentProvider, /* dispatcher */)

        // 6. Create MediaOverlayPlayer
        val player = MediaOverlayPlayer(
            epubPublication = publication,
            analytics = analytics,
            smilLoadingManager = smilLoadingManager,
            exoPlayer = exoPlayer,
            // ... other dependencies
        )

        // 7. Initialize at saved chapter (or first chapter with audio)
        val initialChapterHref = savedPosition?.locatorHref
            ?: publication.publication.readingOrder.firstOrNull()?.href?.toString()

        player.initialize(initialChapterHref)

        // 8. Create session with position info
        return HeadlessPlaybackSession(
            serverId = serverId,
            bookUuid = bookUuid,
            publication = publication,
            player = player,
            smilLoadingManager = smilLoadingManager,
            saveProgressUseCase = saveReadingProgressUseCase,
            initialChapterHref = initialChapterHref,
            initialPositionMs = savedPosition?.audioTimestampMs,
        )
    }
}
```

---

## Position Restoration Flow

When a user selects a book in Android Auto, their reading position is automatically restored:

```
HeadlessSessionFactory.createSession(serverId, bookUuid)
    │
    ├── repositoryProvider.getReaderRepository(serverId)
    │       └── readerRepo.getPosition(bookUuid)
    │               └── Returns ServerPosition {
    │                       locatorHref: "chapter5.xhtml"
    │                       audioTimestampMs: 45000  (45 seconds in)
    │                   }
    │
    ├── publicationService.openPublication()
    │
    ├── player.initialize(chapterHref = "chapter5.xhtml")
    │
    └── HeadlessPlaybackSession(initialPositionMs = 45000)
            │
            ▼
        session.play()
            │
            ▼
        player.play(chapterHref = "chapter5.xhtml", initialPositionMs = 45000)
            │
            ▼
        User hears audio from where they left off ✓
```

## Position Persistence

Position is saved at multiple points to ensure no progress is lost:

| Trigger | When | Purpose |
|---------|------|---------|
| **Periodic** | Every 30 seconds while playing | Regular checkpoint |
| **On Pause** | When playback pauses | Capture pause position |
| **On Close** | When session closes | Final position save |
| **On Chapter Change** | When advancing to next chapter | Mark chapter completion |

---

## Session Handoff (Phase 2)

When a user stops Android Auto and wants to continue on their phone:

### Scenario: Stop & Continue

```
Android Auto playing at 2:35
    │
    ▼
User disconnects Android Auto
    │
    ▼
HeadlessPlaybackSession.close()
    ├── Saves position (2:35) to server
    └── Releases player, stops audio
    │
    ▼
Later, user opens phone app
    │
    ▼
ReaderViewModel loads saved position (2:35)
    │
    ▼
User presses play → continues from 2:35 ✓
```

This works automatically because position is saved via `SaveReadingProgressUseCase`.

### Future Enhancement: Seamless Handoff

A more advanced implementation could keep audio playing when Android Auto disconnects, allowing the phone app to "attach" to the ongoing session. This would require:

1. `PlaybackSessionManager` singleton to hold the active session
2. `ReaderViewModel` checks for active session before creating new one
3. Session transfer mechanism to hand off player ownership

This is optional and can be implemented in Phase 2 after basic headless playback is working.

---

## Dependencies

Ensure you have the required Media3 dependencies in your build.gradle:

```kotlin
// Already present in your project
implementation("androidx.media3:media3-session:1.x.x")
implementation("androidx.media3:media3-exoplayer:1.x.x")

// Add for ListenableFuture coroutine support
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3")
```

---

## Content Hierarchy Rules

Android Auto has specific requirements for content hierarchy:

| Rule | Description |
|------|-------------|
| **Max 4 root tabs** | The root level should have ≤4 browsable items |
| **Max 4 levels deep** | Content tree should not exceed 4 levels |
| **Browsable vs Playable** | Use `FLAG_BROWSABLE` for folders, `FLAG_PLAYABLE` for items |
| **Media types** | Use appropriate `MediaMetadata.MEDIA_TYPE_*` constants |
| **Artwork** | Provide `artworkUri` for cover images |

---

## Testing

### 1. Install Desktop Head Unit (DHU)

```bash
# Install via Android Studio SDK Manager:
# SDK Manager → SDK Tools → Android Auto Desktop Head Unit Emulator

# Run DHU:
cd $ANDROID_HOME/extras/google/auto
./desktop-head-unit
```

### 2. Enable Developer Mode

1. Open Android Auto app on your phone
2. Tap version number 10 times to enable developer mode
3. Go to Settings → Developer settings
4. Enable "Unknown sources"

### 3. Connect and Test

1. Connect phone via USB
2. Run DHU
3. Your app should appear in the media apps list
4. Test browsing and playback

### 4. Common Issues

| Issue | Solution |
|-------|----------|
| App not appearing | Check manifest declarations and service export |
| Empty content | Verify `onGetChildren` returns items |
| Playback not working | Ensure MediaSession is properly registered |
| Crashes on browse | Check for null safety in async callbacks |

---

## Key Differences from Current Implementation

| Current (Reader) | Android Auto Headless |
|------------------|----------------------|
| `MediaSessionService` | `MediaLibraryService` |
| `MediaSession` only | `MediaSession` + `MediaLibrarySession` (both coexist) |
| No content browsing | `onGetChildren`, `onGetLibraryRoot` via `LibrarySessionCallback` |
| Playback via `ReaderViewModel` | Playback via `HeadlessPlaybackSession` |
| Text highlighting + audio sync | Audio only (no UI) |
| Uses `ReaderScope` Koin scope | Service-level components |
| `ReaderSyncCoordinator` | Not needed (no UI to sync) |

---

## Files to Create/Modify

| File | Action | ~Lines |
|------|--------|--------|
| `androidApp/src/main/res/xml/automotive_app_desc.xml` | CREATE - Android Auto capability declaration | 4 |
| `androidApp/src/main/AndroidManifest.xml` | MODIFY - Add meta-data and update intent-filter | +10 |
| `feature/books/domain/.../usecase/GetReadaloudBooksUseCase.kt` | CREATE - Use case for fetching read-aloud books | ~30 |
| `feature/reader/ui/.../auto/BooksMediaProvider.kt` | EXISTS - Provides MediaItems for browsing | ~180 |
| `feature/reader/ui/.../auto/HeadlessPlaybackSession.kt` | **CREATE** - Headless playback state management | ~100 |
| `feature/reader/ui/.../auto/HeadlessSessionFactory.kt` | **CREATE** - Factory to create sessions | ~80 |
| `feature/reader/ui/.../MediaPlaybackService.kt` | MODIFY - Add headless playback support | +50 |
| `feature/reader/ui/.../MediaPlaybackController.kt` | NO CHANGE - Existing logic works as-is | - |
| `feature/reader/ui/.../MediaSessionManager.kt` | NO CHANGE - Existing MediaSession works as-is | - |
| `gradle/libs.versions.toml` | MODIFY - Add coroutines-guava dependency | +1 |
| `feature/reader/ui/build.gradle.kts` | MODIFY - Add coroutines-guava dependency | +1 |

### Components Reused (No Changes Needed)

| Component | Lines | Purpose |
|-----------|-------|---------|
| `MediaOverlayPlayer` | ~900 | Core audio playback with SMIL parsing |
| `SmilLoadingManager` | ~400 | SMIL content loading and chapter detection |
| `SmilParser`, `SmilQuickScanner` | ~200 | SMIL file parsing |
| `PlaybackStateTracker` | ~150 | ExoPlayer state tracking |
| `LocatorTracker` | ~200 | Clip-to-position mapping |
| `SaveReadingProgressUseCase` | ~50 | Position persistence |

### Estimated Effort

| Task | Hours |
|------|-------|
| Create `HeadlessPlaybackSession` | 4 |
| Create `HeadlessSessionFactory` | 4 |
| Modify `MediaPlaybackService` | 3 |
| Wire media button callbacks | 2 |
| Position persistence | 2 |
| Testing with Android Auto emulator | 6 |
| Edge cases & polish | 4 |
| **Total** | **~25 hours** |

---

## Important Considerations

### 1. Authentication State
If the user is not logged in, return an appropriate error message or empty state in `onGetChildren`.

### 2. Loading States
Android Auto expects quick responses. Use caching for book lists when possible.

### 3. Error Handling
Always handle network errors gracefully - return empty lists rather than throwing exceptions.

### 4. Cover Art
- Use HTTPS URLs for cover art
- Android Auto caches images
- Provide reasonable resolution (300x300 recommended)

### 5. Voice Search (Optional)
Implement `onSearch` callback for voice commands:

```kotlin
override fun onSearch(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: LibraryParams?
): ListenableFuture<LibraryResult<Unit>> {
    // Trigger search and prepare results
    return Futures.immediateFuture(LibraryResult.ofVoid(params))
}

override fun onGetSearchResult(
    session: MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: LibraryParams?
): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return serviceScope.future {
        val books = booksProvider.getReadaloudBooks()
        val filtered = books.filter {
            it.mediaMetadata.title?.toString()?.contains(query, ignoreCase = true) == true
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(filtered), params)
    }
}
```

---

## Architecture Decisions

### Why Headless Playback Instead of Deep Links?

The original implementation used deep links to open the reader app when a book was selected in Android Auto. While simple, this **defeats the hands-free purpose** of Android Auto:

| Deep Link Approach | Headless Playback |
|--------------------|-------------------|
| ❌ Opens phone app | ✅ Phone stays in pocket |
| ❌ Requires phone interaction | ✅ True hands-free experience |
| ❌ User must look at phone | ✅ Eyes stay on the road |
| ✅ Full text-audio sync | ❌ Audio only (acceptable tradeoff) |

### Why Not Use ReaderSyncCoordinator?

`ReaderSyncCoordinator` synchronizes between book navigation (text) and audio playback:
- Book page turn → audio chapter preparation
- Audio position → text highlighting
- Double-tap on sentence → start playback

**For Android Auto, none of this is needed** because there's no UI to synchronize with. The headless architecture is simpler:

```
HeadlessPlaybackSession
    ├── MediaOverlayPlayer (handles audio)
    ├── SmilLoadingManager (knows chapter structure)
    └── Position persistence (saves progress)
```

### Avoiding Dependency Injection Conflicts

The reader uses `ReaderScope` for scoped dependencies. The headless playback architecture **avoids conflicts** by:

1. Using `@Factory` and `@Single` annotations for Android Auto components
2. Creating audio components directly in `HeadlessSessionFactory` (not via Koin scopes)
3. Reusing stateless components (`SmilParser`, etc.) without scope issues

### Position Synchronization

Both the reader and Android Auto use the same position persistence:

```kotlin
// Both use:
SaveReadingProgressUseCase(
    bookUuid = bookUuid,
    serverId = serverId,
    locatorHref = chapterHref,
    audioTimestampMs = positionMs,
)
```

This ensures:
- Position saved in Android Auto is loaded when opening the reader
- Position saved in the reader is loaded when starting Android Auto
- No special sync logic required

---

## References

- [Android Auto Media Guide](https://developer.android.com/training/cars/media/auto)
- [Media3 MediaLibraryService](https://developer.android.com/media/media3/session/serve-content)
- [UAMP Sample (Media3 branch)](https://github.com/android/uamp/tree/media3)
- [Android Auto Design Guidelines](https://developers.google.com/cars/design/android-auto)

