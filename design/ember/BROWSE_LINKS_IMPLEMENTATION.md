# Browse / links / positions implementation

Approved order: **A1 → B → C → A2**, separate commits.

## A1 — Series-only Browse

- The displayed tab label is Browse. Internal tab/destination names and `series` analytics remain unchanged.
- Series list/detail now use Ember, persistent list search, grouped detail rows, real progress,
  fractional/unnumbered memberships, accessible row descriptions, contextual states and retry.
- `ObserveSeriesBrowseUseCase` replaces the legacy Series UI data path. It retains source-qualified
  catalogue/membership provenance and source failures. Last successfully loaded source data survives
  a subsequent failed refresh within the observer's lifetime.
- Linked identity is grouped before inspecting memberships. Library primaries do not lose external
  series membership. Counts and aggregate progress include each linked book once.
- Progress uses the current/recent primary version's local-first position, not the furthest version.
- Refresh cancels the previous collector and its progress fetch; content remains visible.
- E-ink uses outlines and textual loading/refresh feedback, without search/list transitions or spinners.

### Isolated verification profile

`tools/ember-fixtures` is a separate, debug-only APK (`com.retro99.parrot.fixtures`). It has no
network permission, production Application, database, account or sync workers. Its in-memory
fixtures reuse production aggregation and screen content. No production APK is installed for captures.

Fixtures cover same-named series from Storyteller/ABS, linked/unlinked versions, a book in multiple
series, fractional and missing numbers, finished/partial/unstarted progress, empty and offline UI.
The offline flag is presentation injection, not a live server/network test. Repository failure and
last-known data retention are tested separately in reader-domain host tests.

Commands:

```
./gradlew :androidApp:assembleDebug :tools:ember-fixtures:assembleDebug
./gradlew :feature:books:domain:testAndroidHostTest :feature:reader:domain:testAndroidHostTest
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
python3 tools/ember-fixtures/capture.py --serial emulator-5554
```

With the owner's explicit authorization, the separate fixture APK was also installed on the
Xiaomi. Its real Parrot package, profile, servers, links and positions were not modified.
Captures are under `design/ember/verification/a1-xiaomi/` (Day and E-ink list/detail/offline,
plus Day empty). No grayscale conversion is used. The E-ink empty capture was discarded
when the phone switched apps; no further theme runs were made after the owner's instruction.

```
python3 tools/ember-fixtures/capture.py --serial 10.41.65.3:5555 --allow-device --output design/ember/verification/a1-xiaomi
python3 tools/ember-fixtures/smoke.py --serial 10.41.65.3:5555 --allow-device --output design/ember/verification/a1-xiaomi/smoke.txt
```

Books-domain and reader-domain host suites: 218 tests, no failures. Android production and
fixture debug APKs and the iOS simulator debug framework build successfully.
The Xiaomi fixture smoke checks pass for submit-only search, search restoration after actual
process death, detail Back preserving the query, and scroll restoration after process death.

### Deferred / limits

- **B:** link confirmation, whole-group validation, decision-only Undo and identifier-backed bulk outcomes.
- **C:** position redesign and seeded stale/approximate/unmatched positions. Endpoint reconciliation
  stays deferred; the first pass must point conflicting phone/server pairs to Book details.
- **A2:** author identity, structured memberships, author routes/links, and Series | Authors switch.
- Portable link keys and existing UUID-keyed position storage are unchanged. Supporting colliding
  upstream book UUIDs across server instances requires identity/storage migration, not this UI pass.
- Last-known Series source data is in-memory, not a new durable offline catalogue.
- Fixture screen captures are not evidence of production navigation/DI or live server networking.

## B — partial implementation

- Picker and suggestion prevalidation now checks both complete link groups, including members
  not present in the current catalogue.
- Local link/separate writes carry an expected membership snapshot; SQL checks it inside the
  write transaction, before any membership, decision or outbox changes. A concurrent sync change
  refuses the stale edit rather than overwriting it. The check is conservative across all live
  groups; an unrelated membership change also requires retry.
- ISBNs require valid checksums (and ISBN-13 prefixes); ASINs require ten ASCII alphanumeric
  characters. Identifier labels reflect verified shared data; bulk eligibility requires shared
  ISBN, never title-match score or ASIN alone.
- Bulk failures retain both titles and a reason rather than silently counting only successes.
- Manual picker selection opens a confirmation sheet, with a disabled textual Linking state
  and inline failures. This is not yet the final tile/layout design.
- A new linking catalogue stream exposes source errors and retains last-successful books
  during failed emissions. The picker and review use it, with retry. Retention is observer-local,
  not durable caching; retry starts a fresh observer.
- Remaining to complete B: full Ember picker/review layout and grouping, accessible
  version tiles, review/bulk confirmation and busy guards, return-to-detail Linked snackbar,
  decision-only Undo, separation confirmation/failure handling and unused section removal,
  isolated linking fixtures/captures and final Android/iOS verification.
- Decision Undo requires a sync-compatible clear/tombstone: the current Cloud mutation and
  table accept only `never`/`skip`, and LWW uses `decided_at`. Backdating Skip or just deleting a
  local row would not safely undo a synced decision. Do not use either workaround or deploy
  migrations against the user's real server during fixture verification.
- Android debug APK and iOS simulator framework build successfully; books-domain,
  books-data and database host tests pass. With explicit authorization, the real Parrot debug
  APK was installed on the Xiaomi using `adb install -r` and launched for owner testing,
  without clearing app data. Linking actions in that build affect the real library. No B
  fixture captures or complete end-to-end verification are claimed yet.
