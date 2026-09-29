# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Reading statistics now sync to Parrot Cloud. Reading sessions are uploaded automatically for signed-in Parrot Cloud accounts — including the full local history on the first sync — and merged on other devices, where time, sessions, books read and streaks are computed from them as before. Sessions are treated as an append-only ledger and are removed with the Parrot Cloud account.
- Prompted voice pack updates: when a new model version is published, voice settings offers "Download now / Later" instead of silently re-downloading. Updates install side-by-side and only switch once fully verified — a failed update leaves the current version fully working — the previous version is kept until the next update succeeds, and unchanged files are reused without downloading.

### Changed
- Replaced TTS voice pack preparation (archive download + on-device bzip2 extraction) with resumable, checksum-verified per-file downloads driven by a model manifest. Voice pack download sizes in the UI now come from the manifest instead of hardcoded constants.

## [0.4.5] - 2026-06-18

### Fixed
- Fixed crashes when parsing Storyteller series `featured` fields returned as booleans.

## [0.4.4] - 2026-06-02

### Added
- Added reader font weight controls for EPUB reading settings.
- Added a reader text normalization setting.
- Added a sleep timer postpone prompt.

### Changed
- Reorganized and refined reader settings controls for clearer configuration.
- Improved reader gestures, input dialogs, and screen-awake behavior while reading.
- Kept reader controls visible while the sleep timer menu is open.
- Moved e-ink display detection into shared base UI platform code.

### Fixed
- Removed redundant Android reader fragment state saving during activity shutdown.

## [0.4.3] - 2026-06-01

### Fixed
- Fixed Storyteller browser/OIDC login by completing authentication from the callback app token instead of requiring an additional user lookup.

### Changed
- Added sanitized analytics breadcrumbs for OAuth login failures, including browser flow, app-token exchange, callback token decoding, and unexpected exceptions.

## [0.4.0] - 2026-03-11

### Added
- **Configurable Tap Navigation** - Enable/disable tap navigation and customize left/right tap actions (Next Page or Previous Page)
- **Configurable Double-Tap Timeout** - Adjust double-tap detection timing (200-800ms) in Settings under Read Aloud section
- **Audio Progress Bar Visibility Setting** - Choose when to show the audio progress bar: On Tap (with controls) or Never

### Changed
- Moved double-tap detection from JavaScript to native code for more consistent behavior across platforms
- Renamed `VolumeButtonAction` enum to `NavigationAction` for semantic clarity (now used for both volume buttons and tap zones)

## [0.3.0] - 2026-03-10

### Added
- **Android Auto Integration** - Full Android Auto support with headless playback for listening to audiobooks while driving
- **Background Audio Playback** - Audio continues playing when the app is in the background with proper notification controls
- **Mini Player** - New mini player component that shows current playback state across the app
- **Chapter Navigation in Media Controls** - Skip to next/previous chapter directly from notification or Android Auto
- **Volume Button Navigation** - Use hardware volume buttons to navigate pages while reading

### Fixed
- Playback speed not being persisted between sessions
- Seek bar not updating when navigating pages
- Incorrect ReadAloud starting position
- `VisibleSentenceDetector` returning `not_found` on chapter change
- Position preservation when changing font size
- Multi-audio file playback and chapter completion
- Slow audio loading by pre-scanning all SMIL files

### Changed
- Improved synchronization between media playback and reader UI
- Better notification metadata with chapter titles
- Refactored Android audio playback to a service-centric architecture for better reliability
- Improved audio sync on navigation
- Updated README with Parrot app description

## [0.2.0] - 2025-XX-XX

### Added
- Separate underline color option for ReadAloud
- DATE_ADDED sort option for books
- CACHED quick filter for books
- Publication date support for local books
- Auto-scroll to expanded sections in settings

### Fixed
- Series position not showing in UI
- Highlight color selection and persistence issues
- Highlight color alpha being ignored by Readium
- Race condition in LocalServerInitializer causing imported books not to appear
- Underline color in dark theme

### Changed
- Made font selection expandable in Reader settings
- Renamed BooksViewModel to BooksListViewModel
- Refactored highlight color to support custom colors

## [0.1.0] - 2025-XX-XX

### Added
- Initial release
- Storyteller server integration
- Local server support for importing books
- EPUB reading with Readium
- Audio playback with media overlays (Read Aloud)
- Reading progress tracking and sync
- Book filtering and sorting
- Series support
- Reading statistics
- Customizable reader settings (fonts, colors, themes)
- Book caching for offline reading
- Multi-user/server support
- iOS and Android support

