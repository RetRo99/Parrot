# UX redesign — before/after changelog

Goal: a first-time user should understand what to do on every screen without
instructions or guesswork. Every change below was verified by building the debug
APK and walking the flow on a device with `adb` screenshots.

## Evidence

| Device | Purpose | Screenshots |
| --- | --- | --- |
| Xiaomi `7TEULNB6JJTK75Y5` | Real user data, main flows | `docs/ux-audit/screenshots/01–17`, `30–72` |
| Emulator `emulator-5556` | Fresh install (first-run, empty state) | `docs/ux-audit/screenshots/18–22`, `60–62` |

"Before" = `01–22`, "after" = `30+`. Helpers used for the walk-through:
`scripts/ux-shot.sh` (sequence + screenshot) and `scripts/ux-tap-text.sh`
(tap a node by its visible text, then screenshot).

## Root cause: labels that did not render at all

The first audit showed buttons with no text at all (empty-state CTA, extended
FAB). The strings were added to `translations/.../strings.xml` *after* the APK
had been built — the installed build was stale, not buggy. The APK on the device
now matches the source (verified by MD5 of the built vs. pulled APK) and every
label renders:

* Empty state: icon + **No Books Found** + "Import a book to get started" +
  primary **Import a book** button (`60-fresh-launch.png`).
* FAB reads **＋ Import book** instead of a bare icon (`70-books-final.png`).

## Per-flow changes

### First run (Welcome → Sign in / browse)

*Before* `18-welcome.png`, `20-login-fresh.png`: feature bullets then a Sign In
screen with a greyed-out button and no explanation of why.

*After* (`61-welcome-after.png`, `62-login-fresh-after.png`):

* Welcome keeps two clearly ranked actions: **Get Started** (primary) and
  **Browse without account** (secondary).
* Sign In has a title + subtitle ("Connect to your library") so the screen keeps
  context, visible labels on every field (Server Type, Server URL, Username,
  Password), and — when the button is disabled — a hint directly underneath:
  *"Enter the address of your server, then sign in with your username and
  password."* (`login_sign_in_hint`). The dead grey button no longer looks
  broken.

### Books list

*Before* `02-books-list.png`: five unlabelled toolbar icons, an unlabelled FAB,
and a stray full-width "Back up all existing books" button floating above the
list.

*After* (`30-books-after-cycle1.png`, `70-books-final.png`):

* FAB is an extended FAB with an icon **and** the label "Import book", plus a
  `contentDescription` for TalkBack.
* Toolbar actions carry tooltips/labels; the bottom bar is
  Books / Series / Statistics / Settings with text labels.
* The "Back up all existing books" action is a proper full-width outlined button
  with a `CloudUpload` icon (`BooksListScreen`), and it only renders when the
  account actually supports cloud backup (`viewState.supportsCloudBackup`), so it
  no longer appears as an orphan control on a list that cannot back up.
* The "Continue reading" shelf card at the top gives one obvious next step for a
  returning reader (cover, title, % and format, play button).

### Book detail

*Before* `07-book-detail.png`, `08-book-detail-scroll.png`: no read action (the
card said "Download"/"Ready"), title clipped and marquee-scrambled by scrolling,
raw ISO timestamp `0101-01-01T00:00:00.0…`, and a "Description" heading with
nothing under it.

*After* (`36-detail-v5.png`):

* **One obvious primary action** — `PrimaryMediaAction` renders the right button
  for the state: *Continue reading (14%)*, *Listen*, *Download to read*,
  *Download to listen*, *Downloading… N%*, *Read*.
* Format cards stay below under a **"Other formats"** section label, so the
  primary CTA is never buried among equal-looking options.
* Title no longer uses `basicMarquee` — full title, wrapped.
* Publication date is year-only and placeholder dates (`0101-01-01…`) are hidden
  (`formatPublicationDate`).
* Descriptions are converted from publisher HTML to plain text before rendering
  (`plainTextDescription`): the section now shows real prose instead of an empty
  heading, and it is hidden entirely when there is nothing readable.
* The floating continue-reading bubble is hidden here (it duplicated and covered
  the Reading Progress bar).

### Reader

*Before* `10-reader.png`, `11-reader-controls.png`: controls and read-aloud
overlays over content, unclear targets.

*After* (`72-reader-final.png` + reader/controls sheets): tap target verified
end-to-end from the detail CTA — reader opens, progress (14%) is carried over,
controls and table-of-contents are reachable and dismissible. No crashes across
the walk-through.

### Series

*Before* `05-series.png`: bare list, no header.

*After* (`71-series-final.png`): screen title **Series** with a count line
("1 series in your library") and labelled cards.

### Statistics

*Before* `12-statistics.png`: the heading "Reading Statistics" appeared twice
(top bar and first list item) and streaks read "1 days".

*After* (`45-statistics-after.png`): single heading (the top bar owns it), and
streaks use `statistics_day_singular` — "1 day" for both current and longest
streak (`dayCountResource`).

### Settings

*Before* `03-settings.png`, `04-settings-scroll.png`: the floating
continue-reading bubble sat on top of the toggles; **Account** (Servers, Sync &
Backup) was buried below **Support**.

*After* (`40-settings-after.png`, `41-settings-scroll.png`):

* Section order is now Profiles → Reading → **Account** → Support, so adding a
  server and syncing (the tasks people come for) are reachable without
  scrolling past diagnostics.
* Floating bubble is suppressed on all settings-style surfaces
  (`HomeDestination.hidesContinueBubble`: Settings sheet, AppSettings,
  ServerManagement, SyncAndBackup, Statistics, BookDetail) — rows and toggles are
  never covered again.
* "Show Continue Reading" now describes what it actually does: a card on the
  book list *and* a floating shortcut elsewhere.

### Servers (add/remove sources)

*Before* `13-servers.png`: two unlabelled destructive icons per row.

*After* (`47-servers.png`): rows show type, URL and status ("Logged in as…",
or **Login failed** in red) and the actions carry `contentDescription`s
(sign out / delete), so TalkBack and first-timers can tell them apart.

### Sync & Backup

*Before* `14-sync.png`: raw HTTP exception dumps (`retrofit2.HttpException:
HTTP 401 …`) shown verbatim to the user.

*After* (`49-sync-after.png`): failures go through `friendlyErrorMessage` in
`CloudAccountScreen` — e.g. *"Cloud access is temporarily unavailable. Your
local reading remains available."* Unknown, short error messages pass through
unchanged so debugging detail is not lost. The screen also has a clear value
statement above the form ("Keep your progress, bookmarks … across devices").

### Add server / Sign in

*Before* `15-add-server.png`, `16-server-types.png`, `17-login-filled.png`.

*After* (`48-add-server-after.png`): same field labelling and the new disabled
-state hint; server-type picker unchanged (Storyteller / Audiobookshelf / local).

## Accessibility & consistency

* Touch targets ≥ 48dp: FAB 182dp, nav items ~150×140dp, list cards full width,
  icon-only buttons 156dp.
* Icon-only actions have `contentDescription`s; toolbar actions have tooltips.
* All user-facing text goes through `:translations` (`strings.xml`); no hardcoded
  English in composables.
* Changes are in `commonMain` Kotlin — iOS/desktop targets are unaffected.

## Files touched in this pass

* `feature/books/ui/.../detail/BookDetailScreen.kt` — primary CTA, marquee/date/
  description fixes.
* `feature/books/ui/.../list/BooksListScreen.kt` — backup button, FAB label.
* `feature/cloud-account/ui/.../CloudAccountScreen.kt` — friendly errors.
* `feature/statistics/ui/.../StatisticsScreen.kt` — duplicate heading, day
  grammar.
* `feature/home/ui/.../navigation/HomeNavigation.kt` + `HomeDestination.kt` —
  bubble visibility.
* `feature/home/ui/.../appsettings/AppSettingsScreen.kt` — Account before
  Support.
* `feature/login/ui/.../LoginScreen.kt` — disabled-button hint.
* `translations/.../strings.xml` — new/updated strings.
* `scripts/ux-shot.sh`, `scripts/ux-tap-text.sh` — reproducible device walk-
  through helpers.

The working tree also contains concurrent UX work (component library
`base-ui/.../ParrotComponents.kt` + `ParrotTheme.kt`, reader/settings/series
polish, extended FAB, toolbar tooltips, nav labels) that these screenshots
include.

## Walk-through result

Fresh install → welcome → sign in / browse → empty home → add server → books →
book detail → read → progress → statistics → settings → servers → sync all
exercised on device with no crashes and no dead ends. Remaining known gap: the
Parrot Cloud form still shows a disabled "Sign in" until both fields are filled
(now explained by the hint) and OAuth ("Continue with Google") availability
depends on server config.

## Second pass (Samsung Galaxy S24 `SM-S921B`, real library data)

Walked every flow again on a second device. Evidence: `docs/ux-audit/screenshots/
s-01–s-18`. Findings and fixes from this pass:

### Book detail

* **Primary CTA looked disabled.** The detail screen re-colors itself from the
  book cover (`backdropColorScheme`); a muted cover produced a grey `primary`,
  so "Download to read" / "Continue reading" rendered grey-on-grey like a dead
  button. Backdrop schemes now keep the Parrot brand accent for all action roles
  (`base-ui/.../DominantColor.kt`), so the CTA is brand-green on every cover.
  Verified in `s-04-detail-after.png` (before: `s-02-book-detail.png`).
* **Single-format books duplicated the primary action** as a big
  "eBook / Download" card directly under the CTA. With one format the card is
  now omitted (`SingleFormatHousekeeping`); only a quiet
  "Remove download from this device" action remains when an on-device copy
  exists (`books_detail_remove_download`).

### Statistics

* Stat tile values were muted (`onSurfaceVariant`) — now `onSurface`, so the
  number is the brightest element and labels stay quiet.
* An all-zero dashboard was a wall of zeros with no explanation. It now leads
  with *"No reading activity yet. Open a book and your time, sessions and
  streaks will appear here."* (`statistics_empty_hint`, `s-05`, `s-18`).

### Servers

* The add-server action was a bare "+" FAB — now an extended FAB labelled
  **＋ Add Server**, matching the "Import book" FAB on the books list.
* Sign-out used a **✕** icon that reads as "dismiss" — now a proper logout icon
  (trash stays for delete). `s-15`/`s-17` verify both.
* Server status copy ("Logged in as…", "Login failed", "Not logged in",
  "Session expired") was hardcoded English — moved into `:translations`
  (`settings_server_logged_in_as`, …).

### Sync & Backup

* "Cloud access is temporarily unavailable" was rendered in the green info
  banner, i.e. looked like good news. Problem messages now use the error
  container (`StatusTone.Warning`), matching other error styling (`s-16`).
* The disabled **Sign in** button now carries a hint underneath ("Enter your
  email and password, then sign in. New here? Create an account below."),
  same pattern as the server Sign In screen (`cloud_account_sign_in_hint`).

### Books list

* The empty library showed the "Import a book" empty-state CTA **and** the
  "＋ Import book" FAB doing the same thing. The FAB now steps aside while the
  empty state owns the action, so there is exactly one obvious next step
  (`e-03-empty-books.png`). It stays visible whenever there are books or active
  filters.

### Reader

* The chapter-position readout showed raw `(15/24)`. It now reads
  **"Page 14 of 21"** (`reader_page_of_pages`) — verified in the reader on the
  Samsung (`s-20-reader.png`).
* Verified as already good: table-of-contents highlights the current chapter
  (bold brand-colored title + accent bar, read chapters dimmed) and every
  toolbar icon carries an accessibility label ("Close", "Table of contents",
  "Bookmarks", "Read aloud", "Reader settings"), touch targets 48dp+.
* Audio: read-aloud mode opens the book with a floating control pill
  (play, ±10 s, collapse) over synchronized text (`s-22`/`s-23`); the audiobook
  CTA routes correctly ("Download to listen" → "Continue listening (6%)").

### Audit tooling

* `scripts/ux-tap-desc.sh` — taps a node by its `content-description`, needed
  for icon-only buttons (e.g. the "Add Server" FAB label).

## Second-pass verification summary

| Flow | Device | Evidence | Result |
| --- | --- | --- | --- |
| First run (fresh install) | emulator `emulator-5556` | `e-01`, `e-02` | Welcome: clear value prop, ranked CTAs |
| Empty library | emulator | `e-03` | One CTA ("Import a book"), FAB steps aside |
| Empty series | emulator | `e-04` | "No Series Found / Series will appear here once available" |
| Books list | Samsung `SM-S921B` | `s-01` | Labelled toolbar + nav + extended FAB |
| Book detail | Samsung | `s-02` → `s-04`, `s-19`, `s-20` | Green CTA, no duplicate card, remove-download action |
| Reader (text) | Xiaomi + Samsung | `v2-03…v2-07`, `s-20` | "Page 14 of 21", labelled toolbar, TOC highlight |
| Reader (audio) | Samsung | `s-21…s-23` | Download → Continue listening → control pill |
| Series | Samsung | `s-03` | Count line + labelled cards |
| Statistics | Samsung | `s-05` → `s-18` | Empty hint, bright values |
| Settings / Reader settings | Samsung | `s-06…s-08` | Ordered sections, quick settings + collapsed groups |
| Servers | Samsung | `s-09` → `s-15`, `s-17` | Labelled FAB, logout icon, localized status |
| Sync & Backup | Samsung | `s-11` → `s-16` | Error-toned banner, sign-in hint |
| Add server / Sign in | Samsung | `s-17` | Labels on every field, disabled-button hint |

Build/verification gates: `:androidApp:assembleDebug` green; `compileKotlinIosArm64`
green for every touched module (iOS unaffected); module `allTests` green. No
crashes or broken flows observed across the walk-through.
