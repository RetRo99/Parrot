# Prepared chapters in Parrot Cloud: the brief

This is the standing specification. Each agent run gets a short prompt that says where
the work stands; everything else is here. The running record of what has been built is
`docs/tts-prepared-cloud.md`, which the first run creates.

## What this is

Prepared chapters exist on the phone (`docs/tts-prepared-chapters.md`, read it first,
especially "For the cloud backup run"). This work backs a prepared chapter up to Parrot
Cloud, counts it against the account's storage like any other file, and lets another
Android device download it instead of generating it again.

Also read: `docs/parrot-cloud-book-file-transfer-implementation-plan.md` (how book files
are uploaded, restored, deleted and taken down), `supabase/SECURITY_ROLLOUT.md`, and
`docs/parrot-cloud-abuse-runbook.md`.

## Decided by the owner, final

- Prepared audio is ordinary stored data. It counts against the same allowance as books
  (200 MiB by default). No separate cap for audio.
- Upload follows the existing auto-backup switch, on Wi-Fi only. No new setting.
- Books are uploaded before audio. Nothing is evicted automatically. When the allowance
  is full the upload stops with a clear message that offers to manage storage.
- Other devices download a prepared chapter only on request, per chapter.
- Audio never outlives its book in the cloud: deleting a book's backup, a takedown, a
  blocked book and account deletion all remove its prepared audio.
- Deleting a prepared chapter from the row removes it from this device and from the
  cloud; the confirmation says so.
- Paid storage comes later as its own project. Do not build purchasing. Do not show a
  button that does nothing. Leave one clearly named place in the code where a "get more
  storage" action will be attached.
- Android only. iPhone must not change in behaviour and must not break when the cloud
  holds prepared audio.

## The intended design, to be checked before anything is built

`cloud_book_files` already allows several files per book: it is unique on
`(cloud_book_id, media_type, relative_path)`. A prepared chapter should be one more file
of its book, with its own media type, uploaded through the existing reserve, upload,
finalize, download and delete path. That gives quota accounting, resumable upload, audit
and garbage collection without new machinery.

If reading the server code shows this does not hold (for example the RPCs assume one
file per book, or takedown is tied to a single file), stop after Step 0 and report what
blocks it. Do not invent a parallel upload system without the owner's agreement.

## Rules for every run

- Tests first for all logic: write the test, see it fail, then build. Each step ends with
  a commit and green tests.
- Keep `docs/tts-prepared-cloud.md` current and commit it at the end of each step.
- Small commits. Never `git add -A`; add files by explicit path. Never discard work.
- Server changes are additive: a new migration file with a later timestamp. Never edit an
  existing migration. Every server change comes with pgTAP tests in `supabase/tests/`.
- Nothing is deployed by an agent. The owner applies migrations by hand. Never run
  anything against the hosted project, never use production credentials, and do not
  put any key or account email in the repository.
- Existing behaviour for books (upload, restore, delete, takedown, quota) must not
  change. Its tests must pass unedited; if one has to change, stop and explain why.
- No new libraries. The worktree has no `local.properties`: pass
  `ANDROID_HOME=$HOME/Library/Android/sdk` on the Gradle command line.

## Steps

### Step 0: read, then write the design down

Read the migrations that define `cloud_book_files`, `cloud_book_uploads`,
`cloud_user_storage`, `reserve_book_upload`, `finalize_book_upload`,
`create_book_download`, `delete_book_file`, `admin_takedown_book_file`,
`get_storage_usage`, the orphan garbage collection, account deletion and the storage
bucket policy (allowed MIME types and size limits). Read the client:
`lib/server-parrot-cloud/` (book file service, transports, change applier) and
`feature/books/data/.../transfer/BookFileTransferEngine.kt`.

Write `docs/tts-prepared-cloud.md` with the answers, each with file and line:

1. Can a prepared chapter be one more `cloud_book_files` row? What has to change for a
   new media type: allow-lists, the bucket's MIME types, size limits, the rights
   attestation.
2. What happens today to a book's other files when its ebook file is deleted, taken
   down, blocked by content hash, or the account is deleted? What must be added so
   prepared audio always goes with it?
3. How does a device learn which files a cloud book has (the sync payload and the change
   applier)? What does the CURRENT app, on Android and on iPhone, do when it meets a file
   with a media type it does not know? This is the main compatibility risk: an older app
   must not mistake prepared audio for the book, try to download it as one, or crash.
4. How is a book identified across two devices, and how does that map to the local
   prepared store's folder for a book (it hashes server id and book id)?
5. Uploads are allow-list only today (`cloud_feature_allowlist`). Prepared audio follows
   the same gate.

If answer 1 is no, or answer 3 shows an older app would misbehave and it cannot be
prevented from the server side, stop here.

### Step 1: the server

One new migration and its pgTAP tests, written first:

- the new media type is accepted by the reserve and finalize path, with a size limit of
  64 MiB per chapter file, and only for a book the caller owns that has an available
  ebook file;
- quota: a prepared chapter is reserved, committed and released exactly like a book
  file, and `quota_exceeded` is returned with the used and total bytes;
- row level security: one account can never see, download or delete another's;
- cascade: deleting the book's backup, an admin takedown, a block-list hit and account
  deletion each remove the book's prepared audio and release its bytes; test each;
- garbage collection treats an orphaned prepared-audio object like any other;
- storage usage returns the breakdown the app needs: bytes used by books and bytes used
  by prepared audio, without breaking the existing response shape;
- older apps: whatever Step 0 found, make sure the payload an older app receives is one
  it handles. If that needs prepared audio to be left out of the existing payload and
  served by a new call, do that.

Run the suite with `scripts/supabase/test.sh` (local Supabase; needs Docker). If the
local stack cannot be started, write the migration and tests anyway, say plainly in the
document and the report that the SQL has NOT been run, and continue with the client. The
owner will not apply SQL that has not been run.

Write `supabase/PREPARED_AUDIO_ROLLOUT.md` in the style of `SECURITY_ROLLOUT.md`: the
exact steps the owner runs to apply and verify the migration, and how to roll back.

### Step 2: one file per chapter

A prepared chapter is a folder with `manifest.json` and one `.m4a` per sentence.

- Pack it into a single archive without recompressing the audio (a stored zip is
  enough). The archive name and the cloud relative path are built from hashes only: the
  chapter href hash and a hash of voice id, model version, rate and pitch. No title, no
  text.
- Unpack with every check, each with a test written first: entries cannot escape the
  target folder; only `manifest.json` and files the manifest lists are accepted; sizes
  match the manifest; total size is bounded; an unknown manifest version is rejected; a
  truncated archive leaves nothing behind. Unpack into a temporary folder and move into
  place only when everything passed.
- A chapter whose manifest is not complete is never packed or uploaded.
- Pure logic, host-testable, in the pattern of `TtsPreparedStore`.

### Step 3: upload

- After a chapter finishes preparing, and at app start for chapters prepared earlier, a
  chapter is queued for upload when: auto-backup is on, the device is on Wi-Fi, the
  account is allowed to upload, and the book has an available backup in the cloud.
  Books waiting to upload go first.
- Reuse the book file transfer engine's reserve, resumable upload and finalize path and
  its persisted queue. Do not write a second uploader.
- States, each tested with a fake transport: uploaded; waiting for Wi-Fi; waiting for
  the book's backup; storage full; failed and will retry; not allowed.
- Storage full is permanent until something changes: no retry loop, one message.
- Preparing the same chapter again with the same settings does not upload twice.
  Preparing it with other settings is a different file.
- Deleting the chapter from the row cancels a pending upload and deletes the cloud file.
- Analytics: one event per upload outcome, with size and outcome only. Add every new
  parameter to `AnalyticsParameterSanitizer.kt` with a test.

### Step 4: download on another device

- The device learns from the cloud which chapters of a book have prepared audio and for
  which voice and speed.
- The row for the chapter on screen gains a state: prepared audio is available in the
  cloud, with its size and what it was made for, and one action, Download.
- Download uses the existing restore path, verifies the archive (Step 2), installs it
  into the prepared store, and the chapter then plays instantly, if the same voice and
  speed are selected. If they are not, the row says what it was made for, as it already
  does for local audio.
- Download failures: no network, storage full on the device, archive rejected, file gone
  from the cloud. Each ends in one clear state; a rejected archive is never installed.
- Tests with a fake transport for every state.

### Step 5: the screens

- The prepared-chapter row (`PreparedChapterRowState.kt`, `PreparedChapterRowUi.kt`,
  `PreparedChapterCard.kt`) gains: backed up; waiting to back up and why; storage full
  with "Manage storage"; available in the cloud with Download; downloading with
  progress. Keep it one line of status under the existing content, not a second card.
  Extend the pure state and UI functions and their tests; the composable only renders.
- The delete confirmation says the chapter is removed from this device and the cloud
  when it is backed up.
- The cloud account screen's storage row
  (`feature/cloud-account/ui/.../CloudAccountScreen.kt`) shows the breakdown: books and
  prepared audio. "Manage storage" from the row leads there.
- One named function is the place where a "get more storage" action will be attached
  later. It is not shown yet.
- Strings go in `translations/src/commonMain/composeResources/values/strings.xml` in the
  form of the existing `reader_tts_prepared_` entries. List every new key in the
  document.

### Step 6: documents

Finish `docs/tts-prepared-cloud.md`: the design answers, what was built, the server
contract, the archive format, every state and string, what has and has not been run, the
known limits, and a section "For the paid storage project" naming the hook and the
places that show usage.

### Step 7: checks that can be done without the hosted server

- All host tests and both app builds.
- On the Samsung (rules below), with no server change deployed: prepare a chapter and
  confirm nothing regressed: it prepares, plays instantly, and the row shows a sensible
  backup state for an account that cannot upload or a server that does not know the new
  media type. The app must not crash, loop or show an error for something the user did
  not ask for.
- The real end-to-end check (upload, see the usage, download on a second device, delete,
  storage full) needs the migration applied by the owner. Write the exact checklist for
  it in the document. Do not attempt it.

Device rules: only the Samsung, serial `RFCWC0SSVDM`; `-s RFCWC0SSVDM` on every adb call;
`adb install -r` only; never uninstall, never clear app data, do not sign out or in, do
not change network settings or the ringer; no other phone. A tap in the middle of the
page reveals the reader's control row; wait 0.9 seconds; a long press on Listen
(`input swipe X Y X Y 800`) opens the Listening sheet. Leave the phone on a System voice
at rate 1.0, on the library screen, with prepared audio deleted.

## Not in this work

Purchasing or tiers of storage; a "get more storage" button; automatic download; iPhone
playback of prepared audio; uploading audio for a book that has no backup; sharing audio
between accounts; changing how books are uploaded or restored; deploying anything.

## Verify

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :feature:reader:ui:testAndroidHostTest :feature:reader:ui:iosSimulatorArm64Test :feature:books:data:testAndroidHostTest :feature:cloud-account:ui:testAndroidHostTest :feature:settings:ui:testAndroidHostTest :lib:server-parrot-cloud:testAndroidHostTest :lib:analytics:implementation:testAndroidHostTest :composeApp:testAndroidHostTest --continue --max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx6g
```

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64
```

```bash
scripts/supabase/test.sh
```

Some of those Gradle test tasks may not exist under exactly those names; list the
project's tasks, use the right ones, and record the baseline count of every module you
will touch BEFORE changing anything. Read counts from the result files under
`build/test-results`. Add the new `@Single` classes to a composeApp test that resolves
the real dependency graph, like `PreparedChapterJobWiringTest`.

## The report

Every run ends with the report below, filled in, in exactly this form, inside one fenced
code block, plain text only, nothing after it. Save it as
`docs/tts-prepared-cloud-report.txt` and commit it at the end of every step. Answer "not
built" for steps not reached.

```text
--- REPORT ---
Branch:
Commits this run (hash + subject, oldest first):
Steps complete this run, and steps complete in total (0-7):
Step 0: can prepared audio be one more cloud_book_files row (yes/no, what had to change); what an older Android app and the iPhone app do with an unknown media type (file and line), and how that risk is handled:
Step 0: how a book is identified across devices and mapped to the local prepared store:
Step 1: migration file name; what it changes (list); pgTAP tests added (count); was the SQL actually run locally (yes/no, and if no, why):
Step 1: cascade on book backup delete, takedown, block-list and account deletion (tested yes/no each):
Step 1: existing server tests edited (name + why), or "none":
Step 2: archive format; checks on unpack (list); what a rejected archive leaves behind:
Step 3: when a chapter is queued; states built (list); was the existing transfer engine reused (yes/no, how):
Step 4: how a device learns what the cloud holds; download states built (list):
Step 5: row states added (list); new string keys (list); where the "get more storage" hook is (file and function):
Does iPhone behave exactly as before (yes/no) and how I know:
Existing app tests edited (name + why), or "none":
Per module, before and after: <module> <passed>/<total> -> <passed>/<total> (one line each):
Does a test resolve the new classes from the real Koin graph (yes/no, name):
App build results (Android assemble, iOS framework):
Server test suite result (passed/total, or "not run"):
Device used (model + serial), any command sent to another device (yes/no):
On the phone with nothing deployed: prepare and play still work (yes/no); what the row showed about backup; any crash, loop or unasked-for error (yes/no):
What the owner must do before the end-to-end check (list):
State the phone was left in (voice, rate, screen, prepared audio deleted yes/no):
Why the run ended where it did (finished / out of time / blocked, and on what):
Done this run:
Not done or partly done, and why:
Tests skipped, ignored or weakened (file + name + reason), or "none":
Places the brief was ambiguous and what I chose:
Files changed outside supabase, scripts/supabase, lib/server-parrot-cloud, feature/books, feature/reader, feature/cloud-account, feature/settings, lib/analytics, translations, composeApp tests and docs:
Known problems I am leaving:
--- END REPORT ---
```
