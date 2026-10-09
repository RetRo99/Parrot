# Phase 4: generated covers and book pages

Recorded before the final application builds, then updated with their results.

```text
--- REPORT ---
Branch: opds/phase4-screens, continuing from deb13f8d; no push.

Commits this run (oldest first):
c9b40c80 feat(catalogue): generate title covers for missing artwork
db772754 fix(opds): retain subjects and verify accounts without persistence
cd41d6bc fix(catalogue): verify sign-in and refresh library status on return
e2999da9 feat(catalogue): model live book-page actions and file choices
9c37a078 fix(catalogue): verify credentials against protected searches
974ca16d fix(catalogue): load full entries and keep book actions current
86b95e09 feat(catalogue): show live book pages and file choices
200a6bff docs(catalogue): record book-page results before app builds
c7b0e01a test(fixtures): add catalogue book states and grouped file sheets
75753dc9 test(catalogue): capture book pages and title covers in Ember themes
65f7730d fix(opds): preserve declared Atom acquisition file sizes
76714148 test(catalogue): record real EPUB acquisition and reader evidence
c089db7f docs(catalogue): record device QA and live-feed size fix
0c8ad476 test(catalogue): verify live file sizes and retained selection
Plus this report-finalization commit.

Test command(s):
./gradlew :base-ui:testAndroidHostTest :lib:opds:api:testAndroidHostTest :lib:opds:implementation:testAndroidHostTest :lib:server:api:testAndroidHostTest :lib:server-opds:testAndroidHostTest :feature:catalogue:domain:testAndroidHostTest :feature:catalogue:data:testAndroidHostTest :feature:catalogue:ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :tools:ember-fixtures:testDebugUnitTest :tools:ember-fixtures:assembleDebug :base-ui:iosSimulatorArm64Test :lib:opds:api:iosSimulatorArm64Test :lib:opds:implementation:iosSimulatorArm64Test :lib:server:api:iosSimulatorArm64Test :lib:server-opds:iosSimulatorArm64Test :feature:catalogue:domain:iosSimulatorArm64Test :feature:catalogue:data:iosSimulatorArm64Test :feature:catalogue:ui:iosSimulatorArm64Test :feature:home:ui:iosSimulatorArm64Test --max-workers=2 --continue -Pkotlin.daemon.jvmargs=-Xmx6g
./gradlew :feature:catalogue:ui:testAndroidHostTest :lib:server-opds:testAndroidHostTest --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx6g
./gradlew :lib:opds:implementation:testAndroidHostTest --max-workers=2
./gradlew :lib:opds:implementation:testAndroidHostTest :lib:opds:implementation:iosSimulatorArm64Test :lib:server-opds:testAndroidHostTest :lib:server-opds:iosSimulatorArm64Test :feature:catalogue:ui:testAndroidHostTest :feature:catalogue:ui:iosSimulatorArm64Test --max-workers=2 --continue -Pkotlin.daemon.jvmargs=-Xmx6g
Test-first red runs and subsequent green runs are retained in the session's temporary logs; the final combined command passed.

Per module (passed/total, Android host; iOS simulator):
base-ui: 7/7; 7/7
lib/opds/api: no tests on either target; compiled.
lib/opds/implementation: 181/181; 181/181
lib/server/api: 42/42; 42/42
lib/server-opds: 54/54; 54/54
feature/catalogue/domain: 31/31; 31/31
feature/catalogue/data: 97/97; 95/95
feature/catalogue/ui: 91/91; 90/90 (one Android-only source-safety test)
feature/home/ui: 81/81; 87/87
translations: no tests; compiled for both targets.
tools/ember-fixtures: unit-test task has no sources; APK assembled.
Zero failed or skipped test cases in these results.

App build results: ./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64 --max-workers=2 passed twice, including after the live-feed Atom-size fix. Android APK installed and checked on emulator-5554. No iOS OOM/retry; no heap configuration change.

Steps complete (1–4): 1 generated covers; 2 book-page presentation; 3 live actions and browser/sign-in fixes; 4 Choose a file.

Fixtures captured:
detail, detailWait, detailWaitOne, detailDl, detailUnknown, detailIos, detailAdding, detailDone, editions, editionsGrouped: Day, E-ink, Night.
blocked (Sold), blockedSubscription, blockedBorrow, blockedSample, blockedFormat, blockedProtected: Day, E-ink, Night (Night is extra where no board exists).
Browser covers refreshed: browse, offline, localNet, list, listFailed, search, filter, editionsList: Day, E-ink, Night.
PNG and UI hierarchy pairs are in design/screens/catalogue-<view>-<theme>.*. Board captures used emulator-5554 only.
After explicit user authorization, the same 16 book fixtures were additionally captured in all three themes on Samsung SM-S921B, serial RFCWC0SSVDM, in the demo package com.retro99.parrot.fixtures. Production phone app/data were not touched. Physical captures and interaction results are in the session's temporary catalogue-samsung-demo directory.
Real-book Done, reader chapter, and browser-return evidence is committed as design/screens/catalogue-realBook-*.

Boards not captured: detailUpdate deliberately deferred. No sample-download board/action. Other Phase 4 boards outside this book-page scope were not re-captured.

Differences found/fixed/left:
Fixed blank/placeholder missing-artwork covers with stable title covers, reduced generated-cover font size to avoid splitting Frankenstein, preserved subjects through both protocol parsers, and removed description clamping.
Fixed sign-in saving before verification, challenged-search verification, stale library rows on return, late sign-in after profile switch, initial misleading Download actions, partial/full-entry loading, library status after finished queue rows are purged, and ignored Atom file-length attributes discovered in the live Gutenberg feed.
Left: emulator dimensions differ from boards, so wrapping and sheet height differ. Day sheet uses the existing EmberBottomSheet scrim, lighter than the board. Grouped sheet's underlying button truthfully shows the selected 4.8 MB file, rather than the board's unchanged 1.2 MB background.

Each book-page state:
detail: title cover/artwork, title, supplied author/language/year/subjects, Download · EPUB [· size], Other files count, full sanitized description, From, conditional Rights and law note.
detailWait: Waiting to download… / Two other books are downloading. This one starts next. / Cancel. Exactly one: Another book is downloading. This one starts next. Zero running others omits that explanation.
detailDl: Downloading…; 0.5 of 1.2 MB; determinate Ember bar; Cancel; Android leave-screen note.
detailUnknown: Downloading…; 1.4 MB so far; no progress bar; Cancel.
detailIos: bold Keep Parrot open until this finishes. iPhone pauses downloads soon after you leave the app.
detailAdding: Adding to library…; Checking the file and preparing it for reading.; no Cancel.
detailDone: ✓ In your library [· downloaded <when>]; Read now opens the local library UUID; provenance keeps the date after queue removal.
blocked: six reasons below; no acquisition attempted.
failures: existing connection/storage/refused Retry, too-large/invalid/protected Dismiss, interrupted Start again, sign-in Sign in mappings; no new failure copy.
Load failure: existing browser offline/rate-limit/page-failure presentation and recovery; absent route reference returns to the catalogue root.
editions/editionsGrouped: catalogue labels wrap without clamp; missing labels EPUB file N; groups use edition label or Edition N; one Best; unsupported radios disabled and dimmed only; selected size controls Download.

Each blocked reason/text/button:
Sold: Can't be downloaded here / This book is sold on <Provider>'s site. Parrot can only add books that the catalogue lets you download. / Open provider page only with a web link.
Subscription: Can't be downloaded here / This book is part of a subscription on <Provider>'s site. Parrot can only add books that the catalogue lets you download. / Open provider page only with a web link.
Borrow: Can't be downloaded here / This book can be borrowed on <Provider>'s site, but not downloaded here. Parrot can only add books that the catalogue lets you download. / Open provider page only with a web link.
Sample only: Only a sample is available / The catalogue offers only a sample of this book. / Open provider page only with a web link; never Download sample.
Format: Can't be opened in Parrot / This book is only available as <format>, which Parrot can't open. Unknown type: This book is only available in a format Parrot can't open. / No button.
Protected: Can't be opened in Parrot / This book's file is protected (DRM), so Parrot can't open it. / No button.
Priority: sample, sold, subscription, borrow, protected, format. A complete openable EPUB suppresses the card. Provider name falls back to catalogue name.

Real-book emulator result: PASS on emulator-5554 using the built app. Added https://www.gutenberg.org/ebooks/120.opds as an anonymous catalogue, opened the complete no-images edition of Treasure Island, tapped Download, observed Downloading Treasure Island with View, then In your library with Read now. Read now opened the existing local reader, rendered the cover and chapter one (Page 1 of 10, 3%). Back retained the completed page/date; returning to the list refreshed its In your library status. After installing the refreshed APK, the illustrated edition showed Download · EPUB · 49.5 MB; choosing its other EPUB showed 49.4 MB and retained that selection after closing the sheet. No sample was downloaded. The illustrated edition was not acquired.
Samsung demo interactions: PASS for disabled Kindle radios, unknown-size Download, long-label second-file Download · 0.4 MB, cross-edition Download · 0.6 MB, and exactly one Best. These are presentation/selection checks, not physical-device acquisition claims.

TODO-design strings: none added; existing exact strings reused. No invented failed-download wording.

Done: shared Ember generated-cover tokens; subjects; verified ephemeral accounts without saving or access-status updates; book-page logic/UI/route; live queue and refreshed library actions; file choice; browser return refresh; protected-search sign-in; required fixtures and platform tests.

Not done/partly: detailUpdate and sample acquisition intentionally deferred. Downloads remains the existing placeholder destination; View opens it, but its transfer list is not built in this run. Catalogue settings links still open Libraries, as in the browser. Full Phase 4 acceptance gate (anonymous/Basic custom servers and provider QA on both devices) is not claimed.

Tests skipped/ignored/weakened: none. No-source tasks are not skipped test cases. Known unrelated books/ui failures, composeApp iOS FirebaseCore test link, books/domain and sync/data iOS test-source compilation were not rerun or altered.

Existing tests changed: feature/catalogue/ui/src/commonTest/kotlin/com/retro99/catalogue/ui/browse/CatalogueBrowserTest.kt: a_page_that_needs_an_account_opens_the_sign_in_sheet_and_reloads_after_signing_in and wrong_details_keep_the_sheet_open_and_closing_it_leaves_the_page now require no saved credentials before successful verification. BrowseTestSupport.kt's fake gateway gained temporary page/search verification. Browser return-refresh and book-page/profile-switch regressions were added. base-ui host tests were enabled rather than bypassed.

Ambiguities: full-entry alternates are followed on the book page, never while populating a list. No running downloads means no invented waiting-count sentence. Unsupported file size stays in accessibility text but its visual subtitle follows the board. One grouped default spans all editions. Existing Downloads and Libraries destinations are retained rather than expanded.

Files outside allowed directories: required supporting changes in base-ui (Ember cover slot/tokens and host-test enablement), lib/opds/api and implementation (subjects, Atom lengths, models/parsers/tests), lib/server/api and lib/server-opds (subjects and non-persisting account verification/tests), feature/home/ui (existing route callbacks). No website changes or heap configuration change; .opencode/ remains untouched and untracked. Physical-device work was confined to the demo app after the user authorized the Samsung.

Known problems: sign-out clearAllData() no-op and the previously identified unrelated test/link/compiler failures remain excluded. Emulator disconnect/package-service failures required emulator restarts; UIAutomator occasionally returned a null root during cold launch. Kotlin's default 3 GB daemon hit GC overhead during earlier fixture compilation; test/fixture runs used CLI-only -Xmx6g. Both final app builds passed again after the Atom-size fix.
--- END REPORT ---
```
