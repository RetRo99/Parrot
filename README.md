# Parrot

A cross-platform ebook and audiobook reader app built with Kotlin Multiplatform and Compose Multiplatform. Parrot is a client for [Storyteller](https://gitlab.com/smoores/storyteller) servers, allowing you to stream and read your personal book library on Android and iOS.

## Features

- **📚 Ebook Reader** - Read EPUB books with customizable fonts, themes, and margins
- **🎧 Audiobook Player** - Listen to audiobooks with playback controls
- **🗣️ Read Aloud** - Synchronized text highlighting with audio narration (for books with media overlays)
- **📖 Multiple Server Support** - Connect to multiple Storyteller servers and switch between them
- **📁 Local Books** - Import and read local EPUB files
- **🌐 Book Catalogues (OPDS)** - Browse OPDS catalogues such as Project Gutenberg and download EPUB books straight into your library
- **📊 Reading Statistics** - Track reading time, streaks, sessions, and most-read books
- **🔖 Reading Progress Sync** - Sync reading position across devices via server
- **📑 Table of Contents** - Navigate chapters easily
- **🔍 Search & Filter** - Find books by title, author, series, or tags
- **⭐ Favorites** - Mark books as favorites for quick access
- **📱 E-Ink Support** - Optimized color scheme for e-ink devices
- **🌙 Dark Mode** - System-aware dark/light theme support

## Supported Platforms

| Platform | Status |
|----------|--------|
| Android  | ✅     |
| iOS      | ✅     |

## Project Structure

```
├── composeApp/          # Main application module (shared UI & app entry points)
├── androidApp/          # Android-specific app wrapper
├── iosApp/              # iOS-specific app wrapper (Xcode project)
├── feature/             # Feature modules (Clean Architecture)
│   ├── auth/            # Authentication
│   ├── books/           # Book listing, details, series
│   ├── home/            # Home navigation & bottom tabs
│   ├── login/           # Login/onboarding flow
│   ├── reader/          # EPUB reader & audio player
│   ├── settings/        # Reader settings
│   └── statistics/      # Reading statistics & analytics
├── lib/                 # Shared libraries
│   ├── analytics/       # Analytics abstraction
│   ├── database/        # SQLDelight database
│   ├── network/         # Ktor networking
│   ├── preferences/     # DataStore preferences
│   ├── server/          # Server abstraction layer
│   ├── server-local/    # Local file server implementation
│   ├── server-storyteller/ # Storyteller API implementation
│   └── user/            # User & server management
├── base/                # Base classes & utilities
├── base-ui/             # Shared UI components
└── translations/        # Internationalization resources
```

## Tech Stack

- **Kotlin Multiplatform** - Share code across Android, iOS, and Desktop
- **Compose Multiplatform** - Declarative UI framework
- **Koin** - Dependency injection with annotations
- **Ktor** - HTTP client for API communication
- **SQLDelight** - Type-safe SQL database
- **DataStore** - Preferences storage
- **Readium** - EPUB rendering engine
- **Coil** - Image loading

## Building

### Prerequisites

- JDK 17+
- Android Studio or IntelliJ IDEA
- Xcode (for iOS builds)

### Android

```shell
./gradlew :composeApp:assembleDebug
```

### iOS

Open `iosApp/iosApp.xcworkspace` in Xcode and run the project.

## Server Setup

Parrot requires a [Storyteller](https://gitlab.com/smoores/storyteller) server to stream books. You can also import local EPUB files directly into the app.

## Book catalogues (OPDS)

Parrot can browse book catalogues that speak OPDS, the feed format used by Project Gutenberg,
Calibre, Kavita, Komga and many libraries, and download books from them into your library.
A downloaded book is an ordinary library book: it stays when the catalogue is turned off or
removed, and it is read, searched and synced like a book you imported yourself.

### Adding a catalogue

1. Open **Get books**.
2. Pick one under **Start with one of these**, or choose **Add by address** and type the
   catalogue's address, for example `https://books.example.org/opds`.
3. If the catalogue asks for an account, turn on **Needs an account** and enter your user name
   and password. Parrot checks them against the catalogue before it saves anything.

The catalogue then appears under **Your catalogues**. Browse it, search it if it offers
search, and tap a book to download it. **Downloads** shows what is running and what failed.
A catalogue's settings let you change its address or account, turn it off, or remove it.

### What this release supports

| | |
|---|---|
| Formats | OPDS 1.x (Atom) and OPDS 2.0 (JSON) catalogues. EPUB files only. |
| Sign-in | User name and password (HTTP Basic), over https only. |
| Search | OpenSearch descriptions and OPDS 2 search templates. |
| Filters | OPDS 2 facets. |
| Offline | Pages you have opened are kept and shown, marked as a saved copy, when the catalogue cannot be reached. |

### What it does not support

- **No Digest, OAuth or OPDS authentication-document sign-in.** A catalogue that needs one of
  these can be added only if its first page is public, and then only its public part.
- **No borrowing, buying or subscriptions.** Such books are listed with the reason they cannot
  be downloaded and, where the catalogue gives one, a link to its own page.
- **No sample downloads.**
- **EPUB only.** PDF, MOBI, comics and audiobooks in a catalogue are shown as unavailable.
  Books protected by DRM are refused.
- **No password over http.** A catalogue on a plain `http://` address can be used without an
  account only, after a warning.
- **iPhone needs https.** On iPhone and iPad a catalogue on an `http://` address cannot be
  added at all.
- **No filters for OPDS 1 catalogues**, no search across several catalogues, no resuming a
  download part-way, and no downloading while the app is closed.

### Limits

| Limit | Value |
|---|---|
| One book file | 512 MiB |
| Downloads at once | 2; the rest wait in order |
| One catalogue page | 5 MiB, 2,000 entries |
| One cover | 4 MiB (256 KiB when it is carried inside the page) |
| Pages of one list kept in memory | 20 |
| Saved pages for offline use | 25 MiB per profile; the oldest go first |
| Redirects followed for one request | 5 |

These are Parrot's own limits, not limits of OPDS.

What has been tried on real devices and servers, and what has not, is in
[docs/opds-compatibility.md](docs/opds-compatibility.md).

## License

This project is for personal use.

