# Ember — Bookmarks, highlights & notes (prompt for Claude desktop)

Reference screenshots in this folder: `marks-page-day.png`, `marks-added-day.png`, `marks-select-day.png`, `marks-detail-day.png`, `marks-note-day.png`, `marks-page-eink.png`, `marks-select-eink.png`, `marks-detail-night.png`, `marks-list-night.png`, `marks-list-day.png`, `marks-list-eink.png`, `marks-listEdit-day.png`, `marks-empty-day.png`, `marks-export-day.png`, `marks-library-night.png`, `marks-library-eink.png`.

Book text, titles and counts in the screenshots are placeholders.

---

## Prompt

Rebuild bookmarks into **bookmarks, highlights and notes** following `design/ember/BOOKMARKS_PROMPT.md` and the `marks-*.png` screenshots. This is a product redesign, **not tied to the current implementation**: you may replace the bookmark model, storage and UI. Everything must sync through **Parrot Cloud**. Ember tokens only (`design/ember/DESIGN_SYSTEM.md`).

**Step 1 — investigate and plan first.** Report, then wait for my OK:
- today's bookmark model, storage and sync, and what can be migrated;
- what Readium gives us on Android and iOS for text selection, decorations (highlights) and stable text anchors, and anything that can't be done equally on iOS;
- a proposed data model, Parrot Cloud sync design (tables, conflict rules, deletes, limits) and migration of existing bookmarks;
- how this fits with Storyteller / Audiobookshelf books (do they have their own bookmarks we should read or write?);
- the composable split and commit order. Flag anything in this prompt that is expensive or risky.

**Step 2 — implement.**

### A. One model: a "saved item"
One entity with a type, so every surface treats them the same:
- **Bookmark** — a place: text anchor of the first sentence on the page (not a page number, so it survives font/layout changes), that sentence stored as the snippet. Optional note. When made while listening, also the audio position.
- **Highlight** — a text range: anchor + the quoted text, a colour (amber default, rose, sage, sky), optional note.
- A "note" is not a separate type: it's a bookmark or highlight that has note text.
Every item stores: book identity that is stable across devices and sources, chapter title, total progression, created/updated time, and soft-delete for sync. Migrate existing bookmarks (snippet resolved lazily the next time the book opens; until then show the chapter title).

### B. Sync (Parrot Cloud)
- All items sync for signed-in users, for every book regardless of where the file comes from (local, Parrot Cloud, Storyteller, ABS). Works offline; changes queue and sync later.
- Last-write-wins per item on `updatedAt`; deletes are tombstones; edits on two devices never duplicate an item.
- Signed-out users keep everything locally; on sign-in, local items upload and merge.
- Show sync state in words only where the lists end: "✓ Synced to Parrot Cloud" / "Will sync when you're online" / "Sign in to sync across devices" (link). No per-item sync icons.

### C. In the reader (`marks-page-*.png`, `marks-added-day.png`)
- **Bookmark icon** in the top bar toggles a bookmark for the current page (filled when the page contains a bookmark). Adding shows a bar above the progress strip for ~5 s: "Bookmarked" · **Add note** · **Undo**. Removing shows "Bookmark removed" · **Undo**.
- **Ribbon** at the top-right of the page while a bookmark anchor is on that page; tapping it opens the bookmark's detail sheet.
- **Highlights** are drawn with the decoration API in their colour; a highlight or bookmark with a note shows a small "note" marker at its end. Tapping a highlight opens its detail sheet.
- **Progress strip:** small ticks at bookmark positions (bookmarks only, not highlights).
- **While listening:** the bookmark button in the player / listening panel bookmarks the sentence being spoken and stores the audio position; jumping to it resumes audio there.

### D. Selecting text (`marks-select-*.png`)
- Press and hold selects the sentence/word as today; our toolbar replaces the system menu and never covers the selection (place it below, or above if there's no room).
- Row 1: "Highlight" + four colour dots (44dp targets). One tap highlights in that colour and closes the toolbar.
- Row 2: **Note** (highlights in the last-used colour and opens the note sheet) · **Copy** · **Search** (in-book search for the selection) · **Share**.
- Selecting inside or across an existing highlight extends/merges it rather than stacking two.

### E. Detail and note sheets (`marks-detail-*.png`, `marks-note-day.png`)
- **Detail:** title "Highlight" or "Bookmark" + ✕; meta "Chapter 8, The Crossing · 62% · saved 3 days ago"; the quote in the book font with a colour bar; colour dots (highlights only, selected one ringed); the note box (tap to edit; "Add a note…" when empty); actions **Copy** · **Share** · **Remove…** (`err`; removes with an Undo bar, no dialog).
- **Note editor:** quote on top, multi-line field (focused, keyboard open), one filled **Save note**. Closing with unsaved text asks "Discard this note?".

### F. Per book: Contents → "Saved" (`marks-list-*.png`, `marks-listEdit-day.png`, `marks-empty-day.png`)
- The Contents sheet tab is renamed from "Bookmarks · N" to **"Saved · N"**.
- Filter chips: **All · Bookmarks · Highlights · Notes** (Notes = any item with note text). Remember the last filter per session.
- Items in **book order**, grouped under chapter headers. Each row: colour bar (accent for bookmarks, highlight colour for highlights), the **sentence/quote** in the book font (max 3 lines), the note in a tinted box when present, and one meta line "**Bookmark** · 41% · 2 weeks ago" (listening bookmarks: "**Bookmark** · listening, 4:12:08 · yesterday"). Never show only a chapter name.
- Tap a row → jump there (keep `navigateKeepingAudio` and the "Back to where I was" pill) and close the sheet. Long-press → detail sheet.
- Footer: sync line, **Edit** (shows a 44dp delete button per row; delete = immediate + Undo; "Done" leaves) and **Export**.
- Remove the dashed "This page is bookmarked" box. Empty state: icon, "Nothing saved yet", "Tap the bookmark at the top of a page to save your place. Press and hold a sentence to highlight it or add a note." + filled **Bookmark this page**.

### G. Export (`marks-export-day.png`)
Sheet "Export from this book" with counts, then: **Share as text**, **Copy everything**, **Save as Markdown file**. Output in book order under chapter headings: quote, note, position. Bookmarks export their sentence.

### H. Across all books (`marks-library-*.png`)
- New screen **"Notes & highlights"**, reached from Book details ("Saved · 19" row → that book's list) and from Settings/Library (all books).
- Search field (searches quotes and notes across books), the same four chips, **Latest** (most recent items across books) and **By book** (cover, title, "3 bookmarks · 12 highlights · 4 notes"). Tapping a book opens its Saved list full-screen; tapping an item opens the book at that place.
- Works for books not downloaded on this device: items are listed from sync; opening one offers to download the book first.

### E-ink
No colours: highlights are a 2dp underline, the current selection is a black box with white text; the colour dots are replaced by a single **Highlight** button and colour is kept as the default amber for other devices. Ribbon and bars are black; sheets have a 2dp top border and no scrim; no animation; the "Bookmarked" bar is static and stays until the next page turn.

### Accessibility
Highlights are announced as "highlighted" with their note; ribbon "This page is bookmarked"; colour dots are named (Amber, Rose, Sage, Sky) and never the only signal (selected one has a ring); rows read as "Highlight, chapter 8, 62 percent: <quote>. Note: <note>".

**Step 3 — verify.** Build Android (and iOS if set up). Test: add/remove a bookmark with undo; change font size and confirm the bookmark still lands on the same sentence; highlight across a page break and across paragraphs; overlapping highlights merge; note add/edit/discard; listening bookmark resumes audio; filters; export in all three formats; migration of old bookmarks; two devices editing the same item offline; delete on one device disappears on the other; signed-out → sign-in merge; a book with 500+ items; all three themes. Summarize changed files, the sync schema, and anything not achievable on iOS.
