# Ember recap implementation

## Changes

- `feature/reader/ui/.../reader/ReaderRecapCapture.kt`: visible text is only a
  candidate. Adjacent manual turns and fully departed scroll ranges commit text;
  closing discards the remaining visible range. Explicit navigation, background,
  audio playback and startup prompts discard manual candidates. DOM/locator
  mismatches and late extraction results are dropped, never guessed.
- `feature/reader/domain/.../recap/{RecapExcerptBuffer,RecapPolicy,RecapReadTracker}.kt`:
  8,000-character capture budget, brief opening plus verbatim tail; shared chapter
  ranges deduplicate manual reading and extracted TTS chunks. Legacy sentences
  without coordinates retain chapter/index deduplication.
- `feature/reader/ui/.../navigator/ChapterSentenceExtractor.kt` and `tts/TtsSentence.kt`:
  normalized audio chunks retain exact raw chapter coordinates.
- `supabase/functions/generate-recap/recap.ts`: five safety rules and language
  handling remain. Validated `summary` / localized `stoppedAt` become two plain
  paragraphs in existing storage/sync, with a combined 600-character ceiling.
  Plain-prose fallback is one paragraph; existing job fingerprints stay unchanged.
- `feature/reader/ui/.../recap/{ReaderRecapBanner,ReaderRecapViewModel}.kt` and
  `reader/{ReaderOverlay,ReaderScreen}.kt`: no text overlay; toolbar pill plus
  Ember sheet. Position gating, one-shot entry presentation, >1-hour default,
  audio suppression and persistent seen IDs are retained/separated from manual
  availability. Failed/empty results never auto-open.
- `feature/reader/ui/.../recap/RecapSettingsSheet.kt`, home app-settings UI/VM,
  `PreferencesRecapSettings.kt` and `Preferences.kt`: short settings row, dedicated
  settings content, presentation preference, explicit consent/withdrawal
  confirmations, unchanged reachable full disclosure and read-only allowlist RPC.
  Withdrawal remains persistence-first and works while signed out.
- `feature/statistics/ui`: Ember statistics tiles, no speed/model labels, static
  accessible states, only actionable buttons, sign-in navigation and e-ink-safe
  session sheet. Missing historical page counts are omitted, not fabricated.
- `supabase/migrations/20261006000000_recap_error_reasons.sql`: expiry, withdrawal,
  allowlist removal and deletion reasons remain distinct without changing durable
  admission, charging, leases, limits or retention. Expired local text is scrubbed
  while a content-free expired state can remain visible in Statistics.
- `translations/src/commonMain/composeResources/values/strings.xml` and
  `docs/RECAPS.md`: new UI copy and current capture/presentation documentation.

## Report bugs

- **1:** existing jump/audio exclusion and non-clamping estimator preserved.
  Separate commit `ce2a17e7` rejects implausible raw speed before smoothing and
  repairs pinned persisted settings on first read, including restored settings.
- **5:** corrected client deleted-state mapping and added accurate server reasons.
  Server correction requires applying the new migration.
- **6:** corrected when new extracted sentence coordinates are available; the
  legacy/no-coordinate fallback cannot deduplicate across sources reliably.
- **7:** replaced dwell-based capture with confirmed completed ranges.
- **2, 3, 4, 8, 9, 11:** preserve the fixes already present in this checkout.
  The approved 30-second durable submission timeout remains; text retention is
  24 hours and result retention 180 days.
- **10:** iOS TTS implementation and background delivery remain deferred.

## Verification

- Android debug APK built successfully.
- Installed the latest APK on Xiaomi `2602BPC18G` over VPN with `adb install -r`
  (app data preserved), launched `MainActivity`, and confirmed the app process is
  running. This is an installation/launch check, not the full manual checklist.
- 499 Kotlin host tests passed: reader domain 124, data 88, UI 196,
  statistics UI 28, home UI 63.
- 46 Edge helper/provider/worker tests passed, including two-part Slovenian
  output validation, plain fallback, sentinel handling and combined length checks.
- Changed UI and data compile for the iOS simulator. Full Xcode build is blocked
  by missing CocoaPods-generated `Pods-iosApp.debug.xcconfig` and file lists.
- Added `supabase/tests/recap_error_reasons_test.sql`; SQL integration tests were
  not run against a database. No hosted migrations/functions were deployed.
- Unit coverage includes paged/scroll unfinished text, navigation, TTS, long
  capture, position gating, 5-minute/2-hour presentation, dismissal, consent,
  signed-out advice, persistence and failure/expiry states.
- Actual model generation for a Slovenian book, live cloud deletion/quota flows,
  and the complete physical-device/e-ink visual checklist remain manual checks.

## Approved tradeoffs

- An 8k excerpt retains the final ~7k characters, not the entire end of an
  arbitrarily long session.
- Generic rate limits do not promise tomorrow: daily, global and staging
  refusals are not distinguishable in the current API.
- Privacy copy describes 24-hour expiry followed by cleanup, and distinguishes
  immediate local withdrawal from cloud/other-device deletion on reconnect.
- Audio-driven media-overlay page turns are not treated as manually read pages;
  only fully-heard device-TTS sentences have reliable sentence-level capture.
