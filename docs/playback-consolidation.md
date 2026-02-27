# Playback Architecture Consolidation

This document outlines the plan to consolidate from a two-service/two-session architecture to a single-service architecture for audio playback.

## Current Architecture Analysis

### The Problem: Two Sessions, Multiple Players

The current architecture has **three key components** creating complexity:

```
MediaPlaybackService (MediaLibraryService)
├── browsingSession (MediaLibrarySession) ← stubPlayer (never plays audio!)
│   └── Used for Android Auto content browsing
│
├── activePlaybackSession (HeadlessPlaybackSession)
│   └── Uses DIFFERENT ExoPlayer for Android Auto playback
│
└── MediaSessionManager (@Scoped to ReaderScope)
    └── Creates ANOTHER MediaSession for in-app playback
        └── Uses ANOTHER ExoPlayer from ReaderScope
```

**Issues:**
1. `browsingSession.player` is a stub that never plays audio
2. Android Auto observes `browsingSession.player` but actual audio plays on different ExoPlayer
3. Result: Android Auto can't show playback progress, duration, or controls properly
4. Two separate ExoPlayers for in-app vs Android Auto playback

### Koin Scope Analysis

**Current Scoping:**

| Component | Scope | Issue |
|-----------|-------|-------|
| `MediaPlaybackService` | Android Service | Creates stubPlayer in onCreate() |
| `MediaPlaybackController` | `@Single` | Bridges service ↔ components |
| `MediaSessionManager` | `@Scoped(ReaderScope)` | Creates its OWN MediaSession |
| `ExoPlayer` (in-app) | `@Scoped(ReaderScope)` | Created in PlatformReaderModule |
| `MediaOverlayPlayer` | `@Scoped(ReaderScope)` | Uses ReaderScope's ExoPlayer |
| `HeadlessSessionFactory` | `@Factory` | Creates HeadlessPlaybackSession |

**ReaderScope Lifecycle:**
- Created when user opens a book (`ReaderViewModel.readerScope`)
- Closed when user exits the book (`onCleared()` calls `readerScope.close()`)
- Scope ID = `bookUuid`

**Key Insight:** ReaderScope is per-book and destroyed when leaving the reader. This is fundamentally incompatible with Android Auto which needs playback to survive without any UI.

## Target Architecture

### Single Player, Single Session

```
MediaPlaybackService (MediaLibraryService)
└── mediaLibrarySession (MediaLibrarySession)
    └── player (ONE ExoPlayer)
        ├── Android Auto browsing observes this player
        ├── Android Auto playback uses this player (via HeadlessPlaybackSession)
        └── In-app playback uses this player (via MediaOverlayPlayer)
```

### Design Principles

1. **One ExoPlayer owned by MediaPlaybackService** - survives ReaderScope lifecycle
2. **One MediaLibrarySession** - handles both browsing AND playback
3. **In-app components connect via MediaController** or get player reference from service
4. **HeadlessPlaybackSession uses service's player** (already does this correctly!)

## Koin Scope Changes

### What MUST Change

| Component | Current | New | Reason |
|-----------|---------|-----|--------|
| `ExoPlayer` | ReaderScope | Service-owned | Must survive reader exit |
| `MediaSessionManager` | ReaderScope | Remove/Merge | Consolidate into service |

### What Stays The Same

| Component | Scope | Reason |
|-----------|-------|--------|
| `MediaOverlayPlayer` | ReaderScope | Per-book SMIL parsing |
| `LocatorTracker` | ReaderScope | Per-book position tracking |
| `PlaybackStateTracker` | ReaderScope | Per-book state tracking |
| `AudioFocusManager` | ReaderScope | Can keep per-book |
| `SmilLoadingManager` | ReaderScope | Per-book SMIL data |

### The Key Challenge: ExoPlayer Ownership

**Option A: Service owns ExoPlayer, ReaderScope borrows it**
- Service creates ExoPlayer in `onCreate()`
- `MediaOverlayPlayer` gets ExoPlayer reference from `MediaPlaybackController`
- When ReaderScope closes, player continues (for Android Auto)
- When new ReaderScope opens with different book, must handle player state

**Option B: Keep separate players, connect session to active player**
- ReaderScope keeps its ExoPlayer
- Service's MediaLibrarySession is built with service's ExoPlayer
- When in-app playback active, service's session player mirrors ReaderScope's player
- Complex state synchronization

**Recommendation: Option A** - Service owns the player.

## Implementation Plan

### Phase 1: Move ExoPlayer to Service

1. **Remove ExoPlayer from PlatformReaderModule.android.kt**
   - Delete `provideExoPlayer()` function
   - ExoPlayer will no longer be created per ReaderScope

2. **Create ExoPlayer in MediaPlaybackService.onCreate()**
   - Replace `stubPlayer` with actual `player`
   - Configure with same settings (seek increment, audio noisy handling)

3. **Expose player via MediaPlaybackController**
   - Add `getOrAwaitPlayer(): ExoPlayer` method
   - In-app components call this to get the service's player

### Phase 2: Consolidate MediaSession

1. **Remove MediaSessionManager class**
   - Its responsibilities move to MediaPlaybackService
   - Keep the MediaSessionCallback logic for seek commands

2. **Update MediaPlaybackService**
   - Build `MediaLibrarySession` with the real `player` (not stub)
   - Handle media button events, metadata, session activity

3. **Update MediaOverlayPlayer**
   - Remove `mediaSessionManager` dependency
   - Get ExoPlayer from `MediaPlaybackController` instead of Koin injection
   - Update metadata via `MediaPlaybackController` -> service

### Phase 3: Update MediaPlaybackController

```kotlin
@Single
class MediaPlaybackController {
    private var _player: ExoPlayer? = null
    private var _session: MediaLibrarySession? = null

    // Called by MediaPlaybackService.onCreate()
    fun onServiceCreated(service: MediaPlaybackService, player: ExoPlayer, session: MediaLibrarySession) {
        _player = player
        _session = session
    }

    // Called by MediaOverlayPlayer to get the shared player
    fun getPlayer(): ExoPlayer? = _player

    // Update metadata (forwarded to service/session)
    fun updateMetadata(title: String, chapter: String?, artwork: ByteArray?)
}
```

### Phase 4: Update Component Dependencies

**MediaOverlayPlayer changes:**
```kotlin
@Scope(ReaderScope::class)
@Scoped
class MediaOverlayPlayer(
    // REMOVE: private val exoPlayer: ExoPlayer,
    // REMOVE: private val mediaSessionManager: MediaSessionManager,
    private val controller: MediaPlaybackController,  // ADD
    // ... other deps stay the same
) {
    // Get player from controller instead of injection
    private val exoPlayer: ExoPlayer
        get() = controller.getPlayer() ?: throw IllegalStateException("Service not started")
}
```

**HeadlessPlaybackSession - no changes needed:**
- Already receives ExoPlayer as parameter
- Service passes its player when creating session

### Phase 5: Handle ReaderScope Lifecycle

When user exits reader (ReaderScope closes):
1. `MediaOverlayPlayer.release()` is called
2. Do NOT release ExoPlayer (service owns it)
3. Do NOT stop service (Android Auto may be using it)
4. Clear SMIL data, locator state, etc.

When user opens new book:
1. New ReaderScope created
2. New `MediaOverlayPlayer` gets existing player from controller
3. Load new book's SMIL data
4. Player continues with new content

## Files to Modify

### Delete
- None (keep all files, modify them)

### Major Changes
1. `MediaPlaybackService.kt` - Own player, build single session
2. `MediaPlaybackController.kt` - Expose player, handle metadata
3. `MediaOverlayPlayer.kt` - Get player from controller
4. `PlatformReaderModule.android.kt` - Remove ExoPlayer provider

### Minor Changes
1. `ForegroundServiceController.kt` - No changes needed
2. `PlaybackStateTracker.kt` - May need to get player differently
3. `LocatorTracker.kt` - May need to get player differently
4. `AudioFocusManager.kt` - May need to get player differently

### Remove
1. `MediaSessionManager.kt` - Merge into service

## Rollback Plan

If issues arise:
1. Keep `MediaSessionManager` but have it NOT create a MediaSession
2. Service's `MediaLibrarySession` handles everything
3. `MediaSessionManager` becomes just metadata/callback helper

## Testing Checklist

- [ ] In-app playback works (play, pause, seek, skip)
- [ ] Notification shows correct info
- [ ] Lockscreen controls work
- [ ] Bluetooth headset buttons work
- [ ] Android Auto browsing shows books
- [ ] Android Auto playback works
- [ ] Switching books in-app works
- [ ] Exit reader → notification persists (if playing)
- [ ] Resume playback after reopening reader
- [ ] Android Auto playback while in-app reader open

