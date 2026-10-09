# Phase 5 (hardening) run report — 2026-10-09

Branch `opds/phase4-screens`. No emulator or device was used and nothing was installed.
The other documents of this run: `opds-test-coverage.md`, `opds-security-review.md`,
`opds-compatibility.md`, `opds-release-checklist.md`, and the README section
"Book catalogues (OPDS)".

```
--- REPORT ---
Branch: opds/phase4-screens

Commits this run (hash + subject, oldest first):
ddf3644a fix(catalogue): drop a preset's search address and list hint when its address moves to another server
a3516451 fix(catalogue): end a list whose next link loops back, and stop auto-loading endless empty pages
8692c4bb fix(opds): read the standard and legacy thumbnail and cover relations of OPDS 1 entries
8059cd3f fix(opds): stop a download that sends more than it declared; stress tests for feed limits and failing transfers
2a586c23 test(catalogue): race matrix for removal, turn-off, account and profile changes in every running stage; twenty downloads at once; disk filling up
abd4c039 fix(catalogue): a catalogue on the internet cannot send unasked requests to local-network devices
afc0962a fix(opds): resolve the address of an OPDS 1 cover like every other link
2977d20f fix(catalogue): a sign-in from a page, a book or Downloads updates the catalogue's status at once
5565d8e3 test(catalogue): signed-in use end to end in the app's graph; a full disk during the move into the library
ced7ab21 chore(catalogue): remove eleven unused strings and merge four that said the same thing
d5019066 chore(catalogue): remove code with no caller
85801037 test(opds): prefixed Atom namespaces, character references, OPDS 1 facet links, an authentication document offered as a page
edca1916 fix(catalogue): an address with no host or an unclosed bracket is refused, not asked as localhost
0fd911ef docs(opds): test coverage against the plan and the security review
6a6e7159 docs(opds): README section, compatibility table, release checklist, plan status for Phase 5
bf8e821a docs(opds): add the address and local-network tests to the coverage table
72c212b7 docs(opds): note the compressed-download check for iPhone
bf35a07e docs(opds): Phase 5 run report, before the final app builds
(then one more commit: this report with the app build results)

Test command(s) run:
Android host, all 23 modules that have host tests, from clean (--rerun-tasks):
./gradlew :base:testAndroidHostTest :base-ui:testAndroidHostTest :composeApp:testAndroidHostTest :feature:auth:domain:testAndroidHostTest :feature:books:data:testAndroidHostTest :feature:books:domain:testAndroidHostTest :feature:books:ui:testAndroidHostTest :feature:catalogue:data:testAndroidHostTest :feature:catalogue:domain:testAndroidHostTest :feature:catalogue:ui:testAndroidHostTest :feature:home:ui:testAndroidHostTest :feature:login:ui:testAndroidHostTest :feature:settings:data:testAndroidHostTest :feature:settings:ui:testAndroidHostTest :lib:database:implementation:testAndroidHostTest :lib:epub:implementation:testAndroidHostTest :lib:opds:implementation:testAndroidHostTest :lib:server-opds:testAndroidHostTest :lib:server-storyteller:testAndroidHostTest :lib:server:api:testAndroidHostTest :lib:server:implementation:testAndroidHostTest :lib:user:implementation:testAndroidHostTest :tools:ember-fixtures:testDebugUnitTest :lib:database:implementation:verifySqlDelightMigration --continue --rerun-tasks --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx6g
iOS simulator, the same modules plus the five that have an iOS test task and no Android one:
./gradlew :<module>:iosSimulatorArm64Test for each of the 28 modules, --continue --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx6g -Dorg.gradle.jvmargs="-Xmx8g -XX:+UseParallelGC -Dfile.encoding=UTF-8"

Per module: <module> Android host <passed>/<total>, iOS <passed>/<total>
base Android host 13/13, iOS 11/11
base-ui Android host 7/7, iOS 7/7
composeApp Android host 57/57, iOS not run (known: test binary does not link, framework FirebaseCore not found)
feature:auth:domain Android host 8/8, iOS 8/8
feature:books:data Android host 121/121, iOS 113/113
feature:books:domain Android host 60/60, iOS not run (known: iOS test sources do not compile)
feature:books:ui Android host 67/71, iOS 67/71 (the four known failures, both platforms: LinkPickerViewModelTest 2, LinkReviewViewModelTest 2)
feature:catalogue:data Android host 107/107, iOS 105/105
feature:catalogue:domain Android host 31/31, iOS 31/31
feature:catalogue:ui Android host 139/139, iOS 137/137
feature:home:ui Android host 84/84, iOS 90/90
feature:login:ui Android host 45/45, iOS 45/45
feature:settings:data Android host 1/1, iOS 1/1
feature:settings:ui Android host 20/20, iOS 20/20
lib:database:implementation Android host 136/136, iOS has no tests
lib:epub:implementation Android host 45/45, iOS 30/30
lib:opds:implementation Android host 215/215, iOS 215/215
lib:server-opds Android host 79/79, iOS 79/79
lib:server-storyteller Android host 35/35, iOS 35/35
lib:server:api Android host 44/44, iOS 44/44
lib:server:implementation Android host 34/34, iOS 34/34
lib:user:implementation Android host 6/6, iOS 6/6
lib:database:api, lib:epub:api, lib:opds:api, lib:preferences:api, lib:user:api, translations: no tests on either platform
tools:ember-fixtures: no unit tests (task reports NO-SOURCE)
No test was skipped on either platform.

composeApp host suite result: 57/57 passed

Migration verification result: :lib:database:implementation:verifySqlDelightMigration and :verifyCommonMainAppDatabaseMigration passed

App build results (Android assemble, iOS framework): :androidApp:assembleDebug BUILD SUCCESSFUL; :composeApp:linkDebugFrameworkIosSimulatorArm64 BUILD SUCCESSFUL. Also :tools:ember-fixtures:compileDebugKotlin, which uses the catalogue strings: successful. Run on bf35a07e, after the last code change (edca1916).

Steps complete (1-6): 1 yes, 2 yes, 3 yes, 4 yes, 5 yes, 6 yes

Test-plan lines that were missing a test, and what I added:
Namespace prefixes: Opds1SyntaxCoverageTest (prefixed Atom reads the same; elements are matched by name whatever their namespace, which is a pinned limitation).
Character references: Opds1SyntaxCoverageTest.
OPDS 1 facets: Opds1SyntaxCoverageTest pins that they are not read and do not disturb the page. OPDS 1 facets are not implemented.
An OPDS authentication document offered as a page: Opds1SyntaxCoverageTest.
Limits at the limit, not only over it: FeedLimitsStressTest (items, bytes, nesting, both formats, all at once).
Credentials absent on cross-origin feed links and on cross-origin search: CatalogueAccountReachTest.
Navigation and pagination loop safeguards: CataloguePagingLoopTest. There was no safeguard; see bugs.
Profile deleted, and every source event during checking and adding: AcquisitionRaceMatrixTest.
Disk full during the move into the library: AcquisitionQueueStressTest and CatalogueLibraryDiskFullTest (real graph).
More bytes than declared: TransportFailureStressTest.
Saved-pages eviction across more than one batch, and the budget after every save: SavedPagesFeedCacheTest.

Test-plan lines still without a test, and why:
Optional checksum mismatch: no checksum is read from a catalogue in this release, so there is nothing to test.
Adding OPDS cannot break series, link suggestions and progress aggregation: only the shared guard is tested (RepositoryCapabilityTest). No test inside those modules registers a catalogue. Left for the device checklist.
OPDS 2 manifests with a reading order: no fixture; they are not a supported acquisition and are classed by media type only.
Everything under "UI and device QA": needs a device. Listed in docs/opds-release-checklist.md.
TLS errors and the 30-second stall limit: tested with simulated errors only; the real engines were not run.

Stress and failure tests added (one line each), and any that found a bug:
FeedLimitsStressTest: item, byte and nesting limits at the limit and one past it for Atom and JSON, all limits at once, nesting at depth 100,000, 2,000 inline thumbnails. FOUND: OPDS 1 thumbnails were not read as pictures.
CatalogueInlineThumbnailStressTest: 2,000 small inline thumbnails, as many at the 256 KiB ceiling as fit a page, one byte over, a page over budget, pictures that are not pictures.
TransportFailureStressTest: stall (timeouts set, engine timeout, cancel), cut mid-file with and without a declared length, more bytes than declared, endless body for a file and for a page, redirect loop and endless redirect chain, redirect off the origin and back with account details, same host on another port, 429 and 503 with Retry-After in seconds, as a date, huge, negative and garbage for pages and files, addresses with no host. FOUND: more bytes than declared were written in full; an address with no host was asked as localhost.
CataloguePagingLoopTest: next link to itself, next link back to an earlier page, a redirect back to an earlier page, a loop after the first pages were dropped, endless empty pages. FOUND: the browser followed such links without end.
AcquisitionRaceMatrixTest: catalogue removed, turned off, account changed, profile switched, profile deleted, each during downloading, checking and adding, plus all at once. No bug.
AcquisitionQueueStressTest: twenty downloads at once each asked twice with a restart in the middle; twenty that fail in every way; disk full at six points of a download; disk full during the move into the library. No bug.
SavedPagesFeedCacheTest (two added): a page larger than the room left, across eviction batches; the budget after each of 600 saves. No bug.
CatalogueLibraryDiskFullTest (real graph): full disk during the move, nothing left behind, retry adds the book once. No bug.

Security review: each question, the answer, and anything fixed:
1. Can account details reach another host? No. One rule in the transport (same scheme, host and port, https, never after leaving the origin), now tested for redirect, image, download link, search template, search description, link in a feed, address edit and http. Fixed: a preset's search address stayed attached after its address moved to another server (search text, not account details, would have gone to the old host).
2. Can another profile's or catalogue's data be read? No path found. Credentials, saved pages, cached pictures, queue rows, staged files and page references are each scoped and tested.
3. Unbounded memory, CPU or disk; writing outside staging and library; a non-book in the library? Memory, disk and paths are bounded and tested. Fixed: endless paging; a download longer than declared written in full; a catalogue on the internet making Parrot ask local-network devices for pictures, search descriptions and files without asking; an address with no host asked as localhost. A non-EPUB cannot reach the library: the file is checked by its bytes.
4. Do logs, analytics, breadcrumbs, routes or saved state hold addresses, queries, search text, titles, user names or passwords? No. Catalogue modules contain no log or analytics call; the transport's four log lines are fixed words and have no receiver; routes hold an id and a reference. redactAddress() has no caller because nothing logs an address. Fixed: the add check could let an exception carrying the address escape. Stored by design, not logged: download rows hold title, author and cover address; a saved search page is stored under its address, which contains the search text.
5. Are account details excluded from backup and from Parrot Cloud sync? Android: yes, the encrypted settings file is excluded from cloud backup and device transfer. iPhone: not excluded by Parrot; the keychain item is not "this device only", so it travels in an encrypted backup. Parrot Cloud: nothing catalogue-related is synced. Not fixed, reported: the iPhone keychain setting, and the profile database (saved pages, download rows, provenance) being in Android's backup.

Security items I could not verify:
That Android's backup rules behave as written on a device.
The iPhone keychain's behaviour in a real backup and restore.
How large a decoded picture can get: bytes are limited (4 MiB), width and height are not.
A host name that resolves to a local address is not recognised as local; only numeric addresses and localhost, .local, .lan, .localhost are.
The 30-second no-bytes limit and real TLS failures on the platforms' network engines.
Whether the iPhone's system HTTP cache stores catalogue responses on disk.
Whether iOS unpacks a gzip answer while reporting the packed length, which would fail a download as a length mismatch.
Anything on an iPhone beyond tests in the simulator.

Signed-in end-to-end test: what it covers and its result:
CatalogueSignedInEndToEndTest, composeApp Android host, the app's real Koin graph with MockEngine. Passes; run five times in a row without a failure. Steps: add by address answers 401 Basic and nothing is registered; wrong details are refused and nothing is saved; right details add the catalogue; browse the first page; open a folder; load a cover through the app's image path; search through an OpenSearch description; download a book whose file is on another host; the book is in the library with provenance by origin only; the row says In your library; remove the account details (saved pages go, the catalogue and book stay); the next request is anonymous and the sign-in sheet opens; sign in again from the sheet with wrong then right details. Every request is recorded: the file host got no account details and no cookie, the wrong password went out once per attempt and only to the catalogue, nothing went over http, and no error was reported to analytics. It runs for Android only: the composeApp iOS tests do not link.
It found two bugs: relative OPDS 1 cover addresses were never resolved, and a sign-in from the sheet left the status at "Sign in needed".

Dead code removed:
Nine DAO methods with no production caller: CatalogueAcquisitionsDatabase interruptRunning, deleteAll, observeAll; CatalogueBookSourcesDatabase getForPublication, observeForSource, moveToBook, deleteForBook, deleteAll; CatalogueDocumentsDatabase deleteAll. Six SQL queries only they used.
Helpers: OpdsLink.acquisitionRelations and filterToRelations, hasPagination on the pagination model, OpdsMediaType.hasProfile, CatalogueAccessProvider.observeAll, the interface CatalogueAddCompletion.
The stray file lib/opds/implementation/.../detect/placeholder.kt (it went into the strings commit ced7ab21 by mistake, not the dead-code commit).
Strings: eleven unused catalogue strings removed; four pairs with the same text merged into one each.
The placeholder screens file no longer exists; the two screen files in its place are used.
Kept on purpose: redactAddress() (no caller, asked for by the plan), CatalogueLibraryLookup.libraryBookFor and CatalogueFetchStatus.isSavedCopy (used by tests only).

Documents written:
docs/opds-test-coverage.md, docs/opds-security-review.md, docs/opds-compatibility.md, docs/opds-release-checklist.md, docs/opds-phase5-report.md, README section "Book catalogues (OPDS)", plan status line and Phase 5 status.

Bugs found and fixed this run:
1. A next link pointing at its own page or an earlier one was followed without end.
2. OPDS 1 thumbnail and legacy cover relations were not read, so such entries had no cover.
3. OPDS 1 cover addresses were kept as written, so a relative cover address could not be loaded.
4. A download that sent more than it declared was written in full before being refused.
5. A catalogue on the internet could make Parrot request pictures, search descriptions and files from local-network devices without asking.
6. A sign-in from a page, a book or Downloads left the catalogue's status at "Sign in needed".
7. A preset's search address and list hint stayed attached after its address moved to another server.
8. An address with no host was asked as localhost; one with an unclosed bracket was read past it.
9. The add check built its transport outside its error handling.

Bugs found and NOT fixed, with why:
OPDS 1 facets are not read: a feature that was left out in Phase 1, not a regression; building it changes screens that cannot be checked without a device.
OPDS 1 elements are matched by name and not by namespace: changing it risks feeds that omit the namespace and that work today; pinned by a test and documented.
iPhone keychain items are not "this device only": the same store holds every server token; changing it signs everyone out and needs a device.
Catalogue tables are in the profile database that Android backs up: needs a separate database, a schema change.
A possible iPhone-only length mismatch on compressed downloads: not reproduced, from reading the code; in the release checklist.
Decoded picture size is not limited: needs a device to measure.

Tests skipped, ignored or weakened (file + name + reason), or "none":
None skipped or ignored. Two removed with the dead code they tested: lib/database/implementation/.../CatalogueDaosTest.kt "interrupting touches only downloading, checking and adding" (tested interruptRunning, which had no caller; the queue interrupts row by row and that is tested in AcquisitionQueuePipelineTest) and "provenance follows a merged book and goes with a deleted one" (tested moveToBook and deleteForBook; the real merge and delete paths are tested by two other tests in the same file). Two assertions on getForPublication were removed from "one library book can have several provenance rows".

Existing tests that had to change, and why:
lib/opds/implementation/.../Opds1ParserTest.kt: acquisition_feed_cover_images_surface_the_model_entry_and_link and standalone_full_entry_cdata_content_and_root_xml_base_resolution asserted the unresolved cover address ("/covers/verses-1.png", "treatise.png"); they now assert the resolved one. That was the bug.
lib/database/implementation/.../CatalogueDaosTest.kt: as above.
Test fakes lost the overrides of removed DAO methods: QueueTestSupport.kt, SavedPagesTestSupport.kt.
composeApp/.../RealAppGraph.kt: one optional parameter, to simulate a full library disk.

Files changed outside the catalogue modules, docs and README:
lib/server/implementation: ServerRegistryImpl.kt (preset hints), CataloguePresetHintsTest.kt (new)
lib/server/api: CatalogueAccessProvider.kt (unused method removed)
lib/database/api and lib/database/implementation: the three catalogue DAO interfaces, CatalogueSqlDelightDaos.kt, three .sq files (queries removed, no schema change), CatalogueDaosTest.kt
translations: strings.xml (fifteen catalogue entries removed)
composeApp: three test files only

Known problems I am leaving:
The four you listed, untouched: the failing tests in feature/books/ui, the composeApp iOS test link error, the iOS test sources of feature/books/domain and feature/sync/data, the profile creation crash, and clearAllData() doing nothing on sign-out.
Five changes of this run alter what a device shows and have not been seen on one: covers from thumbnails, relative cover addresses, list ends on a looping next link, no local-network pictures for a catalogue on the internet, status after sign-in.
Where I read your instruction more widely than written: the preset's search address and list hint are cleared when the scheme or the port changes too, not only the host. A path or query change keeps them.
"Every catalogue string exists only once" is true among the catalogue strings. About thirty of them repeat the text of a string of another feature ("Try again", "Cancel"), as every feature in this app does; I left those.
--- END REPORT ---
```
