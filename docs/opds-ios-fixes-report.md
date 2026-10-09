# Book catalogues (OPDS): fixing what the first iPhone run found

Run on 2026-10-09 on branch `opds/ios-fixes`, off `opds/ios-check` at 521c5ed7, in the iOS
Simulator ("iPhone Air", `45F57B43-B65F-4F73-AAED-034D5B3DA463`, iOS 27.0) on macOS 27.0.1 with
Xcode 27.0. The faults are the ones recorded in `opds-ios-check-report.md`. No Android device,
emulator or phone was used. Screenshots are in
`docs/manual-qa-evidence/2026-10-09/ios-catalogue-fixes/`.

Nothing here changes Storyteller, Audiobookshelf, Parrot Cloud or local books.

## 1. Catalogue traffic no longer reaches the iPhone's system HTTP cache

### What changed

`catalogueHttpEngines(platform)` in `lib/opds/implementation` is a new, single seam that every
catalogue HTTP client is built from. `OpdsCatalogueRepositoryFactory` now holds one
`catalogueEngines` and uses it for both places a catalogue client is made: the live session
(`create`, which serves feed pages, search pages, covers and downloaded files) and the check made
when a catalogue is added (`validate`). Nothing else in the app goes through it, so no other
feature's client changes.

- The iOS actual replaces Ktor's `Darwin` factory with one that appends a `configureSession`
  block setting `URLCache = null` and
  `requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData`. The block is appended last, so
  nothing a caller configures can put the cache back. Any factory that is not `Darwin` — a mock
  engine in a test — is handed back untouched.
- The Android actual hands the factory straight back, with the reason written down.

Parrot's own saved pages are untouched. `If-None-Match` and `If-Modified-Since` are request
headers `KtorOpdsTransport` sends from `SavedPagesFeedCache`'s validators, and a 304 still serves
the saved copy; only `NSURLSession`'s own store is gone.

### The test

`CatalogueHttpEnginesTest.catalogueSessionKeepsNothingInTheSystemHttpCache`, in
`lib/opds/implementation/src/iosTest`, whose iOS tests do link. It builds the engine the
catalogue uses, takes the `sessionConfig` block off its `DarwinClientEngineConfig`, and applies
it to `NSURLSessionConfiguration.defaultSessionConfiguration()` — exactly what Ktor's
`createSession` does. It first asserts that the default configuration *does* carry a `URLCache`
and is *not* already on the reload policy, so a passing test cannot be vacuous.

It was committed first, against a pass-through actual (75a673d6), and failed on
`assertNull(session.URLCache)`. It passes with the fix (afb50409).

### Does Android cache catalogue traffic to disk by default? No.

OkHttp keeps no response cache unless a `Cache` is installed on the client, and nothing installs
one: `getHttpEngine()` on Android is a bare `OkHttp` factory, `HttpClientProvider` and
`ServerHttpClientFactory` install only `ContentNegotiation` and `HttpTimeout`, and Ktor's
`HttpCache` plugin appears nowhere in the repo (`grep` for `HttpCache`, `Cache(`, `.cache(`
returns nothing outside tests). So on Android the HTTP layer writes no catalogue page, cover or
book file to disk, and the Android actual has nothing to do.

### Proved on the simulator

Both runs were a clean `simctl uninstall` + `install`, with `Library/Caches` holding 0 files at
install time. The same journey each time: Get books → Project Gutenberg → Popular, ten drag
batches of paging → search → open a book → download it.

**Before the fix** (same build, iOS actual reverted to the pass-through), after only the root
feed and one list:

```
select request_key from cfurl_cache_response where request_key like '%gutenberg%';
  https://www.gutenberg.org/ebooks.opds/
  https://www.gutenberg.org/ebooks/search.opds/?sort_order=downloads
```

**After the fix**, after the whole journey including the download:

- `Cache.db` holds 25 responses, and every one of them is `https://books.retar.si/api/v2/...` —
  Parrot Cloud, through the app's shared client, which is not the catalogue's.
- 0 keys match `gutenberg`, `query=` or `.epub`.
- `grep -r -a -iE "gutenberg|Pride and Prejudice"` over the whole `Library/Caches` tree matches
  nothing. The typed search words are not on disk.
- `fsCachedData` holds 6 files, 1,290,543 bytes: five JPEGs and one JSON body, all Parrot Cloud.
  No EPUB anywhere in the tree.
- The downloaded book exists exactly once: `Documents/library/…_ebook.epub`, 561,102 bytes. The
  "roughly twice the book's size" cost in section 2 of the release checklist is gone.

What is still in `Library/Caches` after the run, and where it comes from:

| Path | Files | Bytes | Whose |
|---|---|---|---|
| `com.retro99.parrot/Cache.db(-wal,-shm)` + `fsCachedData/` | 6 | 1,290,543 | `NSURLSession`'s shared cache, used by the app's **non-catalogue** client (Parrot Cloud book list, positions, covers) |
| `com.retro99.parrot/com.apple.metal`, `com.apple.metalfe`, `com.apple.gpuarchiver` | 7 | 4,094,228 | iOS shader and Metal pipeline caches |
| `com.crashlytics.data/com.retro99.parrot` | 5 | 518,104 | Firebase Crashlytics |
| `google-sdks-events/GDTCORFlatFileStorage` | 3 | 2,104 | Firebase data-transport queue |
| `ebooks/` | 0 | 0 | created by the app, empty |

`Library/HTTPStorages/com.retro99.parrot/httpstorages.sqlite` also exists; that is
`NSURLSession`'s cookie and credential store, not a response cache, and the catalogue transport
installs no cookie plugin.

### Saved pages: conditional requests still work, but Gutenberg stopped being savable

`OpdsSavedPagesTest` (10 tests, including `a saved page is checked again when the catalogue can
be reached`, which asserts the `If-None-Match` header) passes on the Android host and the iOS
simulator.

Separately, and **not caused by this change**: `catalogue_documents` stayed empty through the
whole simulator journey. It is empty on the pre-fix build too, in the same A/B pair, so this is
not a regression. The cause is Gutenberg: `https://www.gutenberg.org/ebooks.opds/` now answers
with `Vary: Accept-Language`, and `CachedOpdsFeedLoader` deliberately refuses to save a
representation keyed on a request header it does not key on
(`vary.any { it != "accept" }` → `cache.invalidate(key)`). So Gutenberg pages are no longer
saved, and the offline "showing the copy saved just now" banner the first run saw for Gutenberg
will no longer appear for that catalogue. The rule is doing what it says; whether `Accept-Language`
should be tolerated — Parrot sends no `Accept-Language`, so the response cannot vary on it — is a
product question, left open here.

## 2. Small sizes

`catalogueMegabytes` became `catalogueSize`, with MB wording unchanged from a megabyte up,
kilobytes below that and bytes below a kilobyte. Units are decimal, as the MB formatting already
was. A size that would round up to "1000.0 KB" is written "1.0 MB" instead.

The "`<so far>` of `<total>`" line had the unit stripped off its first half by hand
(`.removeSuffix(" MB")`), which breaks as soon as the two halves can be in different units. That
is now `catalogueSizeSoFar(bytes, total)`: both halves are written in the total's unit, and only
the total shows it.

One formatter feeds every place the catalogue writes a size, so all of them changed together:

| Place | Before | After |
|---|---|---|
| Book page download button (`CatalogueBookScreen`) | `Download · EPUB · 0.0 MB` | `Download · EPUB · 561.1 KB` |
| "Choose a file" rows and its button (`CatalogueFileSheet`) | `0.3 MB`, `0.6 MB`, `4.7 MB` | `354.1 KB`, `567.8 KB`, `4.7 MB` |
| Book page progress (`CatalogueBookScreen`) | `0.0 of 0.4 MB` | `0.0 of 354.1 KB` |
| Downloads row progress (`DownloadsContent`), 23,330-byte book | `0.0 of 0.0 MB` | `0.0 of 23.3 KB` |
| Downloads row "so far", size unknown | `0.0 MB so far` | `12.3 KB so far` |
| Browse snackbar "so far" (`CatalogueBrowseScreen`) | `0.0 MB so far` | `12.3 KB so far` |
| Downloads failure lines (`DownloadsContent`) | `Too large to add · 0 MB (the limit is 536.9 MB)` | `Too large to add · 23 KB (the limit is 536.9 MB)` |
| Book page and spoken failure lines (`CatalogueBookScreen`, `DownloadAnnouncements`) | `needs 0.0 MB` | `needs 23.3 KB` |

`CatalogueSizeTest` is table-driven: 15 size cases and 9 progress cases, including the
`999,949 → 999.9 KB` / `999,950 → 1.0 MB` boundary, `1 → 1 byte`, and a negative input.

Seen on the simulator: `01-book-page-kilobytes.png` and `02-choose-a-file-kb-and-mb.png`, where
`354.1 KB` and `4.7 MB` sit in the same list.

## 3. A stored http catalogue where http is not allowed — analysis only

No behaviour was changed. This is the product decision the first run surfaced.

`CatalogueHttpPolicy.allowHttp` is one `expect object` with a hard-coded actual per platform:
`false` on iOS, `true` on Android.

### Every place it is applied

All five are in `feature/catalogue/ui`, and all five feed the same single check.

1. `CatalogueSourcesScreen.kt:194` — adding a catalogue from the "Get books" screen, whether by
   preset or by address.
2. `CatalogueSourcesScreen.kt:305` — the second add entry point, `CatalogueAddScreen` with a
   pre-filled address.
3. `CatalogueAddRuntime.kt:84` — `CatalogueAddViewModel`, the same add flow behind a view model.
4. `CatalogueSettingsViewModel.kt:106` → `CatalogueSettingsController.kt:152` — the address
   editor and the account editor of an existing catalogue.
5. `CatalogueAddFlow.kt:134` — the check itself: `if (plainHttp && !allowHttp)` →
   `CatalogueAddDialog.HttpBlocked`, before the address is ever fetched.

Two further places hold the line without consulting the value, and correctly:

- `KtorOpdsTransport.kt:126` refuses Basic credentials over http whatever the per-request flag
  says.
- `KtorOpdsTransport.kt:170` refuses a redirect that downgrades https → http.

### Every place it is not applied

1. **Browsing, searching, covers and downloads of a stored catalogue.**
   `OpdsCatalogueRepository.kt:95` sets
   `allowCleartext = config.baseUrl.startsWith("http://", ignoreCase = true)` — the stored
   address decides, and the platform policy is never consulted. This is the fault the first run
   found: on iOS, with `allowHttp = false`, an http catalogue that is already stored still
   browses and still downloads.
2. **The add-time connection check.** `OpdsCatalogueRepositoryFactory.validate(address, account)`
   builds a repository over the given address and fetches it; the policy is applied only by the
   UI before calling it. Any caller of `CatalogueConnectionValidator` that is not
   `CatalogueAddFlow` would make an unchecked cleartext request. Today the UI is the only caller.
3. **Persistence.** `ServerRegistryImpl.persistCatalogueSources()` writes `ServerConfig` rows
   under `PreferencesKey.CatalogueSources` with no scheme check, so an http catalogue can arrive
   from a backup, a restore or a profile that came from Android and be stored on iOS without ever
   passing the add flow.
4. **The transport.** `KtorOpdsTransport.validate` knows only the per-request `allowCleartext`
   flag. It has no notion of a platform policy, by design.
5. **Presets.** `CataloguePreset` and `CataloguePresetSubmission` do not check the scheme. Every
   shipped preset is https, so nothing is wrong today, but a preset with an http address would
   not be filtered out of the list on a platform that disallows http.

iOS itself does not back the policy up: `iosApp/Info.plist` has no `NSAppTransportSecurity` key
and cleartext still reached both loopback and a private LAN address (section F of the first
report). The value is entirely Parrot's own.

### The smallest change that would make it consistent

Move `CatalogueHttpPolicy` out of `feature/catalogue/ui` into `lib/server/api`, which both
`feature/catalogue/ui` and `lib/server-opds` already depend on, keeping it as the same
`expect object` with the same two actuals. Then one line in `OpdsCatalogueRepository.kt:95`:

```kotlin
allowCleartext = CatalogueHttpPolicy.allowHttp &&
    config.baseUrl.startsWith("http://", ignoreCase = true),
```

That is the whole change. It needs no new error state and no new wording: a refused request
already comes back as `OpdsTransportError.Code.CLEARTEXT_NOT_ALLOWED`, which
`OpdsCatalogueRepository.failure` maps to `CatalogueErrorKind.SecurityPolicy` → the existing
`CataloguePageFailure.NotAllowed` page, and `downloadFailure` maps to
`CatalogueDownloadFailure.Refused`. It closes (1) and (2) at once, because `validate` goes through
the same repository.

It does **not** close (3): a stored http catalogue would still be listed and still be browsable
to the point of the error. Making the list itself refuse it is a second, larger change, and the
question of what a user should see — a catalogue that is listed but cannot be opened, or one that
is hidden, or one the app offers to re-add over https — is the product decision, not this one
line.

## Verification

Tests of every module touched, on both hosts, all passing and none skipped:

| Module | Android host | iOS simulator |
|---|---|---|
| `lib/opds/implementation` | 215/215 | 217/217 |
| `lib/server-opds` | 79/79 | 79/79 |
| `feature/catalogue/ui` | 141/141 | 139/139 |

The iOS count is two higher in `lib/opds/implementation` because the two new tests are
iOS-only; the Android count is two higher in `feature/catalogue/ui` because of its
`androidHostTest`-only tests.

```bash
./gradlew --max-workers=2 :androidApp:assembleDebug \
  :composeApp:linkDebugFrameworkIosSimulatorArm64
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -destination 'platform=iOS Simulator,id=45F57B43-B65F-4F73-AAED-034D5B3DA463' \
  -derivedDataPath /tmp/parrot-dd build
```

All three succeeded. `-Pkotlin.daemon.jvmargs=-Xmx6g` was passed on the command line and was not
needed: nothing ran out of memory, and no project configuration was changed. No existing test had
to be changed, and no test was skipped, ignored or weakened.

## Known problems left behind

- Four failing tests in `feature/books/ui`, the `composeApp` iOS test link error (FirebaseCore),
  and the iOS test sources of `feature/books/domain` and `feature/sync/data` not compiling. Not
  touched.
- The profile-creation crash, the absolute paths in `device_files`, and the Gutenberg row
  subtitles. Out of scope for this run by instruction.
- Gutenberg pages are no longer saved for offline, because of `Vary: Accept-Language`. See
  section 1.
- The throwaway test root CA from the first run is still trusted in that simulator.
