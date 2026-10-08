# Book catalogues (OPDS) — build prompt (passes 1–3)

Designs: `design/ember/catalogues/screens/opds-<view>-<theme>.png`, 84 boards.
Day for every view; Night and E-ink for the main ones. This file replaces the
pass 1 prompt. Your design brief (§11) stays the source of truth for
behaviour; this adds the design decisions and exact copy. Plan first and wait
for my OK. Ember tokens and components only.

Device wording: wherever copy says "this phone", use the shared device-name
helper ("this phone" / "this tablet" / "this iPhone" / "this iPad"). Where
the helper isn't available, the fallback is "this device".

---------------------------------------------------------------------------
## 1. Decisions (pass 1, unchanged unless marked)

- **Name:** "Book catalogue", secondary text "OPDS" ("Book catalogue · OPDS").
  Never "server" for a catalogue.
- **Entry points:** library add action → "Get books"; and each catalogue in
  Libraries has Browse. No new bottom tab.
- **Paging:** Night/Day load the next page near the end with a "Loading
  more…" row. E-ink shows a "Load more" button and never loads on its own. A
  failed page keeps loaded books and shows an inline error row.
- **Descriptions:** limited rich text (paragraphs, line breaks, bold, italic,
  lists). Links become plain text. Drop images, tables, scripts, styles.
- **Downloads** keep running after leaving a screen. **At most two at once**;
  the rest wait in order.
- **Cancelled downloads disappear** from Downloads; the book's row and page
  go back to the download button. No undo.
- **Finished downloads** stay on Downloads until the user leaves the screen,
  or for 24 hours.

## 2. Pass 2 answers to your questions (A)

A1 **Choose a file uses catalogue labels.** Show the catalogue's label as the
   title, wrapping to as many lines as it needs (no clamp, up to ~60 chars).
   No label → "EPUB file 1", "EPUB file 2"… numbered in catalogue order. We
   add only: the size ("0.6 MB", or "Size unknown"), the "Best" pill on the
   default, and "Can't be opened in Parrot" for formats we can't open (radio
   dimmed to 40%, text full contrast, not selectable). Intro: "Names come
   from the catalogue. The first one is the best match for Parrot."
   Button: "Download · <size>" or "Download" when size unknown.
   Board: opds-editions-*.

A2 **"Getting ready…" row state** after tapping a row's download button while
   details load. Shows a cancel × (cancels the fetch). If the book has
   several editions or nothing we can open, open the book page instead — the
   row returns to its normal state silently, no error. Board: opds-listStates-*.

A3 **Plainest catalogue:** no search field, no shelf, no "Browse" heading,
   folders only, no subtitles. Folder names wrap to 2 lines then clamp.
   Board: opds-browsePlain-*.

A4 **Rights:** "Check the law where you live before sharing." is ours, a
   separate string, shown on its own line under the catalogue's rights text,
   and **only when the catalogue gives rights text**. No rights text → no
   Rights line and no sentence of ours.

A5 **Address on Get books and Libraries rows:** host only, confirmed
   ("books.home.lan"). For presets, the host too ("gutenberg.org"). The full
   address appears only on the catalogue's settings screen (see C2).

A6 **http on iPhone:** dialog with one button. Board: opds-httpIos-day.

A7 **Offline copy** (now the default on the board, since it doesn't promise
   auto-start): "You're offline. Showing the copy saved <time> ago. Connect to
   the internet to download books." If auto-start ships later, the old line
   "Downloads start when you're back online." may replace the last sentence.

A8 **Presets:** the list works with any number. Boards show Gutenberg added +
   Standard Ebooks ("Patron account needed") + Another catalogue
   (opds-catalogues-day), one preset added (opds-cataloguesFew-day), and none
   added (opds-cataloguesEmpty-day). Keep presets in a data file.

## 3. States (B)

| State | List row (status line, button) | Book page card | Downloads row |
|---|---|---|---|
| Getting ready | "Getting ready…", × | — | — |
| Waiting | "Waiting to download…", × | "Waiting to download…" / "Two other books are downloading. This one starts next." / Cancel | "Waiting to download…" / Cancel |
| Known size | % bar + "42%", × | "Downloading…" · "0.5 of 1.2 MB" + bar / Cancel | "0.5 of 1.2 MB" + bar / Cancel |
| Unknown size | "Downloading · 1.4 MB so far", ×, **no bar** | "Downloading…" · "1.4 MB so far", no bar / Cancel | "Downloading · 2.1 MB so far" / Cancel |
| Adding | "Adding to library…", no button | "Adding to library…" / "Checking the file and preparing it for reading." no Cancel | "Adding to library…" |
| Done | "✓ In your library" | "✓ In your library · downloaded just now" + Read now | "✓ In your library" / Open |

Book page note under the bar: Android "Keeps going if you leave this
screen." · iPhone (bold) "Keep Parrot open until this finishes. iPhone
pauses downloads soon after you leave the app." (opds-detailIos-day).
"Waiting" count: say "Two other books are downloading" only when that's
true; one → "Another book is downloading. This one starts next."

**First load** (opds-firstLoad): "Opening <catalogue>…" + static placeholder
blocks (no shimmer). E-ink: text only.

**Empty folder:** "Nothing here yet" / "This part of the catalogue has no
books right now." / Go back.

**Search:** the search field becomes the top bar with a clear ×; under it
"“<query>” in <catalogue>"; results use normal book rows. No results:
"No books found for “<query>”" / "Check the spelling, or try the author's
name." / Clear search. E-ink search results end with Load more.

**Filter picker** (opds-filter): bottom sheet titled with the facet group
name ("Language"), "<N> options" on the right. Search field
"Search <group, lower-case>" only when there are more than 12 options.
Single choice radio list; the catalogue's count on the right when given.
Tapping an option applies it and closes the sheet; no Apply button. The
first option is the catalogue's "all" option if it has one.

**Several editions** (opds-editionsGrouped): same Choose a file sheet, files
grouped under the edition's label (catalogue title/date, e.g. "Illustrated
edition, 1911"; fallback "Edition 1", "Edition 2"). Intro: "This book has
<N> editions in the catalogue." One "Best" across all groups.

**No catalogues yet:** lead "Add a book catalogue to browse it and download
books straight into your library."; section "Start with one of these".
With catalogues: lead "Browse book catalogues and download books straight
into your library."; sections "Your catalogues" / "Add a catalogue".

**Preset detail** (opds-preset): tile, name, access pill, description,
Address (host), terms link "<Name>'s terms of use" (opens browser), the note
"Parrot isn't part of <Name>. What you can do with a book depends on its
rights and on the law where you live.", primary "Add catalogue". For a
preset that needs an account the pill says "Patron account needed" (or the
preset's wording) and Add catalogue opens the sign-in sheet first.

**Download failed reasons** ("Couldn't finish · <reason>" style, error colour):
- connection: "Couldn't finish · the connection was lost" → Retry (filled)
- too large: "Too large to add · <size> (the limit is <limit>)" → Dismiss
- storage: "Not enough space on this phone · needs <size>" → Retry
- invalid: "This file isn't a book Parrot can open" → Dismiss
- protected: "This file is protected (DRM) and can't be opened" → Dismiss
- refused: "<Catalogue> didn't allow this download" → Retry
- interrupted: "Stopped when Parrot closed" → Start again (filled; see §9 B1)
- sign-in: "Sign-in needed for <catalogue>" → Sign in
Only the first failure in the list gets a filled button; others outlined.

## 4. Libraries and catalogue settings (C)

**Libraries rows** (opds-servers, opds-serversMore). Same card as servers;
type label "Book catalogue". Status line:
- public: hollow dot, "Ready · no account needed" / "Checked <time> ago" + Browse
- signed in: green dot, "Signed in as <name>" / "Checked <time> ago" + Browse
- sign-in needed: error box "Sign-in needed" / "This catalogue now asks for
  your account." (presets may override: "…your patron account.") + filled
  "Add account details"
- unsupported: error box "Sign-in method not supported" / "This catalogue
  changed how you sign in. Parrot can't open it until that's supported." +
  outlined Details (see §9 A1)
- turned off: grey dot "Turned off" / "Browsing and downloads are paused" +
  outlined "Turn on"
- last error: error box "Couldn't reach it · <time> ago" / "The catalogue
  didn't answer. It may be offline." + filled "Try again"
A public catalogue never shows "Signed out".

**Catalogue settings** (opds-serverPublic / serverAccount / serverOff):
header status + "Book catalogue · OPDS · checked <time> ago"; primary
Browse (hidden when turned off). Groups:
- Address: full URL, wraps, breaking anywhere. If the URL has a query value
  or path part that looks like a key (apikey, token, key, auth, password,
  or a 20+ char random string), mask it as "••••••••" with a "Show" button
  (becomes "Hide"). Then "Edit address".
- Account: no account → "No account" / "This catalogue doesn't need one." +
  "Add account details". With one → "Signed in as <name>" / "Saved on this
  phone only." + "Edit account details" + "Remove account details" (asks
  first: "Remove account details for <name>?" / "You'll browse it without an
  account. Downloaded books stay." / Remove / Cancel).
- Catalogue: switch "Use this catalogue" (sub: on "Turn off to pause browsing
  and downloads." / off "Off. Browsing and downloads are paused.") and
  "Remove catalogue…".
- Turned off: note at the top "Turned off. Browsing and downloads from this
  catalogue are paused. Books you downloaded stay in your library." + Turn on.

**Edit address dialog** (opds-editAddress): multi-line field showing the
whole address unmasked (the user is editing it), helper "The full address,
including any key it needs.", Save / Cancel. Same address validation and
errors as Add.

**Remove** (opds-removeCat): "Remove <name>?" / "It will no longer appear in
Get books, and its account details are deleted from this phone. The <N>
books you downloaded from it stay in your library." / "Remove catalogue"
(destructive) / Cancel. N = 0 → drop the last sentence's count: "Books you
downloaded from it stay in your library."

## 5. Dialogs and add errors (exact copy)

Field errors on Add (under the address, error colour):
- web page: "This is a web page, not a catalogue. On that site, look for a
  link called "OPDS" and paste that address."
- unreachable: "Can't reach this address. Check it for typos, and that the
  catalogue is running and on the same network."
- invalid: "This address answered, but not with a book catalogue Parrot can
  read. Check that it's the OPDS address."
- sign-in needed: switch turns on, fields appear, and: "This catalogue needs
  an account. Enter your username and password to add it."

Dialogs (title / body / detail line / primary / secondary):
- http (Android): "This catalogue isn't secure" / "It uses an http://
  address, so others on your network could see what you browse and
  download. You can't use a password with it." / the address / Go back /
  Add anyway
- http (iPhone/iPad): "This catalogue can't be added" / "On <this device>,
  Parrot can only use secure addresses that start with https://. Ask
  whoever runs the catalogue for an https:// address." / the address / OK
  (see §9 B2)
- password over http: "Passwords need a secure address" / "This catalogue
  uses http://, so your password would travel without protection. Use an
  https:// address, or add the catalogue without an account." / — /
  Change address / Add without an account
- certificate: "Can't check this catalogue" / "Its security certificate
  isn't trusted, so Parrot can't be sure it's really <host>. If it's your
  own server, give it a valid certificate and try again." / — / Go back.
  No way to continue.
- unsupported sign-in: "Sign-in method not supported" / "This catalogue uses
  a sign-in method Parrot doesn't support yet. You can still add it and
  browse anything it shows without an account." / — / Add without an
  account / Cancel
- local network: "Open a device on your network?" / "This link leads away
  from <catalogue> to a device on your home network. Only open it if you
  expected that." / the host or IP / Don't open (primary) / Open
- sign out of everything: add this as the dialog's detail line: "Book
  catalogues: account details are removed too. Catalogues that don't need
  an account stay, so you can keep browsing."
- rate limited (screen): "Too many requests" / "<Catalogue> asked apps to
  slow down for a moment. Try again in about a minute." / Try again

**Get updated copy** (opds-detailUpdate): note under Read now:
"A newer file is available" / "<Catalogue> changed this book on <date>.
Your copy stays as it is, with its progress and highlights. The updated
file is added next to it." / outlined "Get updated copy · <size>".

**Sign-in sheet:** "Sign in to <catalogue>" / "This part of the catalogue
needs an account. Your details are saved on this phone only." / Username /
Password, helper "Leave empty if this catalogue has no password." / Sign in.

## 6. Accessibility labels (new buttons)

- Row download: "Download <title>"; row cancel (any state): "Cancel download
  of <title>"; Getting ready cancel: same.
- Row status is part of the row label: "<title>, <author>, <status>".
- Progress announced at 25% steps; unknown size announces "Downloading
  <title>" once.
- Book page Cancel: "Cancel download"; Read now: "Read <title>"; Get updated
  copy: "Get updated copy of <title>, <size>".
- File list: radio group "Choose a file"; each option "<label>, <size>"
  (+ ", best match" / ", can't be opened in Parrot, unavailable").
- Edition headers are headings inside the sheet.
- Filter chip: "<group>: <current value>. Change"; sheet options are a radio
  group; search field "Search <group>".
- Search clear: "Clear search".
- Downloads buttons: "Retry <title>", "Start downloading <title> again", "Dismiss <title>",
  "Sign in to <catalogue>", "Open <title>", "Cancel download of <title>".
- Libraries: card "<name>, book catalogue, <status>"; Browse "Browse <name>";
  status buttons "Add account details for <name>", "Turn on <name>", "Try
  <name> again".
- Address Show/Hide: "Show full address" / "Hide key in address".
- Switch: "Use this catalogue, on/off".
- Preset rows: "<name>, <note>"; terms link "<name>'s terms of use, opens in
  browser".

## 7. E-ink

No spinners, no shimmer, no scrim; sheets get a 2dp top outline; dialogs a
2dp outline; Load more instead of auto-loading (lists and search results);
filled black radios; progress as an outlined 10dp bar; first load is text
only.

## 8. Deliverables

ViewModel tests: paging (next, fail, retry), the two-at-a-time queue and
waiting order, cancel at each state, default file choice and fallback
labels, key masking, every failure reason → copy, and catalogue statuses →
Libraries row. Fixtures in `tools/ember-fixtures` for every board, captured
to `design/screens/catalogue-<view>-<theme>.png` in Day and E-ink.

---------------------------------------------------------------------------
## 9. Pass 3

### A1 Sign-in method not supported, nothing to browse
Board: opds-unsupportedBlocked-day. Use when the catalogue's first page
itself asks for an unsupported sign-in (nothing is visible without it).
Dialog over Add: "Can't add this catalogue yet" / "It asks you to sign in
before showing anything, and uses a sign-in method Parrot doesn't support
yet. Parrot can sign in with a username and password." / host / single
button "Go back" (returns to the address field, keeps what was typed).
When some pages are visible without signing in, keep opds-unsupported
("Add without an account").

**Was working, now unsupported** (opds-serversMore-day, first card): error
box "Sign-in method not supported" / "This catalogue changed how you sign
in. Parrot can't open it until that's supported." / outlined "Details".
**Details opens the catalogue's settings** (opds-serverUnsupported-day):
header status in error colour "Sign-in method not supported"; no Browse
button; error note at the top "Can't be browsed right now" / "This
catalogue now uses a sign-in method Parrot doesn't support yet. Parrot can
sign in with a username and password. Books you downloaded stay in your
library."; Address group as usual; Account group shows only "Remove
account details"; Catalogue group as usual (switch + Remove catalogue…).
Opening the catalogue from Get books lands on the same settings screen.
A11y: Details → "Details for <name>".

### A2 Password over http, nothing to browse
Board: opds-pwHttpBlocked-day. Use when the catalogue shows nothing without
an account. "Passwords need a secure address" / "This catalogue needs an
account for everything, and it uses http://, so your password would travel
without protection. It can't be used until it has an https:// address." /
the address / single "Change address" (focuses the address field with the
text selected). Otherwise keep opds-pwHttp with "Add without an account".

### A3 Same book listed several times
Boards: opds-editionsList-day, -eink. Used when two or more entries with the
same title and author can't be grouped into editions.
- Top bar: book title, sub "<catalogue>".
- Header line: "<Catalogue> lists this book <N> times. They may be
  different editions or files."
- Each row: normal book row plus a bold "telling" line built from what the
  entry has, joined with " · ": edition label ("Illustrated edition"), year
  ("1911"; "Published 2004" when it's the only detail), file count ("1 file"
  / "2 files"). Nothing at all → "No edition details" + file count. Then
  "✓ In your library" on its own line if acquired.
- Keep catalogue order; never hide an entry.
- In ordinary lists and search results, same-title rows also get the
  telling line (so they don't look like duplicates); only rows with a
  sibling get it.
A11y: row label "<title>, <telling line>, <status>"; download button
"Download <title>, <telling line>".

### B1 Resume → "Start again"
The download restarts from zero, so the button says **Start again**. Status
stays "Stopped when Parrot closed". A11y: "Start downloading <title> again".
If true resume ships later, switch to "Resume" / "Resume downloading
<title>".

### B2 http on iPad
Body: "On <this device>, Parrot can only use secure addresses that start
with https://. Ask whoever runs the catalogue for an https:// address."
using the device helper ("this iPhone" / "this iPad"). Fallback: "On this
device, …". Title and button unchanged.

### B3 If iPhone allows http
Confirmed: iPhone then uses the Android dialog unchanged (opds-http).

### B4 Without "Get updated copy"
Confirmed: no placeholder. The book page shows "✓ In your library ·
downloaded <when>" and Read now only (opds-detailDone).

### B5 Offline, no saved copy
Boards: opds-offlineNone-day, -eink. Message screen under the page's normal
top bar: "You're offline" / "This page hasn't been opened before, so
there's no saved copy to show. Pages you've opened are kept for when you're
offline." / "Try again" (soft button). A11y: "Try loading this page again".
With a saved copy, keep the opds-offline banner.

### B6 Two catalogues on one host
Not acceptable as is. Rows show the host only, **unless** another catalogue
has the same host; then both show host + path, e.g.
"books.home.lan/opds/fiction" and "books.home.lan/opds/comics". Apply the
settings key masking (a segment that looks like a key becomes "••••");
query strings are never shown on rows. Still identical → add the port.
Still identical → host only (the names differ by then).

### B7 "Checked <time> ago"
Keep "Checked" (we check when the user opens it or taps Try again). Show the
line only when the last check was within 7 days; older → hide it for
healthy states (Ready / Signed in). Error states keep their time because it
explains the error ("Couldn't reach it · 3 weeks ago"). The settings header
keeps "checked <time> ago" at any age.

---------------------------------------------------------------------------
## 10. "Can't be downloaded here" card (opds-blocked) — every reason

Card on the book page, in place of the Download button (note style: note
background, title 16 bold, body 14). <Provider> = the catalogue's name for
the seller/lender if the entry gives one, otherwise the catalogue's name.
"Open provider page" is a filled button inside the card **only when the
entry has a web link for it**; with no link the card has no button.

| Reason | Title | Body | Button |
|---|---|---|---|
| Sold | Can't be downloaded here | This book is sold on <Provider>'s site. Parrot can only add books that the catalogue lets you download. | Open provider page |
| Subscription | Can't be downloaded here | This book is part of a subscription on <Provider>'s site. Parrot can only add books that the catalogue lets you download. | Open provider page |
| Borrow | Can't be downloaded here | This book can be borrowed on <Provider>'s site, but not downloaded here. Parrot can only add books that the catalogue lets you download. | Open provider page |
| Sample only | Only a sample is available | The catalogue offers only a sample of this book. | "Download sample · <size>" (filled) when the sample is a file Parrot can open, plus a text link "Open provider page" under it. No openable sample → body only + Open provider page (filled). |
| Format | Can't be opened in Parrot | This book is only available as <format>, which Parrot can't open. | none |
| Protected (DRM) | Can't be opened in Parrot | This book's file is protected (DRM), so Parrot can't open it. | none |

- <format> names: "PDF", "MOBI", "a Kindle file (AZW3)", "an audiobook",
  otherwise the catalogue's own type label; unknown → "This book is only
  available in a format Parrot can't open."
- Format and DRM never show Open provider page: the provider can't make the
  file open in Parrot, and the link would suggest otherwise.
- If an entry has several reasons, show the first in this order: sample
  only (it's actionable), sold, subscription, borrow, DRM, format.
- If at least one file can be downloaded, there is no card: the normal
  Download button shows, and the other files appear in Choose a file as
  "Can't be opened in Parrot".
- E-ink: card with 2dp outline, black filled button.

A11y: "Open <Provider> page, opens in browser"; "Download sample of
<title>, <size>". The card title is a heading; the body is read after it.
