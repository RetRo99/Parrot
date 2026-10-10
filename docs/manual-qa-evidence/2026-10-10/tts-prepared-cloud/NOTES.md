# Prepared chapters in Parrot Cloud: run of 2026-10-10

Branch `tts/prepared-cloud`, worktree `.claude/worktrees/tts-investigation`.
One line per check: what was run, what the server answered, what the screen showed.
No key, password, token, project reference or account email appears in this file.
The linked project's reference is written as `<REF>` wherever a command needs it.

## Part A: the server, on the linked development project

A0  `supabase/.temp` is still git-ignored (`.gitignore:38` matches
    `supabase/.temp/project-ref`); `git status --short` shows nothing from it and
    nothing from it was committed.
A0  CLI present: supabase 2.119.0 at /opt/homebrew/bin/supabase. `supabase projects
    list` succeeded, so the stored login is valid; no credential was looked for.
A1  LINKED, yes. `supabase link --project-ref "$(cat ../../supabase/.temp/project-ref)"`
    from the main checkout's reference. Answered `{"project_ref":"<REF>","message":""}`,
    exit 0. No login prompt and no database password prompt: the CLI reused the
    existing login and provisions a temporary login role per connection.
A2  `supabase migration list --linked` -> "Initialising login role... Connecting to
    remote database..." then the list. STOP CONDITION HIT. **Two** migrations are
    pending, not one:
      20261009000000  local only, remote empty  parrot_cloud_saved_words.sql
      20261010000000  local only, remote empty  parrot_cloud_prepared_audio.sql
    All 31 other local migrations are applied and no remote migration is missing from
    the repository, so the divergence is one-directional.
    20261009000000 is NOT this work's: it is the saved-words/offline-dictionary
    feature (commit 9bf4f22a, "feat: add bundled offline English dictionary and saved
    words") and its own header says "Deploy before clients that push words. Existing
    clients need the unknown-type reader fix before words are shared with them".
    `supabase db push --linked` applies every pending migration in order, so it would
    deploy that other feature to the shared development project as a side effect of
    this work. The brief forbids that ("stop if any migration other than this work's
    is pending"), so nothing was applied. NOT APPLIED.
A2  There is no CLI route that applies one pending migration and skips an earlier
    one; `db push` and `migration up` both apply the whole pending chain in timestamp
    order. Applying 20261010000000 alone would also leave the remote's migration
    history out of order.
A3  NOT RUN, and not runnable here. The two pre-apply queries from
    PREPARED_AUDIO_ROLLOUT.md step 2 need a SQL session. `psql` is not installed and
    the CLI has no "run this SQL" subcommand, so there is no way to run them with the
    CLI alone. No credential was extracted to work around this.
A4  BASELINE NOT RUN, and not runnable here. `supabase test db --linked supabase/tests`
    answered, exit 1:
      {"_tag":"Error","error":{"code":"DockerRunError","message":"docker: command not
      found (podman also not found) - install Docker Desktop or Podman and ensure it
      is on PATH"}}
    `--linked` only changes which database pg_prove is pointed at; pg_prove itself
    still runs in a container. docker, podman, colima, psql and pg_prove are all
    absent from this machine (/usr/bin/prove exists but pg_prove, the TAP harness for
    Postgres, does not). So the 38 new assertions, and the baseline for the 20
    existing files, cannot be executed on this machine at all.
A4  Checked before contemplating any run: all 21 files in `supabase/tests/` begin with
    `begin` and end with `rollback`, so the suite would not have written anything.
A5  NOT APPLIED. Blocked twice over, independently:
      (a) the stop condition at A2 - another feature's migration is pending;
      (b) even with (a) cleared, applying it here would mean applying SQL whose 38
          assertions have still never been executed anywhere, which is exactly what
          PREPARED_AUDIO_ROLLOUT.md step 1 calls "not optional".
A6  NOT RUN (depends on A5).
A7  No corrective migration written: nothing was applied, so nothing could fail.
A7  No existing test edited and no existing migration edited.
A8  PREPARED_AUDIO_ROLLOUT.md updated with what this run established: the migration is
    still unapplied on the development project, the CLI link needs no password, the
    suite needs a container runtime, and the saved-words migration is in the way.
A-  The linked development project is left exactly as found: one link operation and
    two read-only commands (`projects list`, `migration list`) were all that touched
    it. No migration applied, no row written, no reset, no script under
    scripts/supabase run. Book backups are therefore unaffected - see B1 for the
    positive check from the app.

## Part B: the phone, end to end

Device: Samsung SM_S921B, serial RFCWC0SSVDM, `-s RFCWC0SSVDM` on every adb call.
A Xiaomi (2602BPC18G at 192.168.1.248:5555) was also attached for the whole run and
received no command: every call in this file names the Samsung's serial.
App `com.retro99.parrot`, version 0.4.5 (21), debug build.

B0  Start state (B0-start.png): Library, "On your shelf - 6 books", Wi-Fi and mobile
    data both up in the status bar. Alice's Adventures in Wonderland at 24%.
B0  SIGNED IN: **no**. Settings -> Parrot Cloud (B0-cloud-account.png) shows the
    signed-out screen: the two-tick blurb, an EMAIL field with the
    "you@example.com" placeholder, an empty PASSWORD field, a disabled-looking
    "Sign in", "Continue with Google", and "New to Parrot Cloud? Create an account".
    No account, no usage breakdown, no storage row.
    The prompt says the owner had signed the phone in before this run and that if the
    app is not signed in an agent must not try to sign in. It is not signed in, so
    nothing was typed into either field and no sign-in was attempted.
B1-B10  ALL BLOCKED, on the same two things and not attempted:
      - the app is signed out, so there is no account to back a book up to, no usage
        breakdown to read, and no quota row or allow-list row to touch;
      - Part A did not apply 20261010000000, so no server reachable from this phone
        knows the `tts_prepared_audio` media type; every upload, breakdown, download
        and cascade check would be testing the wrong thing even signed in.
    No allow-list row was added: without a session there is no user id to add, and
    none was taken from anywhere else.
B-  So this run still has not seen the app talk to a server that knows prepared
    audio. That remains the one untested seam of the whole feature.

## Part C: things no run could reach before

All of part C is local to the device and needs no server, so none of it was blocked.
Throughout: the app's pid stayed 24620 from the first check to the last and
`logcat | grep -c 'FATAL EXCEPTION'` was 0. The app never died.

### C1 Supertonic (first run on a device since the TTS-F22 crash fix)

C1  Terms: ALREADY ACCEPTED before this run. The Voices sheet showed "Model terms
    accepted" on arrival, so the terms dialog itself was not exercised. Deleting the
    pack does NOT revoke acceptance: after "Delete pack" the card still read "Model
    terms accepted" next to "Download · 145 MB". So the dialog is only reachable
    again by clearing app data, which this run did not do. The TERMS_REQUIRED branch
    of the row was therefore unreachable too - see C2.
C1  Pack downloaded: YES, exercised. Deleted it ("Delete the Supertonic 3 pack?" /
    "This removes all 10 Supertonic 3 voices and frees 145 MB. You're using F1, so
    reading switches to your phone's voice. You can download the pack again later.")
    and downloaded it again from the same sheet. The card returned to
    "✓ Downloaded · works offline" and `du -sh files/tts-models/supertonic` = 139M.
C1  Preview: YES. Tapped Preview on F1. Log: `tts_voice_previewed {is_neural=true}`,
    `TtsRouter: synthesize voice=supertonic:0 engine=SUPERTONIC`,
    `tts_model_prepared {is_success=true, duration_ms=1}`, then
    `SupertonicOnnxTts: Supertonic loaded: sampleRate=44100 speakers=10` and
    `Supertonic synthesize start: sid=0 speed=1.0 chars=253`. Audible, no crash.
C1  Read aloud: YES, 7 sentences of Alice chapter IV, not 3. Each one logged its own
    `synthesize start` / `synthesize done` pair (4819ms, 872ms, 1203ms, 5373ms for
    the longer ones). Selecting F1 first changed the sheet to "In use: Supertonic 3 ·
    F1".
C1  Stop during a sentence: YES. Pressed the control mid-sentence; it became "Play",
    the sheet read "Sentence 7 of 151", pid unchanged, no FATAL. C1c-stopped-
    midsentence.png.
C1  Chapter prepared with Supertonic and played: YES. Used the 1 KB fixture book
    "A Quiet Harbour" (4 sentences) so the whole path finished in seconds rather
    than the 151 sentences of Alice chapter IV. Row went "2 of 4 sentences" + Cancel
    -> "Ready, plays instantly · 81 kB" + "Delete…". On disk:
    files/tts-prepared/46b5…/9c9b… C1d-supertonic-prepared.png.
C1  Played from the prepared audio with NO synthesis: YES. Cleared logcat, pressed
    play, waited 12s: `grep -c 'synthesize start'` = 0, while
    `TtsReadAloudEngine.startSentence` ran. So it played the prepared files.
C1  APP STAYED ALIVE: YES. TTS-F22 does not reproduce.
C1  Note, not this feature's code: the read-aloud bar labels a Supertonic voice
    "Device voice · F1" while the Listening sheet calls the same voice
    "F1 · Natural". Cosmetic, in the read-aloud mini player.

### C2 The two row states never seen on a phone

C2  The row's states are decided by one pure function,
    `derivePreparedChapterRow` in PreparedChapterRowState.kt:48. Reading it settled
    what each attempt below actually proves: `VoiceUnusable` is keyed on the
    CURRENTLY SELECTED voice (`preparedChapterVoice`, line 35: PACK_MISSING when
    `voice.needsDownload`, TERMS_REQUIRED for Supertonic without terms) and is
    checked at line 64, BEFORE the `Failed` branch at line 67. Its press outcome is
    OPEN_VOICES (line 85), so its action opens the Voices sheet.
C2  "Voice not usable" row: NOT REACHED, and the reason is a finding rather than a
    failure. Deleting a pack through the app always moves the selection away first -
    both confirmations say so in as many words ("You're using Heart, so reading
    switches to your phone's voice") and after each delete the sheet read "In use:
    System voice". A system voice is not neural, so `preparedChapterVoice` returns
    USABLE and the `VoiceUnusable` branch cannot be entered. The TERMS_REQUIRED
    branch needs Supertonic selected with terms unaccepted, and acceptance survives
    pack deletion (C1). So neither variant of that row is reachable from the UI on a
    device in this state. The state is not dead code - it is what a device would show
    after reader settings sync a neural voice to a phone that has no pack, which is
    exactly the case this run could not reach because the app is signed out.
C2  What the row DOES show when the voice a chapter was prepared with is gone: the
    `OtherSettings` state. After deleting the Supertonic pack with the F1-prepared
    fixture chapter in place, the row read "Made for F1 (female) at 1×" with the
    action "Prepare again". That is correct and is the honest answer to "what the
    prepared-chapter row shows": it reports the mismatch, not a missing pack.
    C2a-other-settings-row-supertonic.png. (C2a-row-with-pack-removed.png and
    C2c-row-after-pack-delete.png are the Kokoro attempt below, which stayed on
    `Failed`; neither shows a `VoiceUnusable` row, because none was reached.)
C2  I also tried to force PACK_MISSING by removing `files/tts-models/kokoro` with
    run-as while Kokoro Heart stayed selected. It did not produce the state: the
    Voices sheet still showed Kokoro as "✓ Downloaded · works offline" and the row
    stayed on `Failed`. The app's `needsDownload` comes from its own install
    bookkeeping, not from a filesystem check, so an out-of-band deletion leaves that
    record stale. This is an artefact of the way I deleted it and NOT a user-facing
    bug - the UI's own delete path keeps the record right. Recorded only so the next
    run does not repeat the experiment.
C2  "Failed" row and Retry: SEEN, both. Started preparing Alice chapter IV with
    Kokoro Heart ("7 of 151 sentences" + Cancel) and removed the Kokoro model folder
    while it ran. The row became "Couldn't prepare this chapter." with "Retry";
    `TtsChapterPreparation` logged the error through
    `GeneratorSentences.prepare(TtsChapterPreparationJob.kt:62)`; no FATAL, pid
    unchanged. C2b-prepare-failed-retry.png.
C2  Retry while the pack was still missing: the press opened the Voices sheet, i.e.
    it sent me to the one place that can fix the cause, and did not start a doomed
    run or loop. Retry after a usable voice was selected: the preparation restarted
    normally, "7 of 151 sentences". So Retry works and there is no retry loop.
C2  Cancel during that run left the `Partly` state: "19 of 151 sentences prepared".
    One more row state seen on a phone, unasked for.
C2  Kokoro pack re-downloaded afterwards: YES. `du -sh files/tts-models/kokoro` =
    152M, card reads "✓ Downloaded · works offline". Both packs are installed.
C2  Delete confirmation for prepared audio, signed out: "Delete prepared audio?" /
    "This chapter will have to be prepared again before it plays instantly." No
    sentence about the cloud, which is right for audio that is not backed up.

### C3 Lock screen

C3  MEDIA NOTIFICATION WITH PAUSE AND PLAY: NO, not shown. Checked twice, and the
    second time with Do Not Disturb off so the result does not rest on it.
      - With DND on (zen_mode=1, the state the phone was found in): lock screen shows
        only clock, date and battery; no media card. C3-lockscreen.png. Pulling the
        shade down on the lock screen showed AccuBattery, Messenger, Gmail and
        Weather - and nothing from Parrot. C3-lockscreen-shade.png.
      - The owner then allowed changing DND, so: `cmd notification set_dnd off`
        (zen_mode=0), played again, locked. Still no media card.
        C3-lockscreen-dnd-off.png. So DND was not the cause.
      - `dumpsys notification` only ever listed Parrot's `neural_voice_preparation`
        and `chapter_audio_preparation` progress notifications. No playback
        notification channel was posted at any point.
C3  A MediaSession IS published while playing: `dumpsys media_session` showed
    `package=com.retro99.parrot, active=true, state=PLAYING(3), position=38276,
    speed=1.0` with custom actions "Previous sentence" and "Next sentence". So the
    session exists but no notification carries it to the lock screen.
C3  Read-aloud does keep playing while the screen is locked: across one lock it went
    from sentence 3 to sentence 17 of 151, and across another from 17 to 22 with the
    page turning from 2 to 3.
C3  DND was restored to `priority` (zen_mode=1), the value it was found at.

### C3 follow-up: a read-aloud defect found twice (NOT this feature's code)

C3  After a lock and unlock, read-aloud stops advancing but the mini player keeps
    claiming to play, and its play/pause control goes dead.
    Reproduced twice, same signature both times:
      1. start read-aloud, confirm the control reads "Pause" (playing) and sentences
         advance;
      2. lock with KEYCODE_POWER, leave it ~1 minute, wake and unlock;
      3. the sentence counter is frozen (17 of 151 the first time, 22 of 151 the
         second), `grep -c 'startSentence|synthesize start'` over the next 12-15s is
         0, and `dumpsys media_session` has no row for the package, so nothing is
         playing;
      4. the control still reads `content-desc="Pause"`, i.e. the UI believes it is
         playing, and pressing it at its own dumped bounds [69,2089][132,2152] does
         nothing at all - it stays "Pause", no log line, no progress.
    "Stop listening" does still work and clears the session cleanly, and the app
    never crashed. C3-wedged-readaloud.png.
    This is in read-aloud, not in prepared chapters, so by the brief it is recorded
    here and not fixed in part D.

## Part D

D   No wrong behaviour was found in THIS feature's own code, so nothing was fixed and
    no code changed this run. The three things worth the owner's attention are all
    outside it: the lock-screen read-aloud wedge and the missing media notification
    (read-aloud), and the "Device voice · F1" label in the read-aloud bar.
    The one observation inside this feature - that `VoiceUnusable` cannot be reached
    from the UI because the pack delete flow switches the selected voice first - is
    correct behaviour, not a defect, and its tests already cover the state.

## State left behind

Phone: signed OUT of Parrot Cloud, exactly as found; nothing typed into the sign-in
form. Wi-Fi on (wifi_on=1), mobile data on (mobile_data=1). DND back to priority
(zen_mode=1). Kokoro installed (152M) and Supertonic installed (139M), both reporting
"✓ Downloaded · works offline". System voice, rate 1×, pitch Normal, sleep timer Off.
On the Library screen. Prepared store emptied of everything this run made: the two
book folders remain but hold no chapter, `du -sh files/tts-prepared` = 11K (it was
one empty book folder before, so one empty folder more). Alice's reading position
moved from 24% to 25% and its chapter IV partial audio was deleted through the app's
own confirmation. No command was sent to the Xiaomi.

Linked Supabase project: untouched beyond one `link` and two read-only commands. No
migration applied, no row written, no allow-list row added, no quota changed.

