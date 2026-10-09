# Phase 4 accessibility, e-ink, visual fixes and gate — 2026-10-09

Branch `opds/phase4-screens`. Run on `emulator-5554` (fixtures, Day/E-ink) and the Samsung
SM-S921B (large font, real check). Not a sign-off of the 783-case QA programme.

## Search for Project Gutenberg

Gutenberg's search description (saved as `GUTENBERG_SEARCH_DESCRIPTION` in the server-opds
tests) lists only `http://` templates, one on another host (`m.gutenberg.org`); that request is
refused. Changes, all generic:

- `catalogue-presets.json` has an optional `searchTemplate` (https only). It is stored on the
  catalogue (`ServerConfig.searchTemplate`) like `listEntriesAreBooks`. When present it is used
  instead of the advertised description, which is then not even fetched.
- Every catalogue: a search address that would send an https catalogue's queries over http is
  never used; with no override the catalogue shows no search field.
- Gutenberg's https search answers (checked 2026-10-09: 200, Atom). On the Samsung the search
  field appears and "alice wonderland" returns books.

## Profile crash

Reproduced on main (078ab8ef). See `manual-qa-evidence/2026-10-09/opds-profile-crash-on-main.txt`.
Not fixed here.

## Accessibility (CATALOGUE_PROMPT §6 and §11)

Fixed: message-screen and Downloads-empty titles are headings, title only (they marked the whole
block); the sign-in sheet title is a heading; the add button announces "Checking address…" as
a live region on the preset page too; the sign-in sheet now uses the shared sheet.

Checked by tool (`tools/ember-fixtures/check_catalogue_semantics.py`, reads the captured
hierarchies): row label `<title>, <author>, <status>`; separate download/cancel targets of at
least 48dp that are not the row; the file list is one group "Choose a file" with
`<label>, <size>` options (best match / unavailable); Downloads buttons at least 48dp.

Checked only by reading the code (the hierarchy dump does not show them and the project has no
Compose UI test setup): headings on edition headers and filter/sheet titles; radio roles on the
file and filter sheets; live regions for loading, failure and sign-in errors; focus to the
password field after a wrong sign-in and to the address after an add error; focus on the new
catalogue's screen after adding (the screen is navigated to); announcements at 25% steps.
No TalkBack/VoiceOver run is claimed.

Large font (200%, Samsung, `design/screens/large-font/`): add screen, Get books, a list, the
book page, Choose a file, Downloads, catalogue settings. Found and fixed: top bars clipped
title and subtitle (base-ui `EmberTopBar` fixed height → minimum height); the Get books title
was squeezed by the Downloads button (button moves under the title at large font); Downloads
rows squeezed the title by the button (button moves under the text at large font).

## E-ink (§7)

Fixed: the preset page's busy button showed a spinner; sign-in sheet had a scrim and no outline
(now the shared sheet: 2dp outline, no scrim); file-sheet and add-kind radios were accent
rings, now filled black. Already right: no shimmer, "Load more" instead of auto-load, 10dp
outlined progress bars, text-only first load, 2dp dialog outlines. All fixtures re-captured.

## Visual differences

Fixed: "Browse" heading colour; message screens (icon colour, button text colour, spacing);
local-network dialog (filled "Don't open" above text "Open"); Storyteller and Audiobookshelf
subtitles; Day sheet scrim (sign-in sheet). Left as asked: the Gutenberg row letter.

## Real check on the Samsung (profile "disposable", empty library, update-installed build)

(a) Local catalogue `http://localhost:8791/opds/` (via `adb reverse`): the http warning showed,
"Add anyway" added it, browsing showed a folder and a book, the row button downloaded
"Salt and Signal" (tiny, so it finished before it could be watched) and Downloads listed both
finished books with "Open"; Open showed the book. (b) Built-in Project Gutenberg: added,
search "alice wonderland" returned results, "Alice's Adventures Under Ground" opened, the book
page Download button fetched the EPUB, Read now opened it. (c) The local catalogue was turned
off ("Turned off", row "Turn on"), on, then removed: the library kept all 3 books and
"Salt and Signal" still opened.

Things seen: after Downloads lists finished books and you open one, returning to Downloads
shows "No downloads" (finished rows are not kept); not changed.

## Gate

- Anonymous custom catalogue end to end on Android: met (local catalogue, Samsung).
- Basic sign-in: not tested live; no local server in the test script does Basic. Covered by
  unit tests only.
- Gutenberg without a custom parser: met (preset data + generic rules).
- Acquired book works after the catalogue is removed: met (Samsung).
- Unsupported transactions never look like downloads: met by the blocked-card tests and
  fixtures; no live catalogue with sales/borrow links was used.
- iOS: simulator tests only, no device run, composeApp iOS tests cannot link (known).
- Not met: full cross-platform gate (no iOS run, no live Basic).
