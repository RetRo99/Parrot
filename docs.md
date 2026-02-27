# Read-Aloud Audio System Documentation

## Overview

The StoryTellerKMP app implements EPUB Media Overlays (W3C standard) for text-audio synchronization in read-aloud books. The system uses **SMIL (Synchronized Multimedia Integration Language)** files embedded in EPUBs to synchronize audio playback with text highlighting.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    ReaderSyncCoordinator                         │
│    (Orchestrates book↔audio synchronization)                     │
└─────────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  BookController │  │ AudioController │  │  SMIL Parsing   │
│  (Text Display) │  │ (Audio Player)  │  │    System       │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

## Core Components

### 1. SMIL Parsing System (Common/Kotlin)

#### SmilParser (`SmilParser.kt`)
- Parses SMIL XML using `kotlinx.serialization` with `xmlutil`
- Extracts `SmilClip` objects containing: `textSrc`, `audioSrc`, `clipBegin`, `clipEnd`

#### SmilClockParser (`SmilClockParser.kt`)
- Parses SMIL clock values in multiple formats:
  - **Metric format**: `2.5s`, `100ms`, `1.5h`, `30min`
  - **Colon-separated**: `00:01:30.500` (hh:mm:ss.ms)
- Returns time in **seconds** as Double

#### SmilLoadingManager (`SmilLoadingManager.kt`)
- **Lazy loading architecture** - critical for performance
- Builds lightweight index first (fast regex scan)
- Parses SMIL files on-demand when chapter accessed
- **Prefetches next N chapters** in background
- Uses coroutines with Mutex for thread-safety

#### SmilQuickScanner (`SmilQuickScanner.kt`)
- Fast regex-based scanner for chapter references
- Extracts `src` attribute from `<text>` elements
- Normalizes chapter hrefs for consistent comparison

#### SmilChapterIndex (`SmilChapterIndex.kt`)
- Maps chapter hrefs → list of SMIL file paths
- Thread-safe with Mutex
- Handles chapters with **multiple SMIL files** (split audio)

#### SmilClipCache (`SmilClipCache.kt`)
- Session-level cache (no eviction during reading)
- Prevents duplicate parsing of same SMIL files

### 2. Audio Playback (Platform-Specific)

#### Android: MediaOverlayPlayer (`MediaOverlayPlayer.kt` - 836 lines)
- Uses **ExoPlayer** with playlist support
- **Key features**:
  - Playlist-based playback for multi-audio-file chapters
  - Position normalization (displays from 0:00 per chapter)
  - Foreground service with notification
  - Audio focus management (ducking, transient loss)
  - Now Playing info for lock screen

#### iOS: MediaOverlayPlayer (`MediaOverlayPlayer.swift` - 814 lines)
- Uses **AVPlayer**
- **Key features**:
  - Temp file caching for streamed audio
  - Now Playing info integration
  - Seek position protection (prevents UI showing 0 after seek)

### 3. Synchronization Components

#### ReaderSyncCoordinator (`ReaderSyncCoordinator.kt`)
- Central coordinator for book↔audio sync
- Handles:
  - Book page turn → audio chapter preparation
  - Audio position → text highlight updates
  - Double-tap on sentence → start playback from that sentence
  - Chapter completion → auto-play next chapter

#### LocatorTracker (`LocatorTracker.kt` - Android)
- Tracks playback position, maps to text locators
- Handles `chapterStartOffset` for position normalization
- Emits `MediaOverlayLocatorUpdate` with fragment ID and sentence duration

#### SentenceVisibilityChecker (`SentenceVisibilityChecker.kt`)
- **JavaScript-based** visibility detection in WebView
- Uses `getClientRects()` for split sentence detection
- Constants:
  - `MINIMUM_VISIBLE_FRACTION = 0.8` (80% visible = considered visible)
  - `AWKWARD_BUFFER_THRESHOLD = 0.90` (90% through = pre-emptive page turn)

#### DoubleTapDetector (`DoubleTapDetector.kt`)
- Injects JavaScript for double-tap detection on sentences
- Platform-specific callbacks:
  - Android: `JavascriptInterface`
  - iOS: `WKScriptMessageHandler`

---

## Edge Cases & Intricacies

### 1. Chapters Without Audio
- **Problem**: Cover pages, TOC, and some chapters have no SMIL data
- **Solution**: `onChapterAudioCompleted` fires immediately, triggering skip to next chapter
- **Code path**: `prepareChapter()` → empty clips → `onChapterAudioCompleted?(normalizedHref)`

### 2. Multi-Audio-File Chapters (Deep Dive)

Some EPUB chapters split audio narration across multiple MP3/audio files. This is common in professionally produced audiobooks where chapters may be 30+ minutes long.

#### Android Implementation (ExoPlayer Playlist)

**Key Data Structures:**
```kotlin
// Maps audio href → track index in ExoPlayer playlist
audioHrefToTrackIndex: Map<Url, Int>

// Ordered list of audio hrefs in current playlist
playlistAudioHrefs: List<Url>

// Duration per audio file (maxEndTime - minStartTime of clips)
audioDurations: Map<Url, Long>

// Minimum clip start time per audio file (for position normalization)
audioStartOffsets: Map<Url, Long>
```

**Single-Pass Calculation Algorithm:**
```kotlin
// Iterates through all clips ONCE to calculate:
// 1. audioStartOffsets (min start time per audio file)
// 2. audioDurations (max end - min start per audio file)
// 3. audioFilesOrdered (order of appearance)
// 4. targetClip (for fragment lookup)

for (clip in allChapterClips) {
    val stats = audioStatsMap.getOrPut(clip.audioHref) { AudioStats() }
    if (startMs < stats.minStart) stats.minStart = startMs
    if (endMs > stats.maxEnd) stats.maxEnd = endMs
}
```

**Playlist Preparation:**
```kotlin
// Build MediaItems for all audio files
val mediaItems = audioHrefs.map { audioHref ->
    MediaItem.Builder()
        .setUri(audioUrl)
        .setMediaMetadata(mediaSessionManager.buildCurrentMetadata())
        .build()
}

// Create ProgressiveMediaSource for each
val mediaSources = mediaItems.map { mediaItem ->
    ProgressiveMediaSource.Factory(dataSourceFactory)
        .createMediaSource(mediaItem)
}

// Set playlist with initial track and position
exoPlayer.setMediaSources(mediaSources, initialTrackIndex, initialPositionMs)
exoPlayer.prepare()
```

**Seamless Track Switching:**
```kotlin
fun switchAudioFileIfNeeded(audioHref: Url, positionMs: Long) {
    val trackIndex = audioHrefToTrackIndex[audioHref]
    if (trackIndex != null) {
        // Update duration and start offset for new track
        playbackStateTracker.setTotalDuration(audioDurations[audioHref])
        locatorTracker.setChapterStartOffset(audioStartOffsets[audioHref])

        // Seamless switch using ExoPlayer's seekTo(trackIndex, position)
        exoPlayer.seekTo(trackIndex, positionMs)
    }
}
```

**Why This Works:**
- ExoPlayer only buffers the current track (no memory overhead)
- `seekTo(trackIndex, positionMs)` switches instantly without re-preparing
- No audible gap between audio files
- Track index → href mapping enables fragment-based navigation

#### iOS Implementation (Sequential - Less Seamless)
- Uses AVPlayer with single audio file at a time
- Must load new audio file when transitioning → audible gap
- See "Potential Improvements" section for AVQueuePlayer suggestion

#### Position Tracking Across Files
- `chapterStartOffset` = minimum clip start time in current audio file
- Display position = `rawPosition - chapterStartOffset`
- Each audio file effectively displays 0:00 → its own duration
- When switching tracks, offset is updated to new file's minimum

### 3. Position Normalization
- **Problem**: Raw audio position includes previous audio files' duration
- **Solution**: Display `currentPosition - chapterStartOffset` so each chapter shows 0:00→end
- **Seek bar**: User sees chapter-relative position, not playlist position

### 4. Split Sentences Across Pages
- **Problem**: Sentence may span multiple pages (partially visible)
- **Solution**: `SentenceVisibilityChecker` uses `getClientRects()` to detect visibility
- **Pre-emptive page turn**: If >90% through current content, auto-turn page
- **Edge case**: Awkward splits handled by checking if majority of sentence on current page

### 5. Seek Position Protection (iOS)
- **Problem**: After seek, AVPlayer emits position 0 briefly before catching up
- **Solution**: `lastSeekTargetMs` flag prevents time observer from overwriting until caught up
- **Tolerance**: 500ms for position comparison

### 6. Audio Focus Management (Android)
- **Ducking**: Lower volume when notification sounds play
- **Transient loss**: Pause and auto-resume after phone call
- **Permanent loss**: Stop playback entirely
- **Implementation**: `AudioFocusManager` with `OnAudioFocusChangeListener`

### 7. Chapter Completion Detection
- **Threshold**: Consider complete when position >= duration - 200ms
- **Flag**: `hasNotifiedChapterCompletion` prevents duplicate callbacks
- **Auto-play**: Seamlessly starts next chapter without user interaction

### 8. Fragment ID Matching
- **Format**: `chapter44.xhtml-sentence50`
- **Matching**: Exact string match between SMIL `text src` fragment and DOM element ID
- **Edge case**: Some EPUBs use inconsistent fragment naming

### 9. Temp File Management (iOS)
- **Problem**: AVPlayer can't stream directly from EPUB container
- **Solution**: Extract audio to temp file, cache by href
- **Cleanup**: `tempFileCache` cleared on `release()`

### 10. Foreground Service (Android)
- **Required**: For background playback (Android 8+)
- **Notification**: Shows play/pause controls, book info
- **Lifecycle**: `ForegroundServiceController` manages start/stop

---

## Data Flow

### Play from Sentence (Double-Tap)
```
User double-taps sentence
    ↓
DoubleTapDetector (JavaScript)
    ↓
ReaderSyncCoordinator.handleDoubleTap(fragmentId)
    ↓
AudioController.playFromFragment(fragmentId)
    ↓
MediaOverlayPlayer.play(chapterHref, initialFragmentId)
    ↓
findPositionForFragment(fragmentId) → clipBegin time
    ↓
seekTo(positionMs) → startPlayback()
```

### Audio → Text Highlight Sync
```
MediaOverlayPlayer (time observer @ 100ms)
    ↓
Find clip where currentTime ∈ [clipBegin, clipEnd)
    ↓
Extract fragmentId from clip
    ↓
onLocatorChanged(locator, sentenceDurationMs)
    ↓
ReaderSyncCoordinator.handleLocatorUpdate()
    ↓
BookController.highlightSentence(fragmentId)
    ↓
JavaScript: element.classList.add("highlight")
```

---

## Potential Improvements

### High Priority

#### 1. **Memory-Efficient Clip Storage**
- **Current**: All clips for a chapter stored as List<SmilClip>
- **Issue**: Large chapters (400+ sentences) create memory pressure
- **Suggestion**: Binary search index with lazy clip loading, or memory-mapped structure

#### 2. **Seamless Multi-File Playback on iOS**
- **Current**: iOS handles multi-file chapters sequentially with gaps
- **Issue**: Audible pause between audio files
- **Suggestion**: Pre-buffer next audio file, use AVQueuePlayer

#### 3. **Offline-First SMIL Caching**
- **Current**: SMIL parsed fresh each session
- **Issue**: Re-parsing on every app launch
- **Suggestion**: Persist SmilChapterIndex and parsed clips to disk (Room/SQLite)

### Medium Priority

#### 4. **Improved Error Recovery**
- **Current**: Silent failures on SMIL parse errors
- **Issue**: User sees no audio without explanation
- **Suggestion**: Emit specific error states (NO_AUDIO, PARSE_ERROR, CORRUPT_SMIL)

#### 5. **Background Prefetch Optimization**
- **Current**: Prefetch next 1 chapter
- **Suggestion**: Prefetch next 2-3 chapters, with priority queue based on reading speed

#### 6. **Variable Playback Speed Persistence**
- **Current**: Speed resets on chapter change
- **Suggestion**: Persist user's preferred speed per book

#### 7. **Sentence-Level Bookmarking**
- **Current**: Only chapter-level position saved
- **Suggestion**: Save exact fragmentId and audio position for precise resume

### Low Priority (Nice-to-Have)

#### 8. **Audio Waveform Preview**
- **Current**: Simple seek bar
- **Suggestion**: Generate waveform thumbnail for visual audio navigation

#### 9. **Sleep Timer**
- **Current**: Not implemented
- **Suggestion**: Add sleep timer (15min, 30min, end of chapter)

#### 10. **Accessibility Improvements**
- **Current**: Basic audio playback
- **Suggestion**:
  - Screen reader announcements for chapter changes
  - Haptic feedback on sentence transitions
  - Voice control commands

---

## File Reference

| Component | Android Path | iOS Path |
|-----------|-------------|----------|
| AudioController | `feature/reader/ui/.../AndroidAudioController.kt` | `feature/reader/ui/.../IosAudioController.kt` |
| MediaOverlayPlayer | `feature/reader/ui/.../MediaOverlayPlayer.kt` | `iosApp/iosApp/MediaOverlayPlayer.swift` |
| SmilParser | `feature/reader/ui/.../smil/SmilParser.kt` | (shared Kotlin) |
| SmilLoadingManager | `feature/reader/ui/.../smil/SmilLoadingManager.kt` | (shared Kotlin) |
| ReaderSyncCoordinator | `feature/reader/ui/.../ReaderSyncCoordinator.kt` | (shared Kotlin) |
| SentenceVisibilityChecker | `feature/reader/ui/.../SentenceVisibilityChecker.kt` | (shared Kotlin) |

---

## SMIL File Structure Example

```xml
<smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
  <body>
    <seq id="seq1">
      <par id="par1">
        <text src="chapter01.xhtml#sentence1"/>
        <audio src="audio/chapter01.mp3" clipBegin="0s" clipEnd="2.5s"/>
      </par>
      <par id="par2">
        <text src="chapter01.xhtml#sentence2"/>
        <audio src="audio/chapter01.mp3" clipBegin="2.5s" clipEnd="5.1s"/>
      </par>
    </seq>
  </body>
</smil>
```

Each `<par>` element synchronizes a text fragment (`#sentence1`) with an audio clip (`clipBegin` to `clipEnd`).

