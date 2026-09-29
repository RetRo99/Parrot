# Parrot — Manual QA Test Plan (Full In-App Action Coverage)

**Execution goal and instrumentation requirements:** [manual-qa-goal.md](manual-qa-goal.md). Read that document before running this catalogue. Baseline IDs 1–494 are preserved; extension IDs 495–754 add Samsung setup, screen edge cases and observability verification. These are planned tests, not recorded results.

**Scope of this pass:** every action a single user can perform inside the app — opening, navigating, backing out, reading, playing, downloading, importing, backing up, configuring, viewing statistics, managing profiles/servers/accounts, and all lifecycle behaviour.

**Explicitly out of scope for this pass (see "Out of scope" at the end):** cross-app and cross-device sync verification (state consistency between two devices / two app instances, or round-trip verification against the Storyteller / Audiobookshelf companion apps beyond the sign-in handshake itself), payments, and automated UI tests.

**Device for this pass:** the physical Samsung phone connected via ADB. iOS, e-ink and Android Auto cases are DEFERRED. Bluetooth/headset cases require an available accessory. Record the actual device, OS/One UI, installed build and package in the run manifest.

**Conventions used below**
- "Detail" = Book Detail screen. "Reader" = eBook / ReadAloud reader. "Player" = Audiobook player.
- Every row is a test case and has a unique number. Report bugs referencing these numbers.
- Unless a case says otherwise, perform it on an account with a real library (mixed books: ebook-only, audiobook-only, ReadAloud, all-three, series books, favourites, local/imported book).
- Mark each case **NOT RUN / PASS / FAIL / BLOCKED / N-A / DEFERRED**, with evidence and a reason for exclusions. Record analytics and diagnostics verification separately from functional results.
- Run each screen's baseline and extension cases together using the execution order in `manual-qa-goal.md`. For every screen, audit/add instrumentation before its device pass.
- Cases describe intended behaviour to verify. Reconcile assumed labels, availability and ambiguous outcomes with the installed build before scoring; do not count missing/unreachable controls as tested.

### Scope overrides for preserved baseline cases

These overrides take precedence over the original rows below, retaining their IDs for existing references:

| IDs | Disposition for this pass |
|---|---|
| Cases 109–112, 261 | DEFERRED: position conflict / remote-position reconciliation testing |
| Cases 347, 465–466 | DEFERRED: synchronisation/queued-change recovery verification |
| Cases 370–371 | DEFERRED: sync status transitions and Sync now execution; navigation to the cloud screen remains in scope |
| Case 108 | Inspect local progress only; do not compare local/server position correctness |
| Case 162 | Verify local position persistence only; do not verify sync queuing |
| Case 374 | Local link dialog confirm/cancel and current-device state only; no remote profile merge assertions |
| Case 380 | Restore the backup file and open it on this phone; remote reading-position restoration is deferred |
| Case 384 | Verify cloud sign-in persistence only, not sync state restoration |
| Case 464 | Verify offline UI, local usability and bounded failure reporting; no `SyncResult` or queue assertions |
| Cases 8, 437; setup S6 | E-ink hardware coverage deferred |
| Case 323 | Android Auto deferred |

Ordinary cached-library browsing, sign-in handshakes, downloads, account controls and single-device file backup/restore remain in scope. Incidental background sync must not be mistaken for completion of a deferred test.

---

## Test setup (not numbered — do once per device)

- S1. Record the Samsung serial and installed build. Use a debug build for local diagnosis and a Firebase-enabled release-like QA build for Firebase verification on this same phone, in separate passes or distinct packages if supported. The current debug analytics provider logs locally only. Preserve fixture data when switching builds.
- S2. Prepare a media server account (Storyteller and/or Audiobookshelf) with: 100+ books, at least one series with 5+ entries, books with covers and long HTML descriptions, an audiobook with 10+ tracks, a ReadAloud book with audio narration, a TTS-only book.
- S3. Prepare a **Parrot Cloud** account (email + password, and a Google account) for section 14.
- S4. Prepare test files: valid EPUB, very large EPUB, corrupt/non-EPUB file renamed to .epub, an EPUB with a custom font file for import.
- S5. Have a way to toggle airplane mode, kill the process, and simulate low storage.
- S6. Note the e-ink device run (if available) is a separate pass for cases marked "(e-ink)".

---

## 1. App launch & onboarding

| # | Test case | Steps | Expected |
|---|---|---|---|
| 1 | Cold start on first install | Install, grant nothing, launch | Splash appears, then the Welcome screen with "Get Started" and "Browse without account" |
| 2 | Cold start when already signed in | Sign in, kill app, relaunch | Splash routes straight to Home (Books tab), no login flash |
| 3 | Cold start in guest mode | Choose "Browse without account", kill, relaunch | Splash routes to Home in guest mode, local library intact |
| 4 | Get Started | Tap "Get Started" | Server login screen opens with server-type dropdown |
| 5 | Browse without account | Tap "Browse without account" | Home opens with local-only library; no server books; import still works |
| 6 | Welcome toolbar back-arrow availability | Reach the unauthenticated root Welcome and inspect the top-left control; if no toolbar arrow is offered, record N-A (system Back is case 522) | A toolbar arrow is offered only when it returns to a valid prior route; root Welcome must not expose a decorative or broken Back action |
| 7 | Build badge | Inspect the badge on Welcome on debug vs release builds | DEBUG badge only on debug builds, RELEASE badge only on release builds |
| 8 | E-ink theming (e-ink) | Launch on an e-ink device | E-ink theme applied and animations disabled app-wide |
| 9 | Rotation on Welcome | Rotate device on Welcome | Layout re-lays out, no duplicated content, no crash |
| 10 | Background and resume on Welcome | Home button, wait, return | Same Welcome state, no relaunch into a broken state |
| 11 | Open last book on launch (on) | Enable "Open last book on launch", read something, kill app, relaunch | App opens directly into the last book at the saved position |
| 12 | Open last book on launch (no current book) | Enable the toggle with "Clear current book" done, relaunch | Normal Home launch, no crash |
| 13 | Locale rendering | Set device to a non-English locale, launch | All UI strings are English, nothing clipped or overflowing |
| 14 | Offline cold start | Airplane mode, launch app | App starts into cached library, no crash, offline behaviour per section 13 |

## 2. Server login & authentication

| # | Test case | Steps | Expected |
|---|---|---|---|
| 15 | Server type dropdown | Open the dropdown | Storyteller and Audiobookshelf selectable; Local and Parrot Cloud not offered |
| 16 | Server URL info tooltip | Tap the info button next to the URL field | Help explaining the URL format appears and can be dismissed |
| 17 | Invalid URL validation | Enter "not a url", tap Sign In | Inline validation error, no crash, no endless spinner |
| 18 | Empty submit validation | Submit with empty fields | Field-level validation errors, nothing is sent |
| 19 | Password masked | Type a password | Characters hidden by default |
| 20 | Show/hide password toggle | Tap the visibility toggle twice | Text revealed then masked again |
| 21 | IME Sign-in action | Fill fields, press the keyboard action key | Sign-in is triggered (same as tapping the button) |
| 22 | Successful sign-in (credentials) | Valid URL + username + password | Loading state, then Home; server appears in Server Management as logged in |
| 23 | Wrong credentials | Wrong password | Clear "login failed" error; inputs preserved; can retry |
| 24 | Network error on sign-in | Airplane mode, submit | Plain-language network error; no crash; retry works after reconnect |
| 25 | OAuth sign-in happy path | Tap "Sign in with [Server] app" | Waiting message, external app/browser roundtrip returns, user lands signed in |
| 26 | OAuth cancelled | Start OAuth and cancel/return without authorising | Back at login with no partial account, no stuck spinner |
| 27 | OAuth while app backgrounded | Start OAuth and background the app mid-flow | Flow is cancelled gracefully with a message, login screen remains usable |
| 28 | Back from Login | Tap back | Returns to Welcome |
| 29 | Rotation during login | Enter text, rotate | All field values preserved, no double-submit |
| 30 | Session expired | Let the server session expire (or revoke it), reopen library | Server shows "session expired" state and offers re-login; no crash |
| 31 | Storyteller OAuth deep-link callback | Complete a Storyteller OAuth that returns via `storyteller://` | Callback completes sign-in even when the app was backgrounded |
| 32 | Google auth deep-link callback | Complete Google sign-in returning via `parrot://auth/callback` | Callback handled, account signed in (see also 366) |
| 33 | Add a second server account | From Server Management choose "Add server", sign in with another account | Both servers present; library shows books from both with correct badges |
| 34 | URL variants | Try URL with and without trailing slash, with and without port, with https | All are normalised and accepted where valid |
| 35 | Keyboard overlap | Focus each field with a small screen | Fields stay visible above the IME, submit button reachable |
| 36 | Slow sign-in response | Use a slow network / throttle | Loading indicator shown, button disabled while in flight, no duplicate requests |

## 3. Books library (Books tab)

| # | Test case | Steps | Expected |
|---|---|---|---|
| 37 | Default library view | Open Books tab | Books render in the last used view mode with covers, title, author |
| 38 | Card contents | Inspect a card | Cover, title, author, series line (if any), server badge, favourite state, download state visible |
| 39 | Pull-to-refresh success | Pull down | Refresh spinner, library reloads, list stays at top |
| 40 | Pull-to-refresh offline | Airplane mode, pull down | Graceful failure message, cached list retained, no crash |
| 41 | Show search | Tap the search icon | Search bar appears with keyboard focus |
| 42 | Hide search | Tap the search icon again (or dismiss) | Search bar hides and the query is cleared/restored |
| 43 | Search by title | Type part of a title | Live filtering on title matches only |
| 44 | Search by author | Type an author name | Author matches shown |
| 45 | Search by series | Type a series name | Books in that series shown |
| 46 | Search by tag | Type a tag name | Books carrying the tag shown |
| 47 | Clear search | Tap the X in the search field | Full library restored instantly |
| 48 | Search with no matches | Type gibberish | "No Books Match Your Filters" empty state with a Reset Filters action |
| 49 | Open filter sheet | Tap the filter icon | `BookFilterBottomSheet` opens with quick-filter chips and server filter |
| 50 | Quick filter: Favorites | Toggle Favorites | Only favourited books remain |
| 51 | Quick filter: In Progress | Toggle In Progress | Only started-but-unfinished books remain |
| 52 | Quick filter: Downloaded / Cached | Toggle Downloaded | Only locally available books remain |
| 53 | Quick filter: Has Ebook | Toggle | Only books with an ebook format remain |
| 54 | Quick filter: Has Audiobook | Toggle | Only books with audiobook format remain |
| 55 | Quick filter: Has ReadAloud | Toggle | Only ReadAloud-capable books remain |
| 56 | Quick filter: In Series | Toggle | Only books belonging to a series remain |
| 57 | Combined quick filters | Enable 2-3 filters together | Combined result is correct and consistent with the chips shown |
| 58 | Server type filter chip | Filter by "All" then by each ServerType | List narrows per server; "All" restores everything |
| 59 | Clear all filters | Tap "Clear all" in the filter sheet | Every filter and the server filter reset |
| 60 | Clear quick filters | Tap "Clear quick filters" | Quick chips reset, server filter preserved as designed |
| 61 | Sort menu contents | Open the sort selector | All 5 keys (Title, Author, Rating, Date Published, Date Added) with direction labels (A→Z, Z→A, Newest, Oldest, Highest, Lowest) |
| 62 | Sort by title ascending | Select Title A→Z | Alphabetical order A→Z |
| 63 | Sort by title descending | Select Title Z→A | Reverse alphabetical order |
| 64 | Sort by author | Select Author both directions | Correct author ordering both ways |
| 65 | Sort by rating | Select Rating Highest then Lowest | Correct numeric ordering |
| 66 | Sort by date published | Select Date Published Newest then Oldest | Correct chronological ordering |
| 67 | Sort by date added | Select Date Added Newest then Oldest | Recently added first, then reverse |
| 68 | Sort change scrolls to top | Scroll down, change sort | List scrolls back to the top |
| 69 | Switch to grid view | Tap the view-mode toggle | Grid layout with covers renders |
| 70 | Switch to list view | Tap the toggle again | List layout restores |
| 71 | Open a book | Tap a card | Book Detail opens for that book |
| 72 | Favourite from card | Tap the heart on a card | Toggles immediately (optimistic), visible on Detail too |
| 73 | Favourite persistence | Toggle, refresh, kill app, relaunch | State unchanged |
| 74 | Server badge accuracy | Compare badges for books from different servers | Each card shows its source server correctly |
| 75 | Import FAB | Tap the Import FAB | System file picker opens, filtered to EPUB, single selection |
| 76 | Backup all visibility | Compare with and without cloud backup support | "Backup all" only visible when cloud backup is supported |
| 77 | Backup all attestation gating | Tap "Backup all" without ticking the rights checkbox | Confirm button disabled until the attestation checkbox is ticked |
| 78 | Backup all result | Run "Backup all" with several books | Result dialog shows queued and failed counts with error text for failures |
| 79 | Import-backup attestation prompt | Import a book while cloud is linked | Prompt "Enable"/"Not now" appears once and behaves as chosen |
| 80 | Empty library state | Use a fresh profile with no books | "No Books Found / Import a book to get started" and an import CTA |
| 81 | Continue-Reading shelf | Tap the continue-reading entry in the header | Reader opens that book at the saved position |
| 82 | Clear continue reading | Tap the shelf overflow (⋮) and "Clear" | Shelf entry removed, hidden when nothing remains |
| 83 | Large library performance | Scroll a 500+ book library in list and grid | Smooth scrolling, no jank, covers load progressively |
| 84 | Scroll position preserved | Scroll deep, open a book, go back | Same scroll offset restored in both view modes |

## 4. Book detail

| # | Test case | Steps | Expected |
|---|---|---|---|
| 85 | Detail renders all metadata | Open a fully-tagged book | Cover, title, authors, series entry, tags, star rating, description, duration/narration info all render |
| 86 | Back arrow | Tap the top back arrow | Returns to the library with scroll position preserved |
| 87 | Back gesture | Use the system back gesture | Same as the back arrow |
| 88 | Favourite toggle in top bar | Tap the heart | Toggles and matches the card state in the library |
| 89 | Read an ebook (downloaded) | Tap Read on an ebook | Reader opens at the stored position |
| 90 | Play an audiobook (downloaded) | Tap Play on an audiobook | Audiobook player opens and is ready |
| 91 | Read ReadAloud (ready) | Tap Read on a ReadAloud book | Reader opens with narration controls available |
| 92 | Primary button becomes Download | Open an undownloaded book | Primary action is "Download" first, then switches to Read/Play |
| 93 | Start a download | Tap Download | "Downloading…" chip and progress; completes to "Ready" |
| 94 | Cancel a download | Tap Cancel download mid-transfer | Transfer stops, partial file cleaned, button returns to Download |
| 95 | Download completion state | Wait for a download to finish | Status chip "Ready", primary button becomes Read/Play |
| 96 | Remove download | Tap "Remove download" and confirm | Local copy removed, button returns to Download |
| 97 | Delete cache confirmation | Tap Delete cache | Confirmation dialog appears with destructive styling |
| 98 | Delete cache cancel | Cancel the dialog | Nothing is deleted |
| 99 | Per-format actions | On a book with all three formats | Each format row exposes Download, Cancel, Delete independently with correct chips |
| 100 | Delete local book | On an imported local book, tap Delete local book and confirm | Book and its files removed from the library |
| 101 | Delete local book cancel | Cancel the dialog | Book remains |
| 102 | Cloud backup confirm | Tap Cloud backup, tick attestation, confirm | Backup starts with transfer progress shown |
| 103 | Cloud backup cancel | Cancel the attestation dialog | No backup is created |
| 104 | Delete cloud backup | Tap Delete cloud backup and confirm | Cloud copy removed, button state updates |
| 105 | Cancel a backup transfer | Tap Cancel transfer mid-upload | Upload stops, state shows cancelled, retry available |
| 106 | Retry a backup | Tap Retry backup after a failure | Upload restarts and completes |
| 107 | Replace backup | Tap Replace backup and confirm | Existing cloud copy replaced, no duplicate |
| 108 | Reading progress bars | Open a partially read book | Local and remote progress both render with correct percentages |
| 109 | Position conflict → Use This Device | Trigger a local/remote position conflict, choose "Use This Device" | Device position wins everywhere |
| 110 | Position conflict → Use Server | Same conflict, choose "Use Server" | Server position adopted, reader opens there |
| 111 | Position conflict dismissed | Dismiss the dialog | Nothing changes, conflict surfaces again later |
| 112 | Conflict resolution error | Force a conflict resolution failure | Error snackbar shown in plain language, app stays usable |
| 113 | Tag chips | Tap a tag chip | Tag selection applies a tag filter (or opens the tag view) — must not crash |
| 114 | Series entry | Tap the series entry | Series Detail for that series opens |
| 115 | Expand description | Tap "Show more" on a long description | Full description expands |
| 116 | Collapse description | Tap "Show less" | Description collapses to the preview |
| 117 | HTML description rendering | Inspect a book with rich HTML description | Headings, lists, paragraphs and emphasis convert to readable markdown-styled text |
| 118 | Star rating display | Compare with the server | Rating matches the server value |
| 119 | Duration / narration info | Open audiobook and ReadAloud books | Duration and narration info shown where available |
| 120 | Detail error state | Force a load failure | Error view with a working Retry button |
| 121 | Detail loading state | Open detail on a slow network | Loading indicator, then content — no layout jump |
| 122 | Single-format book | Open an ebook-only book | Only the relevant format row and actions shown |
| 123 | Local-only book | Open an imported local book | Server-only actions (e.g. server sync) hidden or disabled, local actions present |
| 124 | Rotation on detail | Rotate the device | State preserved, no duplicate dialogs |

## 5. Series

| # | Test case | Steps | Expected |
|---|---|---|---|
| 125 | Series list renders | Open the Series tab | Series cards with names and book counts |
| 126 | Featured grouping | Inspect the list | "Featured" grouping renders as designed |
| 127 | Pull-to-refresh | Pull down on the Series tab | List refreshes without losing scroll at top |
| 128 | Open a series | Tap a series card | Series Detail opens |
| 129 | Series order labels | Inspect books in a series | Each entry shows its "Book #N" order label correctly |
| 130 | Back from series detail | Tap back | Returns to the Series tab |
| 131 | Series detail refresh | Pull down in Series Detail | Book list refreshes |
| 132 | Series detail search toggle | Tap the search icon | Search field appears in Series Detail |
| 133 | Series detail search | Type a query | Only matching books in the series remain |
| 134 | Series detail search empty | Type a query with no match | Clear empty state, search clearable |
| 135 | Open a book from a series | Tap a book row | Book Detail opens |
| 136 | Favourite from series detail | Tap the heart on a row | Toggles and reflects everywhere |
| 137 | Empty series state | Profile with no series | "No Series Found" empty state |

## 6. Reader — navigation & gestures (eBook / ReadAloud)

| # | Test case | Steps | Expected |
|---|---|---|---|
| 138 | Open the reader | Open an ebook | Loading screen shows and an always-visible close affordance while loading |
| 139 | Close via toolbar | Tap the ← in the reader toolbar | Reader closes back to the Detail screen |
| 140 | Back gesture exits | Use the system back gesture while reading | Reader closes and the position is saved |
| 141 | Middle tap toggles chrome | Tap the centre zone | Toolbar and controls appear; tap again to hide |
| 142 | Chrome auto-hide | Show the chrome and wait | Chrome hides automatically after the configured delay |
| 143 | Right tap zone | Tap the right zone | Next page |
| 144 | Left tap zone | Tap the left zone | Previous page |
| 145 | Tap-zone mapping swap | Set left = next page, right = previous page | Tap zones honour the mapping |
| 146 | Tap navigation disabled | Turn off "Tap navigation" | Zone taps no longer turn pages |
| 147 | Scroll reading mode | Set mode to Scroll | Continuous scrolling with correct text flow |
| 148 | Paginated mode | Set mode to Paginated | Discrete pages, page turns work |
| 149 | Auto reading mode | Set mode to Auto | Appropriate mode chosen automatically |
| 150 | Next page button | Use the on-screen next-page control | Advances one page |
| 151 | Previous page button | Use the on-screen previous-page control | Goes back one page |
| 152 | Volume up page turn | Press volume up | Turns the page per the configured mapping |
| 153 | Volume down page turn | Press volume down | Turns the page per the configured mapping |
| 154 | Volume navigation off | Disable volume-button navigation | Volume keys change system volume again |
| 155 | Next chapter | Use next-chapter control | Jumps to the start of the next chapter |
| 156 | Previous chapter | Use previous-chapter control | Jumps to the previous chapter |
| 157 | Chapter "…AndPlay" | Use the chapter-and-play variant | Chapter changes and narration starts |
| 158 | Pinch to zoom | Pinch in and out | Font size changes with a percentage overlay |
| 159 | Zoom undo | After a pinch, tap Undo on the snackbar | Font size reverts to the previous value |
| 160 | Double-tap a sentence | In a ReadAloud/TTS book, double-tap a sentence | Narration starts from that sentence |
| 161 | Double-tap timeout | Change the double-tap timeout slider | Recognition window changes accordingly (single vs double tap) |
| 162 | Position saved on exit | Read a few pages, exit | Position stored locally (and queued for sync) |
| 163 | Resume position | Reopen the same book | Opens at the saved position |
| 164 | Progress text | Enable reading time and current time | "N min left" and current time display per settings |
| 165 | Large EPUB performance | Open a very large book with long chapters | Page turns and scrolling stay responsive |
| 166 | Reader error state | Force an open failure (corrupt file) | `ReaderErrorView` with a working Retry |
| 167 | Rotation in reader | Rotate while paginated | Position preserved, no visual glitch or crash |

## 7. Table of contents & bookmarks

| # | Test case | Steps | Expected |
|---|---|---|---|
| 168 | Open TOC | Tap the Table of Contents button | Bottom sheet with the chapter list opens |
| 169 | Jump to a chapter | Tap a chapter | Reader jumps there and shows a "Jumped to chapter" undo snackbar |
| 170 | TOC prev/next buttons | Use the prev/next chapter buttons in the sheet | Chapter changes accordingly |
| 171 | Undo a chapter jump | Tap Undo on the snackbar | Returns to the previous position |
| 172 | Dismiss TOC | Tap outside or swipe down | Sheet closes, position unchanged |
| 173 | Open bookmarks sheet | Tap the Bookmarks button | `BookmarksSheet` lists existing bookmarks |
| 174 | Add a bookmark | Tap Add bookmark | Appears in the list with a "Bookmark added" snackbar and Undo |
| 175 | Undo bookmark add | Tap Undo | Bookmark is removed again |
| 176 | Duplicate bookmark | Bookmark the same location twice | "Already exists" snackbar, no duplicate row |
| 177 | Jump to a bookmark | Tap a bookmark | Reader jumps to the saved location |
| 178 | Rename a bookmark | Use rename, enter a new name | Name updates in the list |
| 179 | Delete a bookmark | Delete and confirm | Row removed |
| 180 | Reorder bookmarks | Drag a bookmark to a new position | Order persists |
| 181 | Bookmark save failure | Force a save failure | "Save failed" snackbar, no corrupt state |
| 182 | Bookmark limit | Add bookmarks up to the limit | "No more bookmarks" snackbar at the limit |
| 183 | Bookmark persistence | Add bookmarks, kill app, relaunch | All bookmarks and their order persist |
| 184 | Many bookmarks performance | Open a book with 50+ bookmarks | Sheet opens fast, scrolling smooth, order correct |

## 8. Reader settings (settings bottom sheet)

| # | Test case | Steps | Expected |
|---|---|---|---|
| 185 | Open reader settings | Tap Settings in the reader | Settings bottom sheet opens over the book |
| 186 | Close settings sheet | Close/dismiss | Reader visible again at the same position |
| 187 | Section expand/collapse | Expand and collapse each section (Reading Basics, Typography, Layout, Progress, Navigation, Read Aloud) | Sections animate open/closed, only one state retained per section |
| 188 | Fonts list expand | Expand the fonts list | All font options listed |
| 189 | Theme Light | Select Light | Reader re-renders light immediately |
| 190 | Theme Dark | Select Dark | Reader re-renders dark |
| 191 | Theme Sepia | Select Sepia | Reader re-renders sepia |
| 192 | Theme System | Select System | Follows the device appearance and updates on device change |
| 193 | Font size decrement | Tap the − stepper | Text shrinks, value updates |
| 194 | Font size increment | Tap the + stepper | Text grows, value updates |
| 195 | Manual font size entry | Open the numeric dialog, enter a value, OK; then open again and Cancel | OK applies the exact value; Cancel changes nothing |
| 196 | Built-in font families | Cycle Default, Serif, Sans Serif, Cursive, Fantasy, Monospace, Accessible DfA, IA Writer Duospace, OpenDyslexic | Each family renders correctly and is marked "Selected" |
| 197 | Custom font import | Tap "Add font", pick a font file | Font imports, appears in the list and is applied |
| 198 | Custom font markers | Import and select a custom font | "Custom" and "Selected" markers shown correctly |
| 199 | Boldness slider | Drag the font-weight slider | Text weight updates live in the preview |
| 200 | Text normalization switch | Toggle | Text normalisation applies/reverts |
| 201 | Publisher styles switch | Toggle | Publisher styling honoured or overridden |
| 202 | Line height slider | Drag | Line spacing updates live |
| 203 | Paragraph spacing slider | Drag | Paragraph spacing updates live |
| 204 | Horizontal margin slider | Drag | Side margins update live |
| 205 | Vertical margin slider | Drag | Top/bottom margins update live |
| 206 | Text alignment | Cycle Start, End, Center, Justify | Alignment applies live |
| 207 | Reading mode | Cycle Auto, Scroll, Paginated | Reader layout switches accordingly |
| 208 | Fullscreen mode | Toggle | Status bar hides/shows as configured |
| 209 | Progress bar visibility | Cycle Always, On Tap, Never | Behaviour matches exactly |
| 210 | Progress bar position | Toggle Top, Bottom | Bar moves |
| 211 | Progress indicator | Cycle None, Chapter, Book | Indicator content matches the selection |
| 212 | Chapter progress display | Cycle None, %, Pages, Position | Chapter progress label matches the selection |
| 213 | Show total progress | Toggle | Total progress shown/hidden |
| 214 | Show current time | Toggle | Current time shown/hidden |
| 215 | Show reading time | Toggle | Remaining reading time shown/hidden |
| 216 | Tap navigation switch | Toggle | Tap zones enabled/disabled (see 146) |
| 217 | Left tap action | Set to Next page then Previous page | Left zone follows the mapping |
| 218 | Right tap action | Set to Next page then Previous page | Right zone follows the mapping |
| 219 | Double-tap timeout slider | Drag to min and max | Timeout behaviour changes as described in the setting text |
| 220 | Volume navigation switch | Toggle | Volume key page turning on/off |
| 221 | Volume up action mapping | Change the mapping | Volume up performs the selected action |
| 222 | Volume down action mapping | Change the mapping | Volume down performs the selected action |
| 223 | TTS enabled switch | Toggle | TTS read-aloud availability changes |
| 224 | Keep screen on during audio | Toggle | Screen stays awake only when enabled during narration |
| 225 | Highlight colour swatch | Open the swatch picker and choose a colour | Highlight colour changes in the preview |
| 226 | Custom highlight colour | Open the ARGB colour dialog and enter values | Exact colour applied |
| 227 | Underline colour | Choose swatch and custom colours | Underline colour changes accordingly |
| 228 | Highlight style | Cycle Highlight, Underline, Both | Applied style matches |
| 229 | Live preview panel | Change several settings with the preview visible | Preview updates live for every change |
| 230 | Undo on setting change | Change a setting | "Setting changed — Undo" snackbar reverts the change |
| 231 | Settings persistence | Change several settings, kill app, relaunch | All reader settings restored exactly |

## 9. ReadAloud narration & audio controls

| # | Test case | Steps | Expected |
|---|---|---|---|
| 232 | Start narration | Open a ReadAloud book and start playback | Audio-ready overlay resolves, narration starts from the current position |
| 233 | Play / pause | Use the ReadAloud controls | Playback toggles immediately |
| 234 | Skip back 10s | Tap −10s | Position moves back 10 seconds |
| 235 | Skip forward 10s | Tap +10s | Position moves forward 10 seconds |
| 236 | Seek bar drag | Drag the seek bar | Position follows with correct time labels |
| 237 | Expand controls | Tap the chevron | Controls expand to the full panel |
| 238 | Collapse controls | Tap the chevron again | Controls collapse to the compact bar |
| 239 | Drag the controls bar | Drag the ReadAloud bar | Bar repositions smoothly and stays reachable |
| 240 | Dismiss controls | Swipe the bar down | Controls dismiss; text stays and playback state is kept |
| 241 | Playback speed | Cycle 0.5x, 0.75x, 1x, 1.25x, 1.5x, 1.75x, 2x | Narration speed changes exactly, selection persists |
| 242 | Sleep timer presets | Set 5, 10, 15, 30 and 60 minute timers | Countdown label shows remaining time |
| 243 | Sleep timer custom | Use Custom minutes with the stepper | Timer set to the entered duration |
| 244 | Sleep timer end of audio | Choose "End of audio" | Playback stops at the end of the current audio |
| 245 | Cancel sleep timer | Cancel an active timer | Timer cleared, countdown disappears, playback continues |
| 246 | Sleep timer countdown | Watch the timer | Live countdown label updates |
| 247 | Ending-soon prompt → Postpone | Wait for the ending-soon prompt, choose Postpone and minutes | Timer extended by the chosen amount |
| 248 | Ending-soon prompt → Let it end | Choose "Let it end" | Playback stops when the timer hits zero |
| 249 | Audio-only mode | Tap the headphones icon | Full-screen `ReadAloudAudioOnlyView` opens |
| 250 | Audio-only transport | Use play/pause and ±10s in audio-only mode | All transport controls work |
| 251 | Audio-only chapter tracks | Use prev/next "track" | Moves between chapters |
| 252 | Audio-only seek and speed | Drag seek, change speed | Both work and persist |
| 253 | Audio-only chapter list | Open the chapter list | Chapters listed with the current one marked; tap to jump |
| 254 | TTS toggle | On a TTS-based book, toggle TTS | Narration engine switches as designed |
| 255 | TTS play/pause | Control TTS playback | Works independently of audio narration |
| 256 | Voice button | Tap the voice button | Voice settings screen opens (section 10) |
| 257 | No-audio-narration message | Open a book without narration and try to play | "No audio narration" snackbar, dismissible |
| 258 | Notification permission prompt | First audio playback on Android | Permission rationale dialog appears before audio starts |
| 259 | Permission "Open Settings" | Tap it, grant permission in system settings, return | Playback can proceed afterwards |
| 260 | Permission "Try Again" | Deny, then tap Try Again | Prompt reappears or explains the denial without a crash |
| 261 | Position conflict in reader | Trigger local/remote conflict while reading | Dialog offers "Use This Device" and "Use Server" and both work |
| 262 | Background / locked playback | Lock the screen or background the app during narration | Audio continues; controls respond on the lock screen |
| 263 | Audio focus loss | Receive a call or start other audio | Narration pauses and resumes as designed |
| 264 | Audio-ready overlay | Start a book whose audio takes time to prepare | Overlay with progress, then playback starts |
| 265 | Keep screen on | Enable, play narration, leave idle | Screen does not sleep during narration only |

## 10. Voice & TTS settings (neural voices)

| # | Test case | Steps | Expected |
|---|---|---|---|
| 266 | Open voice settings | From the reader tap the voice button | `VoiceSettingsScreen` opens |
| 267 | Close voice settings | Tap back/close | Returns to the reader with narration state intact |
| 268 | Voice source tabs | Switch System, Kokoro, Supertonic | Correct voice list per tab |
| 269 | System voice grouping | Open the System tab | Voices grouped by language |
| 270 | Select a system voice | Pick a system voice | Applied to TTS narration immediately |
| 271 | Select a downloaded neural voice | Pick an already downloaded neural voice | Applied to narration |
| 272 | Start voice download | Tap Download on a neural voice | Package size shown, download starts |
| 273 | Download progress stages | Watch the download | "Downloading %", "Preparing %", "Finalizing" stages display |
| 274 | Download service notification | Observe Android during download | Foreground service notification shows progress |
| 275 | Background during download | Background the app mid-download | Download continues and completes |
| 276 | Delete a voice | Manage → Delete and confirm | Voice package removed, deleting status shown |
| 277 | Delete result | Verify after deletion | Voice no longer marked as downloaded, size freed |
| 278 | Supertonic terms — agree | Tap Download on Supertonic, agree to terms | Terms accepted, download starts |
| 279 | Supertonic terms — cancel | Cancel the terms dialog | No download starts, no state change |
| 280 | View full licence | Open the full licence dialog | Licence text loads and is scrollable |
| 281 | Preview a voice | Tap preview | Sample is spoken with the selected voice |
| 282 | Stop preview | Tap stop | Preview stops immediately |
| 283 | Edit preview text | Expand the editor and change text | New text is used for the preview |
| 284 | TTS rate slider | Drag the rate slider | Speaking rate changes |
| 285 | TTS pitch slider | Drag the pitch slider | Pitch changes |
| 286 | Download failure + retry | Simulate a failed download | Error shown with a working Retry |
| 287 | Delete failure + retry | Simulate a failed delete | Error shown with a working Retry |
| 288 | Low storage download | Fill storage then download a voice | Clear out-of-space error, no crash, app recoverable |
| 289 | Offline voice download | Airplane mode, tap download | Clear network error, no partial package left behind |

## 11. Audiobook player

| # | Test case | Steps | Expected |
|---|---|---|---|
| 290 | Open player (downloaded) | Play a downloaded audiobook | Player opens with cover, tracks and transport ready |
| 291 | Open player (not downloaded) | Play a streaming/undownloaded audiobook | Buffering/loading state, then playback |
| 292 | Close the player | Tap ← | Player closes, playback state as designed (continue or stop) |
| 293 | Play / pause | Tap the large button | Playback toggles |
| 294 | Buffering indicator | Seek or start on a slow network | Buffering spinner on the play button |
| 295 | Skip ±10s | Tap both skip buttons | Position moves ±10 seconds |
| 296 | Previous track at start | On track 1, tap previous | Button disabled or no-ops safely |
| 297 | Next track at end | On the last track, tap next | Button disabled or no-ops safely |
| 298 | Automatic track advance | Let a track finish | Advances to the next track and keeps playing |
| 299 | Seek bar drag | Drag the seek bar | Position follows, time labels update |
| 300 | Playback speed | Cycle every speed | Speed changes and persists |
| 301 | Track list tap | Tap a different track | That track starts playing |
| 302 | Track list auto-scroll | Change tracks | Inline list auto-scrolls to the playing track |
| 303 | Playing indicator | Observe the track list | Animated equalizer on the playing track |
| 304 | All-tracks sheet | Open "All tracks", select a track, dismiss | Sheet lists all tracks, selection works, sheet closes |
| 305 | Header collapse on scroll | Scroll the player content | Cover shrinks and toolbar collapses with fling snapping |
| 306 | Cover pulse | Observe while playing | Cover art pulses subtly while playing and stops on pause |
| 307 | Player error view | Force an error | Error view with a working Close |
| 308 | Rotation on player | Rotate during playback | Playback continues, layout adapts, no restart |
| 309 | Position persistence | Note the position, kill app, reopen | Resumes at the same position |
| 310 | Multi-track position | Stop mid-track 7, reopen | Resumes on track 7 at the saved offset |
| 311 | Long audiobook performance | Play a 50+ track audiobook | Track list scrolling and seeking stay smooth |

## 12. Mini player, media notification & background playback

| # | Test case | Steps | Expected |
|---|---|---|---|
| 312 | Mini player appears | Start playback and leave the player | Mini player shows above the bottom nav on every tab |
| 313 | Mini player play/pause | Tap play/pause on the mini player | Toggles playback |
| 314 | Mini player stop | Tap stop | Playback ends and the mini player disappears |
| 315 | Mini player open | Tap the mini player body | Opens the current book's reader/player |
| 316 | Media notification | Start playback on Android | Media notification appears with the session |
| 317 | Notification play/pause | Use the notification control | Playback toggles |
| 318 | Notification skip | Use the notification skip control (if present) | Skips ±10s or to the next track as designed |
| 319 | Notification tap → deep link | Tap the notification body | App opens the reader/player for that book |
| 320 | Notification metadata | Inspect the notification | Cover, title and chapter/track info correct |
| 321 | Lock screen controls | Lock the device during playback | Controls and metadata shown (Android notification / iOS Now Playing) |
| 322 | Headset / Bluetooth button | Press the play-pause button on a headset | Toggles playback |
| 323 | Android Auto | Connect Android Auto (optional) | Library browsable and playback controllable |
| 324 | Background playback | Play for 30+ minutes in the background | Audio continues without interruption |
| 325 | Playback conflict dialog | Open another book while one is playing | `PlaybackConflictDialog` appears and both choices behave as labelled |
| 326 | Mini player hidden when idle | Stop all playback | Mini player hidden on all tabs |

## 13. Downloads, imports, local books & offline

| # | Test case | Steps | Expected |
|---|---|---|---|
| 327 | Download an ebook | Download from Detail | Progress shown, completes, "Ready" chip |
| 328 | Download a large audiobook | Download a multi-track audiobook | Progress over a long transfer, completes fully playable |
| 329 | Download a ReadAloud book | Download | Complete package available offline |
| 330 | Concurrent downloads | Start 3 downloads at once | All progress correctly and complete |
| 331 | Cancel a download | Cancel mid-way | Stops and cleans partial data |
| 332 | Retry a failed download | Fail a download (drop network) then retry | Retry completes the download |
| 333 | Delete a download | Remove download and confirm | Local data gone, filter updates |
| 334 | Delete cache | Delete cache and confirm | Cache gone, book still listed |
| 335 | Downloaded filter accuracy | Toggle the Downloaded/Cached filter | Exactly the locally available books remain |
| 336 | Status chips | Observe during downloads | Ready / Downloading… chips always match the real state |
| 337 | Import a valid EPUB | Use the Import FAB and pick a valid EPUB | "Importing book…" dialog, then the book appears in the library |
| 338 | Import a corrupt file | Import a fake/corrupt EPUB | Clear error, no corrupt library entry |
| 339 | Import a duplicate | Import the same EPUB twice | Duplicate handled as designed (rejected or merged), no duplicates shown |
| 340 | Import a very large EPUB | Import a large file | Completes without ANR/crash |
| 341 | Import while offline | Airplane mode, import a local file | Import succeeds (local operation) |
| 342 | Read an imported book | Open an imported local book in the reader | Full reader functionality works |
| 343 | Delete a local book | Delete and confirm | Book and files removed |
| 344 | Full offline reading | Downloaded book, airplane mode, read to the end | Everything works offline |
| 345 | Full offline playback | Downloaded audiobook, airplane mode, play | Uninterrupted playback offline |
| 346 | Cached library offline | Airplane mode, browse the library | Cached library, series and detail data available |
| 347 | Offline mutations queue | Offline: change position, favourite, add bookmark; go online | Changes sync once connectivity returns |
| 348 | Airplane mode mid-download | Toggle airplane mode mid-transfer | Failure surfaced cleanly, retry works |
| 349 | Airplane mode mid-streaming | Toggle during streaming playback | Graceful buffering/error behaviour, recovery after reconnect |
| 350 | Storage full on download | Fill storage, start a download | Out-of-space error, no crash, app recoverable |
| 351 | Download notification | Watch Android during a download | Foreground download service notification with progress |
| 352 | Kill app during download | Kill mid-download, relaunch | State recovers (resume or clean retry), no phantom "Downloading…" forever |
| 353 | Downloaded badge | Check list and grid cards | Downloaded state visible on cards |
| 354 | Cache consistency | Repeat 20 download/delete cycles | No storage leak; remaining files match visible downloads |
| 355 | Server loss mid-download | Stop the media server mid-transfer | Failure surfaced cleanly with retry |
| 356 | Import with cloud linked | Import a book while the cloud account is linked | Import-backup attestation prompt appears (see 79) |

## 14. Cloud account & backup (single-device actions)

| # | Test case | Steps | Expected |
|---|---|---|---|
| 357 | Open Sync & Backup | Settings → Sync & Backup | `CloudAccountScreen` opens |
| 358 | Back | Tap back | Returns to App Settings |
| 359 | Sign in / Create account switch | Switch the mode | Correct form and submit label per mode |
| 360 | Email validation | Enter invalid emails then a valid one | Inline validation before submit |
| 361 | Password field | Type and toggle visibility | Masked by default, toggles correctly |
| 362 | Terms-of-service gating | Submit without ticking the ToS checkbox | Submit disabled until the checkbox is ticked |
| 363 | Create account | Create a new account | "Check your email" state shown as designed |
| 364 | Sign in success | Sign in with an existing account | Signed-in view with storage card and sync status |
| 365 | Wrong cloud credentials | Submit wrong password | Clear error, retry possible |
| 366 | Sign in with Google | Tap Sign in with Google | External browser OAuth completes and returns signed in |
| 367 | Google sign-in cancel | Cancel the OAuth flow | Returns to the form with no partial state |
| 368 | Storage usage card | Inspect the signed-in view | Usage bar and numbers render |
| 369 | Storage usage error | Simulate a fetch failure | Error message on the card, rest of screen usable |
| 370 | Sync status states | Observe across a sync | Disabled / idle / in progress / completed and last-success time all display correctly |
| 371 | Sync now | Tap Sync now | Sync runs with progress and updates the status |
| 372 | Auto-backup enable | Toggle auto-backup, tick attestation, Enable | Toggle turns on and stays on after restart |
| 373 | Auto-backup "Not now" | Toggle and choose "Not now" | Toggle stays off |
| 374 | Link profile dialog | Trigger the profile link confirmation | Confirming links the local profile to the cloud account |
| 375 | Sign out | Tap Sign out | Signed-out view; local library remains usable |
| 376 | Sign out during upload | Sign out while a backup transfers | Upload handled safely (cancelled or finished), no corruption |
| 377 | Delete account | Delete and confirm | Account removed, app returns to signed-out state |
| 378 | Re-auth required | Expire the cloud session | "Re-authentication required" message with a way to sign in again |
| 379 | Single-book backup | Use the Cloud backup button on Detail (see 102) | Backup created and visible in the signed-in view |
| 380 | Restore a backup | Trigger restore/download of a cloud backup | Book file and position restored on this device |
| 381 | Upload progress controls | Watch a long upload | Progress shown and cancellable |
| 382 | Backup-all from the library | Run Backup all (see 77-78) | Result dialog accurate |
| 383 | Replace vs duplicate | Back up the same book twice with Replace | Exactly one cloud copy remains |
| 384 | Cloud session persistence | Sign in, kill app, relaunch | Still signed in, sync status restored |

## 15. Statistics

| # | Test case | Steps | Expected |
|---|---|---|---|
| 385 | Statistics tab renders | Open the Statistics tab | Today, This Week, This Month, Total Time, Books Read, Total Sessions and streak cards render |
| 386 | Pull-to-refresh | Pull down | Values refresh |
| 387 | Today detail sheet | Tap Today | `StatisticsDetailBottomSheet` with today's books and session counts |
| 388 | This Week detail sheet | Tap This Week | Correct weekly aggregation |
| 389 | This Month detail sheet | Tap This Month | Correct monthly aggregation |
| 390 | Total Time detail sheet | Tap Total Time | All-time aggregation correct |
| 391 | Per-book list in sheets | Inspect the book list in each sheet | Per-book times add up to the card total |
| 392 | Session counts | Inspect session counts | Counts match the sessions list |
| 393 | Empty period | Open a period with no reading | "No books read in this period" empty state |
| 394 | Books Read sheet | Tap Books Read | List of completed books with details |
| 395 | Total Sessions sheet | Tap Total Sessions | `SessionsDetailBottomSheet` lists sessions |
| 396 | Session wpm speed | Inspect sessions | Per-session reading speed (wpm) displayed |
| 397 | No sessions yet | Fresh profile | "No sessions yet" empty state |
| 398 | Current streak sheet | Tap Current Streak | `StreakDetailBottomSheet` with day list |
| 399 | Longest streak sheet | Tap Longest Streak | Correct streak range and days |
| 400 | Streak day list accuracy | Compare with real reading days | Days marked correctly across month boundaries |
| 401 | No reading days | Fresh profile | "No reading days recorded" empty state |
| 402 | Dismiss sheets | Drag down and tap outside on each sheet | All four sheets dismiss cleanly |
| 403 | Stats update after reading | Read for 2 minutes, return to Statistics | Today's time and session count increase |
| 404 | Stats update after listening | Listen to an audiobook for 2 minutes | Listening time counted as designed |
| 405 | Fresh profile hint | New profile with no data | Empty statistics hint shown |
| 406 | Per-profile statistics | Read in profile A, switch to profile B | Profile B shows no data from profile A |
| 407 | Statistics navigation | Open as tab root vs pushed from App Settings | Back arrow hidden as tab root, shown when pushed |

## 16. Profiles

| # | Test case | Steps | Expected |
|---|---|---|---|
| 408 | Profiles row renders | Open App Settings | Current profile and profile tiles visible |
| 409 | Switch profile | Tap another profile | App switches: library, filters, reader settings and stats change per profile; nav stacks reset |
| 410 | Long-press profile | Long-press a profile tile | Context menu with Rename; Delete appears only when another profile will remain |
| 411 | Rename profile | Rename and confirm | New name shown everywhere |
| 412 | Delete profile | Delete and confirm | Profile and its data removed |
| 413 | Add profile | Tap Add profile, enter a name, confirm | New profile created and becomes available |
| 414 | Empty profile name | Attempt to submit a blank name | Confirm is disabled/blank submission is ignored; no profile is created |
| 415 | Duplicate profile names | Create two profiles with the same name | Allowed or rejected consistently — no confusion in switching |
| 416 | Long profile name | Enter a very long name | Name truncates/wraps gracefully in tiles and menus |
| 417 | Switch profile during playback | Switch while audio plays | Behaviour as designed (stop or continue) with no crash |
| 418 | Switch profile while reading | Switch with a book open | Reader closes or switches as designed; positions stay per profile |
| 419 | Per-profile filters | Set filters/sort/view mode in profile A, switch to B | Profile B has its own settings |
| 420 | Per-profile reader settings | Change reader settings in profile A, switch to B | Profile B keeps its own reader settings |
| 421 | Per-profile statistics | See 406 | No leakage between profiles |
| 422 | Delete the current profile | Delete the profile you are using | Safe behaviour (switch to another profile), no crash |

## 17. App settings

| # | Test case | Steps | Expected |
|---|---|---|---|
| 423 | Open reader settings from hub | Tap "Reader settings" | Reader settings sheet opens |
| 424 | Open last book on launch | Toggle on and off | Behaviour changes on next launch (see 11) |
| 425 | Show continue reading | Toggle off, then on | Shelf and floating bubble hidden, then restored; emit exactly one `show_continue_reading_toggled(is_enabled)` event per committed toggle; no profile or book identifiers |
| 426 | Clear current book | Tap "Clear current book" | Confirmation/snackbar, continue-reading cleared |
| 427 | Reading statistics row | Tap | Statistics screen opens |
| 428 | Servers row | Tap | Server Management opens |
| 429 | Sync & Backup row | Tap | Cloud Account screen opens |
| 430 | Enable file logging | Toggle on | Logs start being written |
| 431 | Only log crashes | Toggle | Non-crash logging suppressed while enabled |
| 432 | Share logs | Tap Share logs with logs present | System share sheet opens with a log file (Android FileProvider) |
| 433 | Share logs (none) | Tap Share logs with no logs | "No logs to share" message |
| 434 | Clear logs | Tap Clear logs | Logs removed with a "Logs cleared" snackbar |
| 435 | Version display | Inspect the version row | Matches the actual installed build version/code recorded in the run manifest |
| 436 | Settings persistence | Change all app settings, restart | Everything persists |
| 437 | Toggle animation (e-ink) | Change toggles on an e-ink device | Animations disabled, instant state change |
| 438 | Floating continue-reading bubble | Drag the bubble, release near an edge | Bubble snaps to a side, tap opens the reader, position persists |
| 439 | Bubble hidden with no current book | Clear the current book | Bubble hidden |

## 18. Server management

| # | Test case | Steps | Expected |
|---|---|---|---|
| 440 | Open Server Management | Settings → Servers | Server list renders |
| 441 | Back | Tap back | Returns to App Settings |
| 442 | Server card contents | Inspect a card | Server name, type and connection state shown |
| 443 | Add server | Tap Add server | Login screen opens (see section 2) |
| 444 | Log in on a logged-out server | Tap Log in | Login flow, then connected state |
| 445 | Log out | Tap Log out | Server shows logged-out state; other servers unaffected |
| 446 | Log out during activity | Log out with a download or playback running | Handled safely (stopped or finished) without a crash |
| 447 | Remove server | Remove and confirm | Server and its library entries handled as designed |
| 448 | Session expired state | Expire a session | Card shows session expired with a re-login action |
| 449 | Login failed state | Force a failed login | Card shows login failed with retry |
| 450 | Multi-server library | Two servers connected | Combined library with correct per-book badges and no duplicate entries |
| 451 | Empty state | Profile with no servers | "No servers" empty state with a hint |
| 452 | Server filtering | Use the server-type chips on the Books tab | Each server's books filterable |

## 19. Deep links & app lifecycle

| # | Test case | Steps | Expected |
|---|---|---|---|
| 453 | Reader deep link | Open `parrot://reader?serverId=…&bookUuid=…&bookType=ebook` | Correct book opens in the reader |
| 454 | Deep link with bad params | Open the reader link with missing/invalid params | Graceful error, app usable |
| 455 | Deep link while running | Open a link with the app in the background | `onNewIntent`/singleTask handles it, correct book opens, no duplicate activity |
| 456 | Deep link from notification | Tap a playback/download notification | Correct screen opens (see 319) |
| 457 | Google auth callback | Open `parrot://auth/callback` mid sign-in | Auth completes |
| 458 | Storyteller OAuth callback | Open `storyteller://…` callback | Auth completes (see 31) |
| 459 | Background and resume | Home button and return from every main screen | Exact state restored (tab, stack, scroll) |
| 460 | Rotation on all main screens | Rotate on Books, Series, Statistics, Settings, Detail, Player | No crash, no state loss |
| 461 | Process death restoration | Kill the process while reading, relaunch from recents | Reader (or library) restores safely thanks to fragment restoration guards |
| 462 | Background audio | See 262, 324 | Continues as designed |
| 463 | Background download completes | Background during a download | Completion notification arrives |
| 464 | Connectivity loss | Disable the network | `SyncResult.Offline` behaviour: pending changes counted, no error spam |
| 465 | Connectivity restore | Re-enable the network | Automatic sync recovery runs |
| 466 | Kill during sync | Kill the app mid-sync, relaunch | Sync recovers with no duplicate or lost changes |
| 467 | Memory pressure | Scroll large lists on a low-end device / with many covers | No OOM, images are recycled |
| 468 | Split screen / freeform | Open in split screen (Android) | Layout usable or degrades gracefully, no crash |
| 469 | Independent tab back stacks | Navigate deep in each tab and switch between them | Each tab keeps its own back stack |
| 470 | Tab scroll preservation | Scroll each tab, switch away and back | Scroll offsets preserved per tab |

## 20. States, errors & polish

| # | Test case | Steps | Expected |
|---|---|---|---|
| 471 | Splash spinner | Cold start | Splash shows a loading indicator |
| 472 | Books loading skeleton | Open Books on a slow network | Skeleton/loading state before content |
| 473 | Importing dialog | Import a book | "Importing book…" dialog with progress, then dismissed |
| 474 | Reader loading | Open a large book | Loading screen with a working close button |
| 475 | ReadAloud audio-ready overlay | Start narration on a slow device | Overlay with progress (see 264) |
| 476 | Player spinner | Play on a slow network | Buffering spinner on the play button |
| 477 | TTS voice preparing | Download a voice | "Preparing…" state text |
| 478 | Cloud storage loading | Open Sync & Backup | Loading state before the usage card |
| 479 | Backup and download progress | Run any transfer | Determinate progress everywhere a transfer runs |
| 480 | Empty library | See 80 | Correct empty state and CTA |
| 481 | Empty filtered list | See 48 | Correct empty state with Reset Filters |
| 482 | Empty series | See 137 | Correct empty state |
| 483 | Empty statistics | See 397, 401, 405 | Correct empty states per card/sheet |
| 484 | Empty servers | See 451 | Correct empty state |
| 485 | Reader error + retry | See 166 | Retry recovers |
| 486 | Detail error + retry | See 120 | Retry recovers |
| 487 | Player error | See 307 | Error view with Close |
| 488 | Login errors | See 17, 23, 24, 30 | All inline errors in plain language |
| 489 | TTS download/delete errors | See 286, 287 | Errors with working Retry |
| 490 | Network error wording | Trigger every network failure path | Consistent plain-language messages, no stack traces or codes |
| 491 | Touch targets & TalkBack | Enable TalkBack/VoiceOver, walk the whole app | All controls labelled and reachable, targets at least 48dp |
| 492 | Large system font | Set the OS font scale to maximum | No clipped or overlapping text on any screen |
| 493 | Landscape | Use the app in landscape | Layouts adapt (especially reader and player) |
| 494 | Theme contrast | Read in Light, Dark and Sepia | Text legible in all three, controls visible |

---

## Out of scope for this pass (to be covered separately)

1. **Cross-app / cross-device sync verification** — same account on two devices, state propagation between devices and app instances, round-trip consistency with the Storyteller / Audiobookshelf companion apps beyond the sign-in handshake, conflict resolution across devices.
2. Payments, subscriptions and IAP (not implemented).
3. Parental controls (not implemented).
4. Achievements / gamification (not implemented — streaks are covered in section 15).
5. Automated UI/instrumented tests (currently none exist; the repo only has unit tests).
6. Backend/server-side load testing and abuse handling (see `docs/parrot-cloud-abuse-runbook.md`).
7. Localisation testing beyond English (single locale).

## Execution order

Use the screen-by-screen order and ID mapping in [manual-qa-goal.md](manual-qa-goal.md#screen-execution-order-and-case-mapping). Finish each screen's action inventory, instrumentation, applicable baseline/extension tests and retests before signing it off. Apply the shared lifecycle and observability checks to every screen.

Regression smoke (run before every release): 2, 22, 39, 71, 89, 93, 139, 163, 232, 290, 312, 337, 364, 385, 409, 453, 459.

---

## Extended screen-by-screen cases

Run these alongside the matching baseline screen, not as a separate late pass. The instrumentation contract in `manual-qa-goal.md` applies to **every** case: inspect the action/outcome event, diagnostic context and recovery where relevant. Use only reachable features; conditional cases require an availability reason when marked N-A. Any missing fixture needed for a reachable feature is BLOCKED.

## 21. Samsung setup and reporting readiness

| # | Test case | Steps | Expected |
|---|---|---|---|
| 495 | Select the Samsung | List ADB devices; identify model/serial; run all commands with that serial | Actions target the physical Samsung, not an emulator or another phone |
| 496 | Identify the tested build | Record package, version/code, commit, variant, install source and signing/build configuration | UI version and package metadata match; later evidence identifies this build |
| 497 | Record device baseline | Record Android/One UI, orientation, font/display scale, navigation mode, battery settings, permissions and free space | Run is reproducible; later changes can be restored |
| 498 | Guest fixture readiness | Prepare a test profile with no server and a valid local EPUB | Guest reading and empty states can be tested independently |
| 499 | Mixed-media fixture readiness | Prepare ebook-only, audio-only, ReadAloud, multi-format, series and missing-cover fixtures | Expected formats and metadata documented for each fixture |
| 500 | Long-content fixtures | Prepare long-title, long-description, large-EPUB and many-track books | Stress cases use identified repeatable inputs |
| 501 | Failure fixtures | Prepare corrupt EPUB, unavailable server and constrained-storage/fault fixture | Failure is reproducible without damaging unrelated phone data |
| 502 | Capture a labelled run | Start logcat and record a known action with timestamp/case ID in the QA ledger | UI event and logs can be correlated; no existing log history erased unnecessarily |
| 503 | Debug-provider verification | Perform a known tracked action in a debug build | Local event visible; no claim that the debug provider sent it to Firebase |
| 504 | Firebase-provider verification | Repeat the action in a Firebase-enabled QA build | Correct provider/configuration confirmed; event reaches the intended Firebase project |
| 505 | Analytics DebugView delivery | Enable debug delivery for the actual package; open Books then Statistics | Expected named screen/action events arrive for the selected QA device |
| 506 | Controlled non-fatal delivery | Trigger one reproducible handled reader/file failure in the Firebase-enabled build | One actionable non-fatal arrives with operation context and useful stack/cause |
| 507 | Symbolication | Inspect the controlled failure on an optimized build | Stack resolves to useful source locations; mapping upload/configuration verified |
| 508 | Breadcrumb correlation | Navigate Books → Detail → Reader, then induce the controlled failure | Diagnostic history identifies entry route, operation stage and terminal failure |
| 509 | Sensitive-data inspection | Use distinctive test search/profile/preview text; inspect local and Firebase payloads | Private content, credentials, tokens, raw paths and full URLs absent |
| 510 | Exception deduplication | Trigger one failure handled by repository and UI layers | One report for the operation; breadcrumbs retain context without duplicate issues |
| 511 | QA traffic classification | Inspect event build/environment identification and reporting policy | Test activity can be excluded from production feature-use analysis |
| 512 | Offline telemetry resilience | Disconnect, perform local actions, reconnect | Logging never blocks UI; queued delivery follows SDK policy without app retry floods |
| 513 | Logging overhead | Compare normal reading, scrolling and playback with diagnostics enabled | No material lag, log-volume explosion or repeated identical error bursts |
| 514 | End-of-run cleanup | Disable Firebase debug property; restore changed phone settings and network | Test-only configuration removed; retained artifacts match the run manifest |

## 22. Startup, Welcome and login extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 515 | Warm launcher return | Open app, press Home, tap launcher icon | Existing session resumes without a duplicate screen/stack |
| 516 | Launcher rapid taps | Launch repeatedly while startup is still loading | One usable app instance; no duplicate onboarding or navigation |
| 517 | Upgrade with local data | Install a compatible newer build over a fixture build; reopen | Local library, positions, profiles, bookmarks and settings survive migrations |
| 518 | Missing last-book file at startup | Enable reopen-last-book; make its test file unavailable; relaunch | Recoverable error/fallback, no launch loop; diagnostic explains failed restoration |
| 519 | Deleted last-book reference | Delete the last book using supported UI; relaunch with reopen enabled | Safe landing screen, no stale automatic navigation |
| 520 | Back during startup | Press system Back while startup work is in progress; relaunch | No partial initialisation or stuck splash on next launch |
| 521 | Repeated Welcome action | Double-tap Get Started, then separately repeat guest entry | One navigation and one accepted-action event per transition |
| 522 | Welcome root Back | Use system Back from fresh Welcome; open app again | Clean exit/background behaviour; onboarding remains usable |
| 523 | Login keyboard Back | Focus password, press Back once, then again | First closes IME; subsequent Back follows login entry route |
| 524 | Login from Add server Back | Open login from Settings → Servers and cancel | Returns to Servers, not unexpectedly to first-run Welcome |
| 525 | Login editing after rejection | Submit incorrect credentials, correct just the password and retry | Error clears appropriately; successful account created once |
| 526 | Login submit race | Tap Sign In rapidly and also press IME action while loading | Only one in-flight accepted request; no duplicate accounts |
| 527 | Login Back while loading | Submit on a delayed connection, leave screen, wait for response | No ghost navigation or stale error overlay on another screen |
| 528 | Switch server type after input | Fill form, change Storyteller/Audiobookshelf selection | Compatible fields remain or reset predictably; request uses selected type |
| 529 | Clipboard and whitespace | Paste URL/username with boundary whitespace; enter password with spaces | URL/user validation is clear; password is not silently altered |
| 530 | Untrusted or expired TLS certificate | Sign in to a controlled invalid-certificate endpoint | Clear secure-connection failure, retry available, no indefinite loading |
| 531 | Wrong endpoint response | Use reachable server returning HTML or malformed auth response | Safe error and sanitized diagnostic; no parse crash |
| 532 | OAuth callback replay | Complete a test login, then deliver the same callback again | Callback not applied twice; no duplicated account or navigation |
| 533 | OAuth stale/mismatched callback | Cancel auth, then deliver a stale or unmatched callback fixture | Callback rejected safely; current profile/account unchanged |
| 534 | Authentication event semantics | Compare invalid input, rejection, cancellation and successful login | Outcome categories distinguish each; credentials never logged; no success before persistence |

## 23. Home navigation and Books extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 535 | Every tab pair | Switch between each ordered pair of Books, Series, Statistics and Settings | Correct selected state/content and source/destination event for each transition |
| 536 | Reselect current tab | Tap already selected tab repeatedly at root and with a nested route | Documented root/stack behaviour, no duplicate screen instances |
| 537 | Root Back | Press system Back from each tab root | Consistent documented navigation/exit; no blank destination |
| 538 | Deep stack Back | Books → Detail → Reader → sheet; press Back step by step | Sheet dismisses first, then reader, detail and root; saved position preserved |
| 539 | Predictive Back cancel | Where supported, begin edge Back then cancel it on a nested screen | Screen/state unchanged; no committed navigation or false screen-view event |
| 540 | Predictive Back commit | Complete the edge gesture on the same screen | One pop and one destination exposure; no double navigation |
| 541 | Fast tab changes | Tap tabs rapidly while lists load and audio is playing | Final selected tab correct; no mixed content, crashes or audio duplication |
| 542 | Continue-reading stale target | Remove/revoke availability of the current test book; tap shelf/bubble | Action explains unavailable content and allows recovery; no crash loop |
| 543 | Bubble placement after rotation | Drag bubble, rotate, open keyboard where possible, rotate back | Bubble remains reachable and avoids blocking required controls |
| 544 | Bubble suppression | Visit Detail, Statistics, App Settings, Servers, Sync & Backup and reader settings | Bubble hidden on the destinations specified by current navigation rules |
| 545 | Bubble plus mini player | Enable continue reading and start playback; browse all tabs | Overlays do not overlap navigation or each other's controls |
| 546 | Search keyboard dismissal | Search, press Back to dismiss keyboard, inspect query and results | Keyboard closes without unintended navigation/query loss |
| 547 | Search whitespace and case | Search a known title with different case and boundary whitespace | Results follow documented normalization; no unexpected empty/error state |
| 548 | Search punctuation and Unicode | Use apostrophes, accents, emoji and non-Latin fixture names | Safe filtering; supported matching behaves consistently |
| 549 | Long search input | Paste a long query and clear it | Input stays responsive and fully clearable; no raw query in telemetry |
| 550 | Search after data refresh | Keep a query active, refresh library | Query remains coherent and results reflect refreshed data |
| 551 | Search plus sort/filter | Search, apply two filters, change sort and toggle list/grid | Correct combined results and stable selected controls |
| 552 | Search result Back restoration | Open a result, return using toolbar and system Back in separate runs | Query, filters, layout and list position retained |
| 553 | Filters outside-tap dismiss | Change a quick filter then dismiss by outside tap | Applied state matches visible chips and documented immediate-apply behaviour |
| 554 | Filters system-Back dismiss | Open filter sheet and press Back | Sheet closes before screen; no accidental reset |
| 555 | Empty combined filters reset | Combine incompatible filters, use empty-state Reset | Full appropriate library restored; chips/search reset as specified |
| 556 | Sort ties and missing metadata | Sort fixture books sharing values or lacking author/rating/date | Stable ordering with documented placement of missing values |
| 557 | Rapid favourite changes | Toggle one heart repeatedly online and offline | Final local state matches final accepted action; no stuck indicator |
| 558 | Favourite persistence failure | Inject a local save failure when favouriting | UI rolls back or clearly marks failure; retry works; failure reported once |
| 559 | Cover failure | Load unavailable/corrupt cover URLs while scrolling | Placeholder shown; text/actions work; per-image failures do not flood Crashlytics |
| 560 | Refresh while leaving screen | Start refresh, switch tab/open Detail, return | Refresh reaches terminal state without stale overlay or lost cached content |
| 561 | Import picker cancellation | Open picker and cancel with system Back | Library unchanged; no import success/failure exception falsely emitted |
| 562 | Backup-all dialog dismiss variants | Open attestation; cancel via button, Back and outside tap if supported | No transfer queued without confirmation; dismissal analytics classified as cancellation |
| 563 | Current-read shelf after deleting book | Delete the book referenced by shelf and return to Books | No broken shelf target; subsequent selection works |
| 564 | Library analytics accuracy | Perform one search, filter, sort, layout change and book open; rotate | Correct source/committed events; rotation/recomposition does not multiply usage |

## 24. Book detail extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 565 | Detail from each source | Open same book via Books, Series, continue reading's supported route and deep link where applicable | Correct book/format and source attribution; Back returns to actual entry context |
| 566 | Rapid Read/Play tap | Tap primary action repeatedly while opening | One reader/player; one accepted operation and terminal outcome |
| 567 | Back during detail loading | Open uncached detail on delayed network and immediately Back | No late forced navigation; prior list remains usable |
| 568 | Book removed while detail open | Use controlled fixture deletion/invalidation then act on stale detail | Explain unavailable book; safe return/refresh; no wrong book operated on |
| 569 | All metadata absent | Open fixture missing cover, description, tags, rating and series | Useful fallback layout; no empty actionable controls or crash |
| 570 | Very long metadata | Open long title/authors/tags and expand description | Content readable by scrolling; primary actions remain reachable |
| 571 | Description link handling | Tap links if rendered as interactive; return from browser | Safe supported link handling and preserved detail state; unsupported links are noninteractive |
| 572 | Multiple formats simultaneously | Download one format while opening/deleting a different available format | Independent status and correct target; no cross-format file deletion |
| 573 | Delete while actively reading/playing | Request deletion of the currently used local content | Documented block/stop/confirmation protects active state; no silent corruption |
| 574 | Delete dialog system Back | Open each supported delete confirmation and press Back | Nothing deleted; screen remains at same position |
| 575 | Delete dialog double confirm | Confirm deletion with rapid repeated taps | One deletion, one result, no second misleading error |
| 576 | Partial format cleanup | Delete ebook from a multi-format book | Audio/ReadAloud assets still match their actual availability; shared assets handled correctly |
| 577 | Primary action after failed download | Fail download, return to detail, retry | Button never claims Read/Play on an unusable partial file |
| 578 | Local progress without server | Read offline, return to Detail | Local progress updates consistently without needing remote state |
| 579 | Detail observability | Trigger open/download/delete success and one controlled failure | Events identify format/action/source; failure breadcrumbs identify correct operation and recovery |

## 25. Series and conditional Authors extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 580 | Series list offline | Cache list, disconnect, open Series and refresh | Cached series usable; refresh ends with recoverable message |
| 581 | Series Back restoration | Scroll series list, open detail/book, Back twice | Original series list and offset restored |
| 582 | Series fractional/missing order | Use series with 0, 1.5, duplicate and missing order numbers | Stable documented ordering; labels do not invent incorrect positions |
| 583 | Series empty after deletion | Remove fixture books through supported local actions then revisit series | Empty/removal state accurate; no stale crash-prone rows |
| 584 | Series duplicate names | Browse same-name series from distinct sources | Grouping follows source identity rules; selections open correct books |
| 585 | Series detail search Back | Search, open book, return, then dismiss search keyboard | Query/list position preserved; keyboard dismissal does not pop detail |
| 586 | Series loading interruption | Refresh, rotate and background/resume | No duplicate refresh, stuck spinner or late navigation |
| 587 | Authors reachability audit | Inspect installed navigation and source route registration | Record reachable entry or N-A with disabled-route evidence; do not assume a fifth tab |
| 588 | Authors list rendering, if exposed | Open populated list; scroll long names and alternate file-as values | Names readable, stable rows, no excessive delayed item appearance |
| 589 | Authors empty state, if exposed | Open a profile with no author records | Clear empty state with usable navigation |
| 590 | Author selection, if exposed | Tap an author, inspect listed books | Correct author/title and only matching books/media badges |
| 591 | Author detail Back, if exposed | Open book from author; toolbar Back then system Back | Correct detail → author → source sequence; scroll restored |
| 592 | Author refresh offline, if exposed | Cache author list/detail, disconnect and refresh both | Cached content retained, refresh terminates, recovery possible |
| 593 | Author ambiguity, if exposed | Use same-name authors and a multi-author book | Membership follows identity rules without duplicate/missing selectable rows |
| 594 | Group-screen telemetry | Open/refresh series and any reachable author screen; induce query failure | Correct screen/source/selection events and useful bounded failure context |

## 26. Reader, TOC and bookmark extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 595 | Close during EPUB preparation | Open large EPUB, use loading-screen close before completion | Returns immediately/safely; no late reader reappearance or false successful-open event |
| 596 | Repeated reader open/close | Open and close same book ten times using both Back methods | No growing loading delay, duplicate sessions or lost position |
| 597 | First-page previous | At book start, use left tap/button/gesture configured for previous | Safe boundary behaviour; position never negative |
| 598 | Last-page next | At end, attempt next repeatedly | Stable end-of-book state; completion recorded once |
| 599 | Cross-chapter page turn | Turn forward and backward across a chapter boundary | No skipped/duplicated content; locator and progress correct |
| 600 | Rapid page-turn input | Tap/swipe quickly, then stop | Final visible page and saved locator agree; UI remains responsive |
| 601 | Gesture versus selection | Long-press/select text where supported, then tap/swipe outside | Supported selection actions work; no unintended page jump or stuck selection |
| 602 | Internal EPUB links | Tap a supported internal chapter/footnote link; use offered return path | Correct destination and recoverable prior reading location |
| 603 | External EPUB links | Open a supported external link and return from browser | Reader restores position and does not count background time as active reading |
| 604 | Image-heavy EPUB | Read fixture with large images and tables | Content fits/scrolls according to reader mode; no unreadable clipping or memory crash |
| 605 | Missing EPUB resource | Open fixture with missing image/font/resource | Readable fallback where possible; severe rendering failures diagnosed, not silent blank pages |
| 606 | Malformed chapter recovery | Reach a broken chapter in otherwise valid fixture | Clear recoverable failure or safe fallback; other navigation remains possible |
| 607 | Reader offline cold reopen | Force-stop after saving downloaded-book position; reopen offline | Local file and position load without requiring a server |
| 608 | Screen off while silent reading | Read, lock phone for two minutes, unlock and leave reader | Position preserved; locked interval not inflated into active reading time |
| 609 | Position save failure | Inject local persistence failure on reader exit | Failure observable; no false saved-success signal; usable retry/recovery path |
| 610 | Position after layout changes | Note passage, change font/mode/orientation, close and reopen | Same logical passage restored, not stale page-number-only position |
| 611 | Reader lifecycle session count | Background/resume/rotate repeatedly during one reading period | Session accounting follows defined rules, without duplicated time or phantom sessions |
| 612 | Reader opening diagnostics | Compare successful, corrupt and interrupted opens | Duration/stage/outcome distinguishes usable content, failure and cancellation |
| 613 | TOC nested entries | Open hierarchical TOC fixture; expand/select nested entries if supported | Hierarchy understandable; selected item targets correct section |
| 614 | TOC current chapter marker | Move chapters through reading, reopen TOC | Current marker and visible selection match actual location |
| 615 | TOC invalid anchor | Select fixture entry with an invalid anchor | Safe error/fallback; reader not stranded or falsely marked at target |
| 616 | TOC jump then exit | Jump, immediately Back out of reader and reopen | Final chosen location saved exactly once |
| 617 | TOC Undo after another action | Jump then navigate again before Undo expires | Undo follows documented validity rules; never restores an unrelated book/location |
| 618 | Empty TOC fixture | Open a readable EPUB without useful TOC entries | Empty/fallback chapter UI; reader still navigable |
| 619 | Bookmark rename cancellation | Edit a bookmark name, cancel via button and Back separately | Original name preserved; cancellation not counted as successful rename |
| 620 | Bookmark blank/long/Unicode name | Submit blank, very long and non-Latin names in separate runs | Consistent validation/limits; no clipped confirmation controls or crash |
| 621 | Bookmark delete cancellation | Open delete confirmation, dismiss without confirming | Bookmark and order unchanged |
| 622 | Bookmark rapid add/Undo | Repeatedly tap Add at one locator, then Undo | Defined duplicate handling; no unrelated bookmark removed |
| 623 | Bookmark locator after typography | Add bookmark, change font/layout, jump to bookmark | Same logical passage reached despite different pagination |
| 624 | Bookmark transaction failure | Inject rename/delete/reorder failure separately; reopen sheet | No falsely committed state; useful non-fatal context and successful retry |

## 27. Reader settings extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 625 | Settings entry-point equivalence | Open reader settings from reader and Settings hub | Same persisted configuration; events distinguish entry source |
| 626 | Settings system Back | Open numeric/color/font dialog inside settings; press Back twice | Inner dialog closes before settings sheet; reader position unchanged |
| 627 | Numeric font-size invalid input | Enter blank, letters, negative, zero and oversized values | Explicit validation/clamping per bounds; no unusable text layout |
| 628 | Numeric keyboard overlap | Edit numeric value with largest supported font/display scaling | Value, errors and confirm/cancel remain reachable above IME |
| 629 | Slider endpoints | Move each setting slider to minimum then maximum | Values stay within bounds; preview and real reader agree |
| 630 | Slider event volume | Drag a typography slider slowly through many intermediate values | Smooth preview; bounded committed-value analytics, not hundreds of usage events |
| 631 | Theme while sheet open | Switch system light/dark with System theme selected | Sheet, preview and reader update consistently without position loss |
| 632 | Invalid custom font | Select corrupt/unsupported font fixture | Clear error; previous font still works; failed import diagnosed |
| 633 | Font picker cancellation | Open font picker and cancel | No new font/selection; no exception for ordinary cancellation |
| 634 | Duplicate font import | Import identical font twice | Defined deduplication/identity; no unusable duplicate selection entries |
| 635 | Font persistence offline | Import/apply font, restart offline and open book | Font available without original picker URI/provider |
| 636 | Invalid custom ARGB | Enter incomplete, invalid and out-of-range color values | Validation prevents broken color state; cancellation restores prior color |
| 637 | Several changes then Undo | Change two different settings and use most recent Undo | Only intended setting reversed; preview/persistence match |
| 638 | Settings persistence failure | Inject preference/font-save failure then reopen settings | Failed commit identified; UI does not falsely claim persistent success |
| 639 | Volume navigation with audio | Enable volume-page controls, play narration, test both hardware keys and disable mapping | Defined behaviour for reading versus volume; restored system volume control when disabled |

## 28. ReadAloud, sleep timer and voice extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 640 | ReadAloud alignment | Play across several paragraph/chapter boundaries | Highlight and displayed passage follow narration within documented tolerance |
| 641 | Seek during preparation | Start narration on delayed content, immediately seek then pause | Latest accepted intent wins; no surprise playback from stale position |
| 642 | Text/audio mode roundtrip | Switch reading → audio-only → reading repeatedly during playback | One audio session, stable locator, speed and controls |
| 643 | ReadAloud missing segment | Reach a controlled missing/corrupt narrated segment | Recoverable failure or documented fallback; gap has actionable diagnostic context |
| 644 | Sleep timer natural expiry | Run a short real timer to zero while app remains foreground | Audio actually stops once; expiry distinguished from manual pause |
| 645 | Sleep timer background expiry | Start short timer, lock phone until after expiry | Audio stops as scheduled; remaining-time UI correct on return |
| 646 | Timer replace and cancel race | Start timer, replace duration, cancel near old expiry | Old timer cannot stop playback later; one active timer at most |
| 647 | Timer input limits | Try minimum, zero, negative and excessive custom durations where editable | Valid bounded duration or clear validation; no immediate unexpected stop |
| 648 | System TTS unavailable | Disable/remove test engine or choose unavailable voice using supported system controls | Helpful setup/recovery state; reader remains usable without speech |
| 649 | Voice preview while narration plays | Start preview during ongoing reading audio | Defined pause/exclusivity behaviour; no overlapping uncontrolled voices |
| 650 | Leave during voice preview | Start sample, Back out and reopen voice settings | Preview stops/continues only as documented; no orphan speech or stale button |
| 651 | Blank/long preview text | Clear sample then enter long Unicode text and preview | Clear empty validation and responsive playback; text not sent as analytics payload |
| 652 | Change engine during playback | Switch System/Kokoro/Supertonic where available | Old engine released; selected engine resumes or requests Play consistently |
| 653 | Remove selected voice | Delete selected downloaded voice and attempt narration | Safe fallback/reselection prompt; no repeated engine crash |
| 654 | Voice download repeated tap | Tap Download repeatedly then navigate away/back | One transfer; consistent stage/progress; no duplicate package writes |
| 655 | Voice download cancellation/interruption | Use cancel if offered; otherwise interrupt network then retry | Explicit terminal state; retry yields validated complete model |
| 656 | Corrupt downloaded model | Use controlled invalid model/checksum fixture then load voice | Corruption detected; recovery offers redownload; no persistent startup loop |
| 657 | Model preparation process death | Kill background process during preparation, relaunch | Partial model not treated as ready; safe cleanup/retry |
| 658 | Neural TTS long session | Narrate for 30 minutes with chapters and screen-off interval | Stable speech, no runaway memory/stall; failures have engine/stage context |
| 659 | Voice telemetry semantics | Select, preview, stop, download and delete a voice; induce one failure | Usage/outcomes distinguish engine and operation; preview text absent; no per-utterance flood |

## 29. Audiobook and media-control extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 660 | Zero/invalid duration | Open controlled track with absent/invalid duration | No division by zero/NaN UI; seek disabled or bounded appropriately |
| 661 | Seek endpoints | Seek to start/end of first, middle and final tracks | Correct clamping and transition; no negative or beyond-duration offset |
| 662 | Rapid transport race | Quickly alternate Play/Pause/Next/Previous and seek | Final accepted state wins; one session and coherent metadata |
| 663 | End of final track | Let final track finish naturally | Playback ends cleanly; completion/session recorded once; no endless buffering |
| 664 | Missing middle track | Play fixture whose next track is unavailable | Clear failed-track handling with retry/skip path; other controls stay usable |
| 665 | Pause during buffering | Start a slow stream, tap Pause before data arrives | Playback does not unexpectedly start after user paused |
| 666 | Speed across tracks and reopen | Set non-default speed, cross track boundary, close/reopen | Speed persists according to defined scope; displayed and actual rate agree |
| 667 | Headphone disconnect | Unplug wired headset or disconnect Bluetooth while playing | Appropriate noisy-route handling; no unexpected loudspeaker continuation |
| 668 | Output route switch | Switch between speaker and available Bluetooth device | Defined continuation without double audio, stale controls or lost offset |
| 669 | Competing media focus return | Start another audio app, then explicitly resume Parrot | Focus loss/gain handled consistently; no unwanted simultaneous playback |
| 670 | Notification after profile/book switch | Switch active book/profile, inspect and tap notification | Metadata/target belong to current allowed session, not stale profile content |
| 671 | Notification permission denied | Deny permission and exercise allowed playback path | Android-version-appropriate media behaviour; app not trapped in rationale loop |
| 672 | Stop from every surface | Stop through available player/mini-player/notification controls in separate runs | Audio stops; session/notification/mini player agree; no orphan service |
| 673 | Recents dismissal versus force-stop | Test swipe-away and force-stop separately during playback, then reopen | Behaviour matches Android/service policy; force-stop ends work; saved state recoverable |
| 674 | Media command analytics | Perform identical Play/Pause from app, notification, lock screen and headset | Source attributed correctly; one accepted-command/outcome event per action |

## 30. Download, import and offline extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 675 | Download already ready | Repeat available download action on already downloaded content | No unnecessary duplicate files/work; state remains accurate |
| 676 | Download repeated start/cancel | Start, cancel and restart rapidly on same format | Single current operation; cancelled callback cannot overwrite new success |
| 677 | Download finish/cancel race | Cancel as progress reaches completion | One consistent outcome; ready file valid or safely absent |
| 678 | Unknown content length | Download fixture without server content-length | Honest indeterminate progress; no fake percentage, overflow or stuck completion |
| 679 | Truncated successful HTTP response | Serve an incomplete media payload with a nominal successful response | Validation rejects unusable file; not marked Ready; retry available |
| 680 | Interrupted multi-track package | Fail midway through audiobook download then retry | Full package eventually playable; completed tracks reused/cleaned consistently |
| 681 | Account expiry during transfer | Expire test session while downloading | Clear authentication recovery; partial data not presented as complete |
| 682 | Wi-Fi/mobile handover | Change available network during transfer | Completes or offers recoverable retry; no duplicated/corrupt file |
| 683 | Download locked phone | Start long transfer, lock and leave idle, then return | Honest terminal/progress state under Samsung background policy |
| 684 | Low-storage recovery | Induce controlled storage failure, free test allocation and retry | Retry succeeds; previous partial data cleaned; no permanently disabled button |
| 685 | Import provider URI lifetime | Import via Android document provider, restart app and open offline | App retains accessible content; no dependence on expired temporary picker permission |
| 686 | Import inaccessible document | Choose a test document whose provider fails/revokes access mid-read | Clear import failure; no phantom library entry |
| 687 | Import zero-byte EPUB | Pick an empty `.epub` fixture | Validation error; local database/files remain consistent |
| 688 | Import extension/MIME mismatch | Pick supported picker-visible mismatch fixtures | Actual content validated; no unchecked assumption based on filename |
| 689 | Same title, different imports | Import distinct valid EPUBs sharing a title | Content identity handled correctly; no accidental overwrite solely by title |
| 690 | Import while app backgrounded | Begin large import, Home/resume and then test process interruption separately | Defined completion/recovery; no permanent Importing dialog |
| 691 | Import local-write failure | Inject storage/database failure during final import commit | No orphan book/file or false success; diagnostics identify failed stage |
| 692 | Delete-download storage accounting | Record app storage, download known fixture, delete and reopen app | Reclaimable file usage released reasonably; library metadata retained as intended |
| 693 | Offline unavailable format | With only ebook downloaded, try unavailable audio/ReadAloud | Clear availability message; downloaded ebook remains usable |
| 694 | Transfer event integrity | Run success, failure, cancel and retry; inspect telemetry | One terminal outcome per attempt, correct type/source/size bucket/timing; no per-byte flood |

## 31. Statistics extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 695 | Repeated statistics entry | Enter via tab and Settings row, switch away/back repeatedly | Correct Back affordance and source analytics; no duplicated sessions/totals |
| 696 | Passive reader time | Leave silent reader idle for known interval using defined activity rules | Totals follow documented active-reading policy rather than unexplained wall time |
| 697 | Paused audio time | Listen one minute, pause two minutes, resume one minute | Paused interval excluded from listening time under defined accounting rules |
| 698 | Background audio accounting | Listen with screen locked for measured interval | Actual listening counted once despite background service/UI lifecycle changes |
| 699 | ReadAloud double-count guard | Read with synchronized narration for measured interval | Combined read/listen totals follow explicit policy, not accidental double counting |
| 700 | Midnight boundary | Use controlled clock/date fixture around local midnight | Sessions split/attributed by documented day rules; totals not lost or duplicated |
| 701 | Week/month/year boundaries | Inspect known sessions straddling boundaries | Today/Week/Month totals and details include exactly the intended sessions |
| 702 | Time-zone/daylight-saving change | Change test timezone or use boundary fixture; reopen statistics | Streak/date grouping coherent; duration never negative; restore device settings afterwards |
| 703 | Incomplete session recovery | Kill background process after a known active interval; reopen Statistics | Committed session state preserved; no huge duration from stale start time |
| 704 | Zero-duration and extreme WPM | Inspect near-instant session and sparse-content fixture | No infinity/NaN, misleading negative values or chart/layout crash |
| 705 | Books Read completion count | Finish book, reopen/reread end repeatedly | Completion count follows defined unique-book policy; no extra completion per reopen |
| 706 | Delete a read local book | Read imported fixture, delete book, inspect historical sheets | Defined history retention/removal; totals/detail rows remain internally consistent |
| 707 | Statistics detail Back variants | Open each sheet; dismiss via Back, outside tap and drag where supported | Correct underlying screen/scroll; no extra sheet opens or unintended tab pop |
| 708 | Statistics data failure | Inject aggregation/query failure and retry/refresh | Clear failure rather than misleading zero totals; useful non-fatal and successful recovery |
| 709 | Statistics telemetry coverage | Open every card/detail and refresh; rotate and dismiss | Correct detail-type/source events once per exposure; no private book/session text in payloads |

## 32. Profiles, App settings and server extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 710 | Create-profile cancellation | Type name, cancel via button, Back and outside tap if supported | No profile created or false success event |
| 711 | Rename-profile cancellation | Edit current/other profile, cancel | Original names and selection preserved |
| 712 | Delete-profile cancellation | Open destructive confirmation and cancel through each supported exit | Profile/data unchanged; cancel correctly classified |
| 713 | Profile name normalization | Try spaces-only, leading/trailing spaces, Unicode and long names | Consistent validation/display; no raw profile name sent to analytics |
| 714 | Delete last remaining profile | Attempt to delete only profile through exposed action | Protected default/replacement policy; app always retains a usable context |
| 715 | Rapid profile switches | Alternate profiles while libraries are loading | Final selection owns all visible data; no stale callback writes into another profile |
| 716 | Switch during download/import | Start fixture work in A, switch to B, wait, return | Work remains owned by originating profile; no leaked book/progress in B |
| 717 | Switch with settings sheet open | Open settings/profile flow, switch and revisit reader settings | Correct profile preferences; no stale sheet committing into wrong profile |
| 718 | Profile persistence failure | Inject create/rename/delete/switch storage failure in separate runs | No partial active-profile state; clear recovery and bounded actionable report |
| 719 | Toggle persistence race | Rapidly change app settings, immediately background/relaunch | Final accepted values restored; no false committed-success analytics |
| 720 | Clear current book during playback | Clear continue-reading reference while audio is active | Defined reference/playback behaviour; no stale bubble or wrong resume target |
| 721 | Share-log cancellation | Open share sheet and Back without choosing a target | Returns to Settings; “share opened” not falsely treated as completed delivery |
| 722 | Shared-log readability | Share to a test receiver; open exported file | Receiver has valid temporary access; file contains expected sanitized diagnostics |
| 723 | Log clearing while logging | Generate a handled failure, clear logs, generate another | Clear result accurate; logger remains functional; old data not unexpectedly re-exported |
| 724 | Crash-only local logging scope | Toggle crash-only and file-logging options; trigger event/handled failure | Local file behaviour matches preferences; Firebase reporting scope separately verified |
| 725 | Share/clear file failure | Inject inaccessible log/export/delete fixture and retry | Honest error rather than success toast; settings stays usable |
| 726 | Remove-server cancellation | Open remove confirmation; Back/cancel | Source account/books unchanged; no removal outcome emitted |
| 727 | Duplicate server/account addition | Add same source/account twice through supported flow | Defined deduplication/identity; no duplicate library/account corruption |
| 728 | Remove one source among two | Remove test source A while source B and local books exist | Only intended source affected; unrelated books/profiles remain intact |
| 729 | Server operation failure reporting | Fail local logout/remove persistence or source load, then recover | Card reflects actual state; operation/source-type diagnostic and accurate outcome events |

## 33. Cloud account and file backup extensions (one phone only)

| # | Test case | Steps | Expected |
|---|---|---|---|
| 730 | Cloud form Back with IME | Edit email/password, press Back to hide keyboard then leave screen | Normal navigation; no partially created account or leaked form telemetry |
| 731 | Cloud duplicate submit | Rapidly tap account submit/Google sign-in on delayed network | Single accepted flow; one terminal outcome, no multiple browser launches |
| 732 | Cloud mode-switch input state | Edit sign-in form, switch Create account and back | Fields/validation retained or reset consistently; correct action submitted |
| 733 | Cloud terms links | Open terms/privacy links and return, then cancel account creation | Correct links and preserved form; no implicit account creation/consent |
| 734 | Backup quota reached | Use controlled full-quota account and attempt file backup | Clear quota outcome; no endless progress or false backed-up state |
| 735 | Backup network interruption | Begin large upload, lose network, cancel/retry after reconnect | One recoverable transfer state; eventual valid file without accidental duplicate |
| 736 | Cancel file restore | Start supported restore/download and cancel where offered | No usable-ready flag on partial file; safe retry on this phone |
| 737 | Restored file validation | Back up fixture, restore via supported flow on same phone and open offline | EPUB/audio content usable; no remote-position or cross-device assertion |
| 738 | Delete cloud-account cancellation | Open delete confirmation, cancel via button and Back | Account and local data unchanged; no false account-deleted event |
| 739 | Cloud operation diagnostics | Induce auth/storage/backup/restore failure separately and recover | Correct operation/stage/reason and useful non-fatal for unexpected failure; no credentials or sync-verification claim |

## 35. Login overlay dismissal and persistence-failure extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 751 | Dismiss server-type dropdown | Open the server-type menu; dismiss once by tapping outside and once with system Back | Menu closes without changing the selected type or submitting the form; no false selection event |
| 752 | Dismiss URL help tooltip | Open the URL help tooltip; dismiss with its button, outside tap and system Back where supported | Tooltip closes; URL/form remain unchanged; dismissal is not reported as login success/failure |
| 753 | Server-registration persistence failure | Return valid auth credentials from a controlled server, then fail local server registration or credential persistence | Login stays usable, spinner terminates, no authenticated route before persistence succeeds, one actionable diagnostic and a recoverable retry |

## 34. Shared Samsung, accessibility and observability acceptance

Each row below is a parameterized case: create a result row for **each reachable screen/overlay** or relevant operation. For example, `740/BookDetail/toolbar-back` and `740/BookDetail/system-back`. The parent ID cannot pass until all applicable variants pass. This ensures that “test Back everywhere” produces evidence rather than a single vague checkbox.

| # | Test case | Steps | Expected |
|---|---|---|---|
| 740 | Every exit and dismissal | On each screen/overlay, try toolbar Back, system Back, supported swipe, outside tap, Cancel/Close and keyboard-first Back | Correct dismissal order and return target; no accidental commit, lost state or blank screen |
| 741 | Every lifecycle restoration | For each screen/overlay, rotate, Home/resume, lock/unlock, swipe Recents, kill background process and force-stop/relaunch in separate runs | Defined state restored safely; separate service semantics respected; no ghost dialogs/navigation |
| 742 | Every loading/empty/error state | For each data surface induce loading, true empty, filtered empty, cached offline and controlled failure | States distinguish no data from failed loading; escape/retry works; no unbounded spinner |
| 743 | Every action under repeated taps | Repeat submit, open, confirm, retry and destructive buttons while operation is pending | One accepted operation or explicitly queued sequence; no duplicate writes, routes or success events |
| 744 | Samsung navigation and keyboard | Run key flows with gesture and three-button navigation; use Samsung keyboard; largest font/display settings | Controls above system bars/IME, scrolling reaches all actions, readable layouts |
| 745 | TalkBack action coverage | Traverse each screen/dialog with TalkBack; activate, adjust sliders and dismiss | Meaningful labels/state, logical focus, reachable controls; focus returns after overlay dismissal |
| 746 | Samsung power/background limits | Repeat audio/transfer/timer cases with battery saver and documented app sleep restrictions; restore settings | Behaviour and recovery accurately explained; no permanent stuck state or false completed work |
| 747 | Screen journey reconstruction | Complete a labelled journey through each entry point, tab, sheet and Back path; inspect events | Source/destination/exposure order reconstructs actual journey without recomposition duplicates |
| 748 | User-impact diagnostic completeness | For each screen induce its mapped significant failure and recovery | Breadcrumbs identify action/stage; unexpected failure appears once with context; expected cancel/offline is not exception spam |
| 749 | Feature adoption/completion integrity | For each meaningful feature run success, failure, cancellation and retry where applicable | Events support users/sessions, attempts, completions and recovery rates; no false success or raw-content payloads |
| 750 | Final Samsung regression and evidence audit | Run listed smoke IDs on final build; reconcile every reachable action, result and instrumentation record | No required NOT RUN/FAIL/BLOCKED hidden; final counts/exclusions/defects and Firebase evidence support completion claim |

## 36. Home destination exposure extension

| # | Test case | Steps | Expected |
|---|---|---|---|
| 754 | Home route exposure and source | Enter Home from each reachable source (guest selection, successful credentials login, cold-start authenticated/guest route); inspect the local event/breadcrumb, trigger recomposition and switch tabs | One Home exposure per actual visible root entry with the correct bounded source/entry point; no event merely from a tap/persisted preference and no duplicate from recomposition/tab changes |

## 37. Welcome guest-mode persistence failure extension

| # | Test case | Steps | Expected |
|---|---|---|---|
| 755 | Guest-mode preference write failure and retry | On Welcome, make the `SkippedLogin` preference write fail; tap Browse without account; restore writes and retry | Stay on a usable Welcome screen with actionable retry guidance; emit one failed action outcome and bounded diagnostic, one actionable non-fatal for the unexpected failure; do not route Home or emit Home exposure until persistence succeeds; retry succeeds once and routes Home once |

## 38. Welcome root Back reliability and telemetry extension

| # | Test case | Steps | Expected |
|---|---|---|---|
| 756 | Welcome root system-Back usage and exit outcome | From fresh root Welcome, press system Back; verify app exits cleanly, relaunch, and inspect local event/breadcrumb sequence | The Welcome route is not removed from its only entry; activity exits cleanly and relaunch returns to usable Welcome. Emit one bounded `navigation_back` (`screen=welcome`, `source_screen=welcome`, `destination_screen=app_exit`, `entry_point=system_back`, `outcome=exited`) and start/completed diagnostic breadcrumbs; no Home exposure, blank screen, duplicate event, or Crashlytics exception for ordinary Back |

## 39. Login required-field IME validation extension

| # | Test case | Steps | Expected |
|---|---|---|---|
| 757 | Empty Login validation via IME | On the empty Login form, focus Password and press the keyboard Done/Sign In action | Same field-level required errors and one bounded validation outcome/breadcrumb as button submit; no auth attempt/request/loading, duplicate event, or crash |

## 40. Logged-out last-book launch recovery extension

| # | Test case | Steps | Expected |
|---|---|---|---|
| 758 | Last-book launch when its server is logged out | Enable Open Last Book on Launch and set a current book; log out that book's server, then relaunch Parrot. Exercise Retry and Back; reauthenticate through the reachable Server Management Login action and reopen the saved book | Do not claim the book opened until usable content is visible. Show an actionable reauthentication path or a safe Home/Books fallback; preserve the saved target. Emit one `last_book_launch_attempted` and one failed/skipped terminal outcome with bounded `server_not_authenticated` context. No Crashlytics issue for expected logged-out state; Retry/Back/re-login must not lose the target or create a startup loop. |

## 41. Profile action-level dismissal and repeat extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 759 | Visible profile edit affordance | In App Settings, activate the visible Edit affordance on a profile; compare with long-press | Both entry methods open the same profile action menu; Analytics distinguishes `edit_button` from `long_press`; no profile mutation occurs merely from opening the menu |
| 760 | Add Profile cancellation paths | Open Add Profile; independently dismiss with Cancel, outside tap and system Back | Each presentation closes without creating/activating a profile; one dialog-open and one cancelled outcome per presentation, with `cancel_button` or bounded `dismiss_request`; no Crashlytics report |
| 761 | Rename Profile cancellation paths | Open Rename; independently dismiss with Cancel, outside tap and system Back | Existing profile label and active state remain unchanged; one dialog-open and one cancelled outcome per presentation; no mutation attempt or Crashlytics report |
| 762 | Delete Profile cancellation paths | With a disposable non-last profile selected, open Delete confirmation; cancel by button, outside tap and system Back | Profile and its local data remain; one dialog-open and one cancelled outcome per presentation; no delete attempt or Crashlytics report. BLOCKED without a disposable non-last profile |
| 763 | Profile menu dismissal paths | Open a profile menu by long-press and Edit; dismiss each by outside tap and system Back | Menu closes without selecting Rename/Delete; one bounded menu-dismissal outcome per dismissal (`dismiss_request` for outside/System Back); no profile mutation |
| 764 | Profile confirmation taps do not reach exposed content | On a disposable profile, rapidly repeat Add/Rename/Delete confirmation while the operation can complete and dismiss its dialog; inspect unrelated controls beneath the dialog | At most one accepted mutation and terminal outcome; subsequent taps in the same rapid burst are consumed/ignored and cannot toggle a setting, open another route, or activate an underlying control. Restore the original profile and preferences |

## 42. Profile-name collision validation

| # | Test case | Steps | Expected |
|---|---|---|---|
| 765 | Reject duplicate profile names on Add and Rename | With disposable profiles, attempt Add and Rename using an existing profile name, including case/leading-or-trailing-space variants; verify the existing active profile and other profiles remain unchanged | Keep the dialog usable with a clear duplicate-name validation message; perform no registry mutation or profile-operation attempt; emit one bounded `profile_name_validation_failed` event and diagnostic breadcrumb with `operation` and `reason_code=duplicate_name`; do not include names/IDs or report ordinary validation as Crashlytics failure |

## 43. Profile carousel reachability

| # | Test case | Steps | Expected |
|---|---|---|---|
| 766 | Horizontally scroll the profile row | With enough disposable profiles that the Add Profile tile is outside the viewport, horizontally scroll the profile row to its end; inspect and activate the final tile/action, then return the row to its start | The row scrolls independently, every profile and Add Profile remain reachable, and swiping does not trigger a profile selection or unrelated vertical-screen action |

## 44. Reader settings exposure and recovery extensions

| # | Test case | Steps | Expected |
|---|---|---|---|
| 767 | Reader Settings dismissal from App Settings | Open Reader Settings from the App Settings row; separately close via the visible close affordance and system Back (also test outside/swipe dismissal if the sheet supports it) | Each dismissal returns to App Settings without changing Reader settings; screen exposure is emitted once per actual visible entry with `source_screen=app_settings` and `entry_point=reader_settings_row`; no duplicate exposure from recomposition |
| 768 | Reader setting save failure and recovery | With a disposable profile, inject one local settings-database write failure; change a setting, inspect feedback/outcomes, restore writes and use Retry | Failed setting is not shown as committed; one failed outcome and bounded diagnostic context identify the operation; exactly one non-fatal is reported for an unexpected persistence failure; Retry is separately marked and persists the value; cancellation does not report an exception. BLOCKED without safe DB fault injection |
| 769 | Reader Settings initial load readiness | With non-default settings saved, open the Reader Settings surface using a delayed initial database Flow; attempt an immediate change before the saved model emits | Controls do not accept writes until the profile's saved state is hydrated; an early user action cannot save defaults over other preferences |

## 45. Statistics route exposure attribution

| # | Test case | Steps | Expected |
|---|---|---|---|
| 770 | Statistics exposure attribution by entry route | Enter Statistics from the App Settings “Reading statistics” row, then from the Books/Series bottom tab; also exercise route restore and Back return when available | Emit exactly one `statistics_viewed` per actual visible entry with bounded `source_screen` and `entry_point` (`app_settings`/`statistics_row`, tab source/`bottom_navigation`, or `route_restore`/`navigation_back`); emit a matching `statistics_route` visible breadcrumb; do not emit duplicates on recomposition or include profile/book identity. Verify functional navigation and Analytics/diagnostics separately. |
| 771 | Statistics detail query failure, retry and dismissal | For period-books, Books Read and Total Sessions detail sheets, inject a query failure, inspect and activate Retry; separately dismiss a slow query while loading | Keep the requested sheet visible on failure with clear retry feedback; retry preserves the requested detail and records separate attempt/success outcomes; failure has one correlated failure outcome/breadcrumb and one contextual Crashlytics report; dismissal records `cancelled` without Crashlytics or duplicate terminal events. No book/session title or identifier in telemetry. Samsung failure injection may be BLOCKED without a safe fixture; host injection is required. |
| 772 | Startup profile-recovery logs contain no profile identity | Launch with existing profiles and a missing/invalid active-profile selection; inspect only sanitized `DefaultUserInitializer` log records | Recovery selects a valid existing profile without emitting profile names, IDs/UUIDs, credentials, or other user-authored values; logs remain bounded and operation-only. No raw log or real profile identifier is retained. |

## 46. Floating Continue Reading bubble reliability and telemetry

| # | Test case | Steps | Expected |
|---|---|---|---|
| 773 | Bubble-position save failure and retry | With a current-reading fixture, inject one `BubblePosition` preference-write failure; drag/release the bubble, restore writes, then activate the visible Retry or repeat the instructed drag retry | Failed save is not reported as success; prior persisted position remains authoritative; one correlated failed outcome and one contextual non-fatal are recorded for the unexpected write failure; usable retry guidance appears; retry emits a separate attempt and one success, with no duplicate report. Ordinary gesture cancellation is not a failure. Samsung failure injection requires a safe preference fault fixture; host fault injection is required. |
| 774 | Continue-reading open source and terminal outcomes | Open the reader from the floating bubble and shelf in separate runs; exercise usable content, a controlled unavailable/stale target, playback-conflict cancel/confirm, and Retry where available | One source-attributed attempt per accepted action; success only after usable Reader content; failure/cancel/retry have separate terminal outcomes and matching bounded breadcrumbs. Events include media type and bounded entry point only—never title, book/profile ID, URL or content. Unexpected user-impacting failure is reported once; auth/offline rejection and user cancellation are not Crashlytics issues. |
| 775 | Bubble tap/drag gesture, bounds and restoration variants | Tap without dragging; drag across the horizontal midpoint and near top/bottom bounds; cancel a drag if supported; rotate and recreate the app in separate runs; verify hidden/empty conditions | A tap opens the current target without moving it; drag clamps within reachable content, snaps to the nearer side, and saves normalized vertical position; cancellation does not save; rotation/process recreation keeps the bubble visible and reachable. Empty/current-book-cleared and disabled cases follow 439/425; suppression/overlap/accessibility variants remain mapped to 543–545 and 491/745. |
| 776 | Continue Reading state is rebound on profile switch | With the active profile holding a current-reading target and a disposable profile with no current book, switch to the disposable profile; inspect Settings and Books/Series; then switch back | A profile with no current target must show neither the shelf nor floating bubble. The prior profile's target must not leak into the new profile; switching back restores only the original profile's target. Do not clear or alter the retained original profile. Record profile-switch and resulting current-book state separately; never include profile/book identity in telemetry. |

## 47. Server Management loading failure and recovery

| # | Test case | Steps | Expected |
|---|---|---|---|
| 777 | Server list load failure, empty completion and retry | With a safe registry/auth-state fault fixture, separately fail before the first combined value, complete without a value, fail after a usable value, and retry after recovery; cancel an in-flight load by leaving the screen | Initial failures end loading and differ from a real empty list; Retry performs a separate attempt and restores the cards; post-load observation failure preserves the last usable cards and offers retry. Each unexpected failure has one bounded outcome, correlated breadcrumbs and one non-fatal at the UI boundary; ordinary cancellation is rethrown/recorded as cancelled without a non-fatal. Host fault injection required; device failures are BLOCKED without a safe fixture. |
