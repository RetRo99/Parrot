# Book catalogues (OPDS): the first iPhone run

Run on 2026-10-09 on branch `opds/ios-check`, off `main` at 90675a6b, in the iOS Simulator
("iPhone Air", iOS 27.0) on macOS 27.0.1 with Xcode 27.0. Nothing had ever been opened on iOS
before this; only unit tests had run there. No product code was changed: `git diff main` over
sources is empty. Screenshots are in `docs/manual-qa-evidence/2026-10-09/ios-catalogue/`.

This report answers four of the "could not verify" items in `opds-security-review.md` and two
of the open product questions in `opds-release-checklist.md`.

## How the app was driven

Xcode 27 ships no `Simulator.app` (it is replaced by a windowless `DeviceHub`), and this Mac
had no usable desktop session to click in, so there was no window to automate. `simctl` has no
tap command. The app was therefore driven by an XCUITest bundle built **outside this repo**
(in `/tmp/ParrotDriver`), which runs inside the simulator and executes commands written to a
file by the host: tap, type, drag, screenshot, and a dump of the accessibility tree. Nothing in
the repo was changed to make this work, and the driver was uninstalled afterwards.

Worth keeping: every catalogue screen exposes usable accessibility labels, which is what made
this possible. Rows read as "Title, Author, edition line", buttons as "Download <title>".

## Build and run

```bash
# Pods were already in sync; no pod install was needed.
cd iosApp
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,id=45F57B43-B65F-4F73-AAED-034D5B3DA463' \
  -derivedDataPath /tmp/parrot-dd build
xcrun simctl install 45F57B43-B65F-4F73-AAED-034D5B3DA463 \
  /tmp/parrot-dd/Build/Products/Debug-iphonesimulator/Parrot.app
xcrun simctl launch 45F57B43-B65F-4F73-AAED-034D5B3DA463 com.retro99.parrot
```

It built first time, with no extra heap and no change to the locked pod versions. The Kotlin
framework step did not run out of memory at the committed `org.gradle.jvmargs=-Xmx4096M`.

## What was tried, and what happened

Everything below is a real screen on the simulator unless it says otherwise.

| Step | Result |
|---|---|
| A. Fresh start, Get books | Passes. The library opens, Add → "Get books", and the built-in catalogues **are** present on iOS: Project Gutenberg, Standard Ebooks, "Another catalogue". The Android packaging fault does not happen here. |
| B. Gutenberg browse, search, book page | Passes. First page lists Popular/Latest/Random; a list shows title, author and a row download button; paging loads page after page silently; search works and echoes `"<text>" in Project Gutenberg`; the book page shows the real cover, metadata, rights lines, "Other files (5)" and a "Choose a file" sheet with its edition groups. |
| C. Download from the book page, Read now | Passes. States: "Downloading… 0.0 of 24.8 MB" with a progress bar, **the iPhone wording** under it, and Cancel; then "✓ In your library · downloaded just now" with "Read now", which opens the reader on real text. |
| D. Downloads screen | Passes. A running row shows "0.0 of 20.8 MB" with Cancel; a finished row shows "✓ In your library" with Open, which opens the reader. Finished rows are gone when the screen is left again ("No downloads"), by design. |
| E. Leaving and killing the app mid-download | See below. Killing behaves exactly as designed; leaving the app did not stop the download here. |
| F. Plain http | The dialog is right, and **iOS does not actually block http**. See below. |
| G. Libraries and catalogue settings | Passes. Cards show name, address, status and "Checked <n> min ago" with Browse. Settings has the address, "Edit address", the account block, "Use this catalogue" and "Remove catalogue…". Turning off shows "Turned off. Browsing and downloads … are paused." with "Turn on". Removing keeps the book: it is still in the library and still opens. The remove dialog gets singular and plural right ("The book you downloaded…" / "The 2 books you downloaded…"). |
| H. Errors and offline | Passes, all four. A web page: "This is a web page, not a catalogue. On that site, look for a link called "OPDS" and paste that address." Unreachable: "Can't reach this address…". A page opened before, offline: the saved copy with a "**You're offline.** Showing the copy saved just now." banner. A page never opened: "You're offline / This page hasn't been opened before…" with "Try again". A real transient 5xx from Gutenberg produced "Couldn't open this page / Project Gutenberg had a problem on its side", and "Try again" recovered. |
| I. iOS-only checks | Three answers and one new fault. See below. |
| J. Day and Night | Nothing clipped or misplaced in either theme on the add screen, Get books, a list, a book page or Downloads. Two differences from the boards, both small; see below. |
| K. Profiles | The known profile-creation crash reproduces on iOS. Once past it, the second profile shows none of the first profile's catalogues, downloads or saved pages, in the UI and in its own database. |

### E. Leaving and killing the app mid-download

**Killing it is exactly as designed.** A 20.8 MB download was started and the app killed about
a second later. The row was left `downloading` in the database with a `.part` staging file. On
relaunch, Downloads showed the request as **"Stopped when Parrot closed"** in red with a
**"Start again"** button, nothing had restarted by itself, and "Start again" completed and the
book landed in the library.

**Leaving the app did not stop the download.** A 46.1 MB download was started, Home was
pressed about a second later, and the app was left in the background for 70 seconds. On return
the book was in the library, and `device_files` shows the full 46,093,367 bytes written at
14:26:19Z — while the app was in the background. So the download ran to completion off-screen.

This does **not** show that the iPhone wording is wrong, and it is not evidence that
backgrounding is safe. The simulator does not enforce iOS's background-execution limits, and
even on a real device 46 MB at the speed seen here (~4 MB/s) fits inside the ~30 seconds iOS
normally grants, so a device could finish it too. What a device does with a download that
needs *minutes* in the background is still untested. This needs a real iPhone and a slow or
large transfer.

### F. Plain http: the dialog, and the truth about iOS

The dialog is right. For `http://localhost:8791/opds/` and for `http://192.168.1.109:8791/opds/`
the add screen refuses with a single-button dialog: **"This catalogue can't be added"** /
"On this iPhone, Parrot can only use secure addresses that start with https://. Ask whoever
runs the catalogue for an https:// address." / the address / **OK**. The device-name helper
resolves correctly to "this iPhone".

**Is http really blocked by iOS for this app? No — for neither address.**
`ALLOW_HTTP_CATALOGUES` was flipped to `true` locally, the app rebuilt and reinstalled, and
the local test catalogue was served from this Mac:

- `http://localhost:8791/opds/` — the one-page check **succeeded**; the server logged
  `127.0.0.1 "GET /opds/ HTTP/1.1" 200`. Parrot then showed its own "This catalogue isn't
  secure" warning with "Add anyway" / "Go back". Added, browsed, and a book downloaded with
  the row button (`GET /files/1.epub 200`), which opened.
- `http://192.168.1.109:8791/opds/` (this Mac's LAN IP) — also **succeeded**, logged from
  `192.168.1.109`. Added, browsed a five-book list, downloaded a book with the row button
  (`GET /files/2.epub 200`). No iOS local-network permission prompt appeared.

`iosApp/iosApp/Info.plist` has **no** `NSAppTransportSecurity` key, and neither does the built
`Parrot.app/Info.plist` (checked with `plutil`; no pod injects one). So nothing in the app asks
iOS for a cleartext exception, and iOS allowed cleartext anyway, to loopback *and* to a private
LAN address. `NSAllowsLocalNetworking` was not needed. The flip was reverted and the app
rebuilt before the rest of the run; `git diff main` over sources is empty.

The note in the release checklist that "iOS itself must also allow it … without it the request
fails whatever the line above says" is therefore wrong as written, at least on iOS 27 in the
simulator. **Product question 2 is a product decision, not a platform limit.** It should still
be confirmed once on a real device before the decision is final.

One related detail: the http block is applied when a catalogue is **added** and when its
address is **edited** (`CatalogueSettingsViewModel` passes `CatalogueHttpPolicy.allowHttp` into
its controller), but not when an http catalogue that is already stored is used. On the shipping
build, with the flag back to `false`, the http catalogues added during the experiment still
browsed and still downloaded. That matters for a profile that arrives from Android, or from a
backup, carrying an http catalogue.

### I. The iOS-only checks

**1. A compressed response does not break a download.** Answered: **no mismatch**. Neither
Gutenberg nor the repo's test server compresses anything (checked with
`Accept-Encoding: gzip`; no `Content-Encoding` comes back), so a server was built for this: a
local **https** catalogue, trusted via a throwaway CA added with
`xcrun simctl keychain … add-root-cert`, serving one book gzip-encoded with
`Content-Encoding: gzip` and `Content-Length` set to the **compressed** size (781 bytes for a
23,330-byte EPUB). The download finished normally and the row read "✓ In your library";
`catalogue_acquisitions` recorded expected 23330 and received 23330, and the stored file is
23,330 bytes. iOS replaces `Content-Length` with the decompressed length when it decompresses
transparently, so `count != declared` in `KtorOpdsTransport` never fires. This closes the
worry in section 2 of the release checklist.

**2. The keychain item is not "this device only".** Read, not run. `IosSettingsFactory` builds
`KeychainSettings(service = name ?: "SecureSettings")`, and that constructor sets only
`kSecClass = kSecClassGenericPassword` and `kSecAttrService`. No `kSecAttrAccessible` is set
anywhere, so the item takes the default, `kSecAttrAccessibleWhenUnlocked` — **not**
`…ThisDeviceOnly`. A catalogue's account details are therefore included in an encrypted device
backup and can travel to a new iPhone. This confirms what the security review says, and leaves
product question 4 as written. It still needs a real backup and restore to see end to end.

**3. Catalogue responses *are* written to the system HTTP cache on disk. This is new.**
See the fault below.

**4. Memory while scrolling a long list with covers: no leak and no crash.** A Gutenberg
"Popular" list was scrolled with roughly 80 drag batches, loading page after page of rows with
generated covers. RSS went 354 MB → 416 MB as the first pages and pictures loaded, then sat
between 424 MB and 433 MB for the remaining ~70 batches. It plateaus; it does not climb with
the number of pages. The app stayed responsive and did not crash.

## The one new fault

**Catalogue traffic is written to the system HTTP cache on disk, including search text and
whole book files.** This answers item 7 of the security review's open list: yes, the iPhone's
system HTTP cache does write catalogue responses to disk.

After browsing, in the app's container under
`Library/Caches/com.retro99.parrot/` there is `Cache.db` (plus `-wal`, `-shm`) and a
`fsCachedData/` directory holding the response bodies. Of 61 cached responses, 21 were the
catalogue's:

- Feed pages, including every paging step: `…/ebooks/search.opds/?sort_order=downloads`,
  `…&start_index=26`, `…=51`, and so on.
- **Search pages, with the search text in the key**:
  `https://www.gutenberg.org/ebooks/search.opds/?query=Treasure%20Island` and
  `…?query=Pride%20and%20Prejudice`. The body on disk is the feed XML in the clear; the words
  the user typed appear in it.
- Covers: `…/pg120.cover.medium.jpg`.
- **The downloaded book itself**: `https://www.gutenberg.org/cache/epub/120/pg120.epub`, whose
  body is a 274,139-byte file that `file(1)` calls an `EPUB document` — a second copy of the
  book beside the one in `Documents/library/`.

Why: `KtorOpdsTransport` installs no cache of its own and sets no cache policy, so the Darwin
engine uses `NSURLSession`'s default shared `NSURLCache`.

What it costs:

1. **Disk.** A download takes roughly twice the book's size until the cache is evicted. This is
   exactly the "Parrot's size grew by about twice that" check in section 2 of the release
   checklist, and the answer is that it does.
2. **The saved-page rules are bypassed.** The security review's account of saved pages — at
   most 25 MiB per profile, cleared when the catalogue is turned off, moved, removed or its
   account changes, keyed by profile and access generation — describes `SavedPagesFeedCache`
   only. The system cache obeys none of it. It is not per profile, it is not cleared when a
   catalogue is removed or turned off, and it survives a profile switch. Search text from one
   profile stays on disk after switching to another.
3. Not a backup leak: `Library/Caches` is excluded from device backup by iOS.

The fix I would make, were it mine to make in a testing run: give the catalogue's HTTP client
its own ephemeral or disabled cache, so the default shared one is never used — in Ktor's Darwin
engine, configure the `NSURLSessionConfiguration` with `URLCache = null` and
`requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData`, next to where the per-
catalogue client is built. It belongs with the transport, so it would not touch Storyteller,
Audiobookshelf, Parrot Cloud or local books. It needs an iOS-side test, which is why it was not
attempted here: the run's rule was to fix only what a test can cover, and `composeApp`'s iOS
tests do not link today (`FirebaseCore`), so there is no iOS test target to put it in.

## Smaller things, none of them blocking

1. **A Gutenberg list can never show real covers.** Gutenberg's acquisition feeds carry a
   thumbnail for every entry, but it is a 22×22 `data:image/png;base64,…` icon, the same
   generic picture for every book. Parrot shows its generated title covers instead, which looks
   right. Real covers *do* load — the book page's larger cover is the actual artwork, fetched
   over https. So cover loading on iOS works, but the release checklist's "check that covers
   appear and are the right pictures" cannot be satisfied from a Gutenberg list, and needs a
   catalogue that publishes real thumbnails (Calibre-Web, Kavita, Komga).
2. **A row's second line sometimes holds a download count, not an author.** Gutenberg puts
   whatever it likes in `<content>`, so rows read "92258 downloads" or "14325 downloads" where
   an author belongs. Parrot is showing what it was given.
3. **A tiny book's size reads "0.0 MB".** The local test book is 23 KB and its button says
   "Download · EPUB · 0.0 MB". Worth a kilobyte case in the size formatter.
4. **Two differences from the boards.** `opds-catalogues-day.png` shows a count badge on the
   "Downloads" pill ("Downloads ②"); the app's pill has no badge. The board's rows also show
   only the access line ("No account needed") where the app shows address, status and access
   ("gutenberg.org · Ready · no account needed"), and the board uses a chevron where the app
   uses an arrow. `opds-detailIos-day.png` matches the real download card closely, except that
   the app also raises a "Downloading <title> / View" snackbar, which is not on the board.
5. **A self-referential folder.** One "Pride and Prejudice" search result opens a feed whose
   only entry is "Pride and Prejudice" again. Parrot does not loop on it, which is what the
   paging-loop fix is for, but it is a dead end for the reader.
6. **Typing into a pre-filled address field misplaces characters.** Appending to an existing
   address after tapping to place the cursor produced dropped characters when typed fast, and,
   typed one character at a time, put all 25 characters in but left the first (`?`) at the end.
   An empty field typed in one go was always correct. **Not reported as an app fault**: this
   was synthetic XCUITest typing, which is not the same input path as a finger on the on-screen
   keyboard, and it needs a person with a real keyboard to confirm before anyone acts on it.

## Not from the catalogue work

1. **Creating a profile crashes the app**, as already recorded for `main`. Reproduced here on
   iOS: `SIGABRT` from an unhandled Kotlin exception in a coroutine on the main dispatcher —
   `terminateWithUnhandledException` ← `processUnhandledException` ←
   `kotlinx.coroutines.internal#propagateExceptionFinalResort` ←
   `handleUncaughtCoroutineException` ← `StandaloneCoroutine.handleJobException` ←
   `JobSupport.finalizeFinishingState` ← `DarwinMainDispatcher`. The throwing frame is not in
   the resumption stack and the report carries no exception message. The profile *is* created,
   and it becomes the active one, so the app relaunches into the welcome screen — which has no
   profile switcher, so the only way back to the first profile is through onboarding (opening
   the file picker and cancelling lands in the empty library, and Settings then has the
   switcher). Crash file: `~/Library/Logs/DiagnosticReports/Parrot-2026-10-09-165957.ips`.
2. **`device_files.file_path` stores an absolute path including the app container UUID.** This
   cost an hour and is worth writing down. Reinstalling the app on the simulator gives the data
   container a new UUID while carrying the data over, so every stored path goes stale and books
   that are present on disk report "Can't open this book … no such file", surviving a relaunch.
   It is not from the catalogue work: an `import` row from 2026-10-06 has the same shape. On a
   real iPhone the container UUID is stable across app updates, so a user would not normally
   see it. It did make the first "a removed catalogue's book still opens" check look like a
   failure; redone with a book downloaded after the last install, it passes.

## What is still untested

- A catalogue with a password, on iOS. No https catalogue with Basic sign-in was available, so
  section 3 of the release checklist is untouched, as is the "Show/Hide" control on the address:
  it only renders when the address contains something that reads as a key
  (`state.hasKey`), and the edit screen refuses an address it cannot reach, so a made-up keyed
  address cannot be saved.
- A real backup and restore, for the keychain question.
- A device: background limits on a long download, real TLS failures, and the 30-second
  "no bytes" limit.
- Any catalogue other than Project Gutenberg and the two local test servers.
- VoiceOver. Labels were read by the driver and are present and sensible, but no screen reader
  was run.

## Left behind on the simulator

- The Default profile now holds the books downloaded during the run (Treasure Island, Pride and
  Prejudice, Don Quixote, The History of Don Quixote vol. 1, and four small test books) and the
  Project Gutenberg catalogue. The two local test catalogues were removed.
- A second profile, "QA Two", created by the crash test.
- **A throwaway test root CA is still trusted in that simulator.** `simctl keychain` can only
  `reset` the whole keychain, which would sign the profile out of Storyteller, so it was left
  in place rather than doing that unasked. Its private key has been destroyed, so no further
  certificates can be minted with it, and the certificate expires in 30 days. To remove the
  trust: `xcrun simctl keychain 45F57B43-B65F-4F73-AAED-034D5B3DA463 reset` (this signs the
  profile out of its servers), or erase the device.
