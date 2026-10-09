# Book catalogues (OPDS): what has actually been tried

Written 2026-10-09, branch `opds/phase4-screens`. This table says what was run, on what, and
how. "Not tried" means exactly that: nobody has run Parrot against it, on any device. Tests
with saved or made-up pages are listed separately and do not count as "tried".

**Read this first.** The device runs below were done before the Phase 5 hardening run. That
run changed five things a device would show, and none of them has been looked at on a device
since:

1. OPDS 1 thumbnails and legacy cover relations are now read, so rows that had no cover
   before can have one.
2. OPDS 1 cover addresses are now resolved against the page, so covers linked with a relative
   address load at all.
3. A list ends when its "next" link loops back, and stops loading by itself after three pages
   in a row without books.
4. A catalogue on the internet no longer gets pictures, search descriptions or files from a
   device on the local network.
5. Signing in from a page, a book or Downloads updates the catalogue's status at once.

## Catalogues

| Catalogue | Tried? | On what | How | What was seen |
|---|---|---|---|---|
| Project Gutenberg (built-in preset, `https://www.gutenberg.org/ebooks.opds/`) | Yes, live | Android phone (Samsung SM-S921B) and Android emulator (`emulator-5554`) | By hand, 2026-10-09, with the built app | Added without an account. Search for "alice wonderland" returned books. A book page opened, Download fetched the EPUB, Read now opened it. On the emulator a single-book address (`…/ebooks/120.opds`) was added as its own catalogue and its book downloaded and opened. |
| Local test catalogue (`tools/catalogue-test-server.py`, plain http, no account) | Yes | Android phone (Samsung SM-S921B), through `adb reverse` | By hand, 2026-10-09 | The http warning showed and "Add anyway" added it. A folder and a book were listed. A row's button downloaded a book; Downloads listed finished books with "Open"; Open showed the book. The catalogue was turned off, on, then removed; the library kept its books. |
| Standard Ebooks (built-in preset, needs a patron account) | **Not tried** | — | No account was available | Nothing. The preset is in the app; whether it works is unknown. |
| Calibre content server | **Not tried** | — | — | Nothing |
| Calibre-Web | **Not tried** | — | — | Nothing |
| Kavita | **Not tried** | — | — | Nothing |
| Komga | **Not tried** | — | — | Nothing |
| Any catalogue with a password (Basic sign-in) | **Not tried on a device** | — | — | Covered by one automated test only, with a pretend network: `CatalogueSignedInEndToEndTest` |
| Any OPDS 2 (JSON) catalogue | **Not tried on a device** | — | — | Parser tests with made-up pages only |
| Any catalogue with an untrusted certificate | **Not tried** | — | — | Tests with simulated errors only |

## Devices

| Device | Tried? | Notes |
|---|---|---|
| Android phone | Yes: one, Samsung SM-S921B | Gutenberg and the local test catalogue, as above. Large text (200%) checked on the catalogue screens. |
| Android emulator | Yes: `emulator-5554` (Medium Phone, API 37) | Gutenberg single-book address; design fixtures in Day and E-ink |
| Android e-ink device | **Not tried** | The E-ink theme was checked on the emulator and phone only |
| Android tablet | **Not tried** | |
| Any iPhone or iPad | **Not tried** | Shared code is tested in the iOS simulator's test runner. The app itself has never been run with a catalogue on an iPhone, an iPad or a simulator. |

## Tested without a device

These run on every build. They prove that saved or made-up input is handled as intended, not
that a real server behaves like the input.

| What | Input | Tests |
|---|---|---|
| Gutenberg's pages | Saved copies of real list pages and of its search description | `CapturedLinkFeedsTest`, `CapturedLinkFeedsParityTest`, `CatalogueSearchTemplateTest` |
| Gutenberg-shaped pages | Made-up pages in the shape of its navigation and book pages | `Opds1ParserTest` |
| A Calibre-style page | One made-up page in Calibre's shape (`calibre-newest.xml`) | `Opds1ParserTest › calibre_feed_entries_are_publications_and_never_a_grouping_point` |
| OPDS 2 | Made-up pages (`catalog.json`, `landscape.json`) | `Opds2ParserTest` |
| Basic sign-in from first tap to library | A pretend catalogue that answers 401 | `CatalogueSignedInEndToEndTest` |

## Known limits that affect compatibility

| Limit | Effect |
|---|---|
| OPDS 1 facets are not read | An OPDS 1 catalogue's sort and filter links are not offered. OPDS 2 facets are. |
| OPDS 1 elements are matched by name, not by namespace | Feeds that omit or mistype the Atom namespace still read. A foreign element with an Atom name would be read as Atom. |
| Only Basic sign-in | A catalogue that asks for Digest, OAuth or an OPDS authentication document cannot be signed in to. If its first page is public it can still be added and browsed. |
| No password over http | A catalogue on plain http can be added without an account only. |
| iPhone needs https | Adding an http catalogue is blocked on iPhone. |
| EPUB only | Other formats are listed as unavailable. |
| No borrowing, buying or samples | Such entries show why they cannot be downloaded and, where the catalogue gives one, a link to its page. |
| Search on Gutenberg uses a fixed https address | Gutenberg advertises http search addresses only, which Parrot refuses for an https catalogue; the preset supplies the https one. |
| A host name that resolves to a local address is not seen as local | Only numeric addresses and `localhost`, `*.local`, `*.lan`, `*.localhost` are. |
