# Dictionary lookup: investigation report

Scope: the reader's selection toolbar shows a short definition when one word is selected, with a "More" button that opens a full entry sheet. No code was changed.

**Evidence labels.** *[repo]* means verified in this codebase; *[source]* means checked against the linked primary source; *[estimate]* means a planning estimate, not measured. Source pages and retrieval date are listed at the end. Licensing notes are engineering research, not legal advice.

**Missing design files.** None of `marks-define-day.png`, `marks-defineTr-day.png`, `marks-defineNone-day.png`, `marks-dict-day.png`, `marks-define-eink.png` or `marks-dict-eink.png` exist in `design/ember/`. The folder has only the `marks-added/detail/empty/export/library/list/listEdit/note/page/select` sets. I could not look at the reference designs, so UI claims below come from the task description only.

---

## 1. What exists today

**No dictionary feature, library or data source exists.** *[repo]*

- A search of all `.kt`, `.swift`, `.xml`, `.kts`, `.sq` and `.toml` files for dictionary, lemma, Wiktionary, StarDict, `UIReferenceLibraryViewController`, `ACTION_DEFINE` and `PROCESS_TEXT` found nothing relevant. The only hits were unrelated: `NSBundle.objectForInfoDictionaryKey`, `Inflater.needsDictionary`, and Swift's `Dictionary(grouping:)`.
- `androidApp/src/main/AndroidManifest.xml` has a single `<queries>` entry (`TTS_SERVICE`). No Define or `PROCESS_TEXT` handling.
- `gradle/libs.versions.toml` has no ML Kit, ICU4J, Lucene or NLP library. It does have Ktor 3.4.1, SQLDelight and Readium 3.2.0.

**Where it would plug in.** The system selection menu is already suppressed and replaced by our own toolbar.

| Piece | Location |
|---|---|
| `SelectionToolbar` (composable, popup placed above or below the selection) | `feature/reader/ui/.../reader/saved/ReaderSavedUi.kt:170` |
| Current buttons: Highlight (four colour dots), Note, Copy, Search, Share | same file, lines 234–246 |
| `ReaderTextSelection(href, mediaType, text: PageText)` | `.../saved/ReaderSavedState.kt:43` |
| `SavedAction.{NoteSelection, CopySelection, SearchSelection, ShareSelection, DismissSelection}` | `ReaderSavedState.kt:88-92` |
| Rendered from | `ReaderOverlay.kt:493` (`saved.selection?.let { SelectionToolbar(...) }`) |
| Selection flow (`selectionChanges`, `selectionForToolbar()`, `clearSelection()`) | `navigator/BookController.kt:210-221` |
| JS: `P.selection` returns quote, before/after context, rect, viewport, `tooLong` | `navigator/SavedPageScript.kt:343` |
| iOS: `shouldShowMenuForSelection` returns false and pings Kotlin | `iosApp/.../ReadiumEpubReaderBridge.swift:1111` |

Toolbar states that exist today: no selection, selection, or `tooLong` (quotes over `MAX_QUOTE = 1500`). The designs add define, define-with-translation, define-none and the dictionary sheet.

---

## 2. Data source options

### 2a. Offline dictionary files

| Format | Notes |
|---|---|
| **StarDict** (`.ifo/.idx/.dict[.dz]/.syn`) | An established file format with a searchable index and a broad ecosystem of clients. Entries are commonly flat text or HTML; structure depends on the dictionary. A `.syn` index only contains aliases/forms actually supplied by that dictionary, so it is not a general inflection solution. |
| **Wiktionary extracts via wiktextract** (kaikki.org) | Structured JSONL can include senses, part of speech, IPA, forms, examples and etymology. The [Kaikki English page](https://kaikki.org/dictionary/English/index.html) lists 1,390,507 distinct words and a 3.1 GB postprocessed download; the [raw-data page](https://kaikki.org/dictionary/rawdata.html) lists a 2.8 GB gzip / 23.9 GB raw English-Wiktionary extraction. These are different artifacts; both are too large to ship untrimmed. Postprocessed downloads are marked deprecated. The [Slovene page](https://kaikki.org/dictionary/Slovene/index.html) lists 7,375 distinct words and a 27.2 MB deprecated download. It is extracted from the English Wiktionary dump, not a separate Slovene-edition dump, and the count should not be read as a count of useful headwords or English glosses. The project's [copyright page](https://en.wiktionary.org/wiki/Wiktionary:Copyrights) says text is available under CC BY-SA 4.0 and GFDL but warns that the page is outdated; verify current terms and third-party material before distribution. |
| **Sloleks 3.1** (CLARIN.SI) | [Current repository record](https://www.clarin.si/repository/xmlui/handle/11356/2080): 372,341 entries; 262.67 MB ZIP; CC BY-SA 4.0. Contains Slovene lemmas, inflected/derived forms, grammar, frequencies, accents and IPA/SAMPA. **It is a morphological lexicon, not a definitions dictionary.** The source archive is sizeable; a form→lemma table extracted for lookup should be much smaller, but its size is not measured here. |
| **FreeDict Slovenian–English** | The [FreeDict API](https://freedict.org/freedict-database.json) lists `slv-eng` at 5,555 headwords; the StarDict archive is about 120 KB. The bundled TEI header says GPL-3.0-or-later and the edition was created in 2020; the [FreeDict reuse guidance](https://freedict.org/documentation/) says to check each dictionary's own terms. It provides English equivalents/examples, not Slovene-language definitions, and is modest in coverage. |
| **Dictionary of Standard Slovenian (SSKJ)** | A source of Slovene definitions exists, but redistribution and in-app reuse terms were not verified. Do not bundle it without checking the current terms with the rights holder. |
| **WordNet / Open English WordNet; Slovene WordNet (sloWNet)** | Candidate lexical resources, but their exact current licences, content coverage and suitability for redistribution were not verified for this report. Treat as leads, not selected sources. |

**What this means for us**

- **English:** Wiktionary-derived content is the most promising evaluated source for broad monolingual definitions, but pack-building requires substantial filtering, attribution and a licence audit. A **10–60 MB trimmed pack is only a target estimate**, not a measured result.
- **Slovene lookup:** Sloleks 3.1 provides a current form/lemma resource under CC BY-SA 4.0; FreeDict adds a small Slovenian→English translation dictionary. These improve inflection handling and glossing but do **not** provide Slovene-language explanatory definitions.
- **Licensing:** CC BY-SA 4.0 does not prohibit commercial use, but attribution and ShareAlike obligations can apply to adaptations. FreeDict's Slovene–English file is marked GPL-3.0-or-later; its own header and reuse guidance should be followed. Keep sources as separately identified packs with source/licence metadata to make compliance and updates manageable; this is risk reduction, not a legal conclusion about what counts as an adaptation.
- **Lookup speed:** indexed lookup in a sorted index or SQLite is in the sub-millisecond to low-millisecond range in theory. I have not measured it on-device.
- **Size on disk:** English target 10–60 MB *[estimate]* after selecting common entries and dropping nonessential fields; unmeasured. FreeDict's compressed Slovene→English StarDict archive is about 120 KB. Sloleks 3.1's full source archive is 263 MB; the derived morphology pack size needs measuring.

### 2b. System dictionary / Android "Define"

- Android exposes no general-purpose public intent that returns a dictionary definition to the caller. The closest standard mechanism is [`Intent.ACTION_PROCESS_TEXT`](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT), for launching another app to process selected text.
- `ACTION_PROCESS_TEXT` is designed for external processing and may return replacement text to the selection host; it does not define a structured dictionary-result contract. A receiving app controls its own UI and response, so this cannot reliably supply our inline definition. It could be offered as a best-effort external fallback only after verifying a handler is installed. Google/Chrome define surfaces are not a stable app API.
- Languages, size and licence: whatever the third-party app has. At most we may receive replacement text according to the intent contract, not structured dictionary definitions or senses.

### 2c. Online API

| API | Notes |
|---|---|
| **Free Dictionary API** (dictionaryapi.dev) | Candidate English API; coverage, service terms, attribution requirements, rate limits and operational guarantees were not verified. Do not plan around it until those are checked. |
| **Wiktionary REST / MediaWiki API** | Can expose Wiktionary content, including Slovene entries on English Wiktionary. Output format and API policy depend on endpoint; attribution/licensing and request limits need implementation-time review. |
| **Merriam-Webster, Oxford, Collins, etc.** | Paid, English-centric; caching and redistribution depend on the provider contract. Terms were not reviewed. |
| **Our own backend** (we already have Supabase and Parrot Cloud) | Could serve a cleaned, cacheable Wiktionary-derived DB with full control. Costs hosting and upkeep. |

Online lookup adds a network round trip; latency is unmeasured and varies with network/service conditions. Every option here **sends the selected word off the device** (see §7).

---

## 3. Word forms and lemmatisation

**None exists in the app today.** *[repo]*

- **English:** use a dictionary's explicit form table where available; stemming is a fallback, not equivalent to lemmatisation. StarDict synonym data may contain aliases, but coverage varies by source.
- **Slovenian:** highly inflected (cases, grammatical gender and dual number, with irregular stems and alternations). "knjigami" → "knjiga" is best handled by a lexicon, not suffix rules. Sloleks 3.1 supplies lemmas and forms and is CC BY-SA 4.0 (§2a); its form→lemma data can be extracted at pack-build time. It has no definitions.
- **Fallback:** exact lookup → Sloleks form table → optional conservative suffix guess. A guess must not be presented as a verified lemma. Do not fall back to the 7,375-word Kaikki Slovene subset as though it had comprehensive inflections.
- **Quality for Slovenian:** no on-device lookup accuracy was measured. Sloleks is a strong practical morphology source, but measure coverage on a representative corpus and check ambiguity/multiple analyses before committing to UX claims.

---

## 4. Choosing the language

Inputs available today:

- **Book language:** `EpubPublication.language` (BCP 47, nullable) *[repo]*, stored in `books.language` (`Book.sq:12`). Already used by recaps.
- **App language:** `Locale.current.language`.
- **Existing helper:** `RecapLanguages.resolve(bookLanguage, appLanguage)` in `feature/reader/domain/.../recap/RecapReadTracker.kt:173`. It normalises `sl-SI` → `sl` and falls back to the app language. It is restricted to the recap set (`en, sl, de, fr, es, it, hr`), so for the dictionary we need either a copy with its own supported set or a shared, generalised helper.

Recommended order:

1. Book language, if a dictionary pack exists for it.
2. Otherwise the language of the selected word, detected by a script/character test plus a cheap detector (see below).
3. Otherwise the app language.

**Book with no language set:** detect from the word. Slovene-specific letters (č, š, ž) are a strong but incomplete signal; most short words are ambiguous. Ask once ("Which language is this book?") and remember it per book, or use the book title/description as extra context.

**Mixed-language book** (quoted foreign phrases, bilingual editions): the book language is wrong for some words. Offer a small language switch on the dictionary sheet, and if the word is not found in the book language, try the next pack before showing "define none".

**Language ID:** single-word language identification is inherently ambiguous and should not override an explicitly set book language. A simple alternative is to search installed packs and use a unique hit; if more than one pack matches, ask the reader or show a language switch.

---

## 5. Translation

- **On-device, ML Kit Translation:** Google's [supported-language list](https://developers.google.com/ml-kit/language/translation/translation-language-support) includes Slovenian (`sl`). Official [Android](https://developers.google.com/ml-kit/language/translation/android) and [iOS](https://developers.google.com/ml-kit/language/translation/ios) guides describe on-device translation with on-demand models of around 30 MB per language and recommend Wi-Fi downloads. Once the model(s) are available, translation runs locally. Confirm actual storage, memory and pair behavior in a prototype. Translation quality for a single isolated word is not established by these docs; test it against dictionary glosses.
- **Apple Translation framework:** a native alternative exists on recent iOS releases (public framework is iOS 18+); language availability and runtime support are device/OS dependent and were not confirmed for Slovenian here. It would be a separate native path, not shared Kotlin logic.
- **Other on-device alternatives:** Bergamot/Firefox Translations and Argos/OpenNMT were not evaluated; do not assume Slovenian pair coverage or a smaller integration cost.
- **Cloud API** (Google/DeepL/our own LLM endpoint): best quality and context, costs per character, and **sends the text off the device**. We already have a server-side path for recaps (`CloudRecapEngine`) that could be adapted, which also gives us a place for rate limits and consent.
- **Bilingual dictionary, no network:** for Slovene→English, FreeDict's verified `slv-eng` pack has 5,555 headwords (§2a), and Wiktionary entries may include translations. A dictionary returns curated equivalents/senses, not necessarily a fluent translation. Coverage and inflected-form gaps remain; no reverse English→Slovene FreeDict pack was verified.
- **Sentences:** only machine translation (on-device or cloud) can do these.

Suggested split: single word → dictionary entry/gloss first, with on-device ML Kit as an optional fallback. Phrase or sentence → ML Kit on-device, or cloud translation behind an explicit disclosure.

---

## 6. Offline and e-ink

- **Offline:** a downloaded pack works with no network. System intent needs the other app. Online APIs need a network. Cache every online answer so repeat lookups work offline.
- **Pack flow — what we have.** *[repo]* The voice-pack flow is `TtsModelManager` (androidMain, 818 lines): a manifest hosted on GitHub releases (`github.com/RetRo99/tts-models/releases/latest/download/manifest.json`), per-version directories, per-file size and `isInstalled` checks, resume of interrupted installs, unchanged files reused on update, a disk-space check with margin, progress reporting, and updates that are never installed implicitly. UI state is `PackUiState` (`NotDownloaded`, `TermsRequired`, `Downloading`, `Finishing`, `Downloaded`, `UpdateAvailable`, `Failed`, `Deleting`) with `VoicePackCard`. A foreground service (`TtsVoicePreparationForegroundService`) handles long downloads.
- **Reusable?** The *design* is reusable and `PackUiState` already matches what we need. The *code* is not directly reusable: `TtsModelManager` is Android-only, ties to TTS model IDs (`KOKORO_MODEL_ID`, `SUPERTONIC_MODEL_ID`), and `derivePackState` takes `NeuralVoicePackage`/`TtsVoice`. Expect a refactor into a generic `DownloadablePackManager` (manifest, versions, verify, resume, delete) in a shared module, with TTS and dictionary as two clients. iOS has no equivalent today (`IosTtsController` exists but model download is Android-side).
- **E-ink lookup speed:** not measured. An indexed lookup is cheap, so the cost is UI, not data. On e-ink the main concern is refresh: show the definition in the same toolbar/sheet without animation, and avoid intermediate states (no spinner → result flicker). An inline definition should be ready in one frame for a local pack. For a cold pack, open the index lazily on first lookup and keep it open while the reader is up. Measure on the actual target device before deciding.

---

## 7. Privacy

| Option | Selected text leaves the device? |
|---|---|
| Offline pack (StarDict, SQLite) | No. Only the pack download happens, and it carries no user text. |
| ML Kit on-device translation | Selected text is processed locally once the model is available. Model downloads contact Google services; Android and iOS have separate SDK/download paths. |
| System intent (`ACTION_PROCESS_TEXT`) | Depends on the receiving app. We cannot know. |
| Online dictionary API | **Yes**, the word (and the request IP). |
| Cloud translation | **Yes**, the word or sentence. |

**Disclosure.** Recaps set the pattern *[repo: `docs/RECAPS.md`]*: a settings toggle (**default off**), nothing captured or sent unless on, withdrawal that purges queued data, the API receiving only the minimum (`excerpt`, `language`, `lastSentence`, no titles or IDs), and logs that carry status codes only. Anything that sends selected text should copy this: an explicit opt-in with plain wording on what is sent, default off, no text in logs, and a visible indicator in the sheet ("Looked up online"). Offline packs need no consent, but the UI should say which source answered.

---

## 8. Selection rules

What the selection model gives us *[repo]*: `P.selection` (`SavedPageScript.kt:343`) returns the `quote` string from a DOM range plus `before`/`after` context, a bounding rect, and a `tooLong` flag. There is **no word-level logic**. Existing code only has `/\S+/g` tokenisation in a different script function (line 266) and sentence logic for TTS.

To decide "one word" cheaply, at the Kotlin side on the quote:

1. Trim whitespace and surrounding punctuation (including “ ” « » „ ‟ and ( ) [ ]).
2. One word if the remainder has **no whitespace** after normalising.
3. Inside the word, allow apostrophes (`'`, `’`) and hyphens (`-`, `‑`) where surrounded by letters. Preserve Unicode combining marks; allow digits only when the token also contains a letter.
4. Reject an empty token or any selection containing multiple tokens. Implement with Unicode-aware letter/mark categories (and test Kotlin/JVM and Kotlin/Native behavior); the illustrative shape is `letter/mark+ (apostrophe-or-hyphen letter/mark+)*`, not an ASCII-only regex.

Edge cases:

- **Hyphenated words:** select "well-known" → treat as one word, look up the whole first, and fall back to the parts.
- **Apostrophes:** preserve straight and curly forms and try an exact lookup first. Contraction handling must be language-aware (`can't` → `cannot`); do not blindly strip `'s`, which can mean *is*, *has* or possession.
- **Words split across lines:** inside one DOM text node a visual line wrap doesn't add characters, so the quote is still one word. A soft hyphen (U+00AD) can appear inside the quote; strip it.
- **Words split across pages** (column layouts): the selection is bounded by the page's DOM range. A word that starts on one page and continues on the next is in a single DOM range only if the renderer keeps it in one node; a selection cannot normally span pages, so this case is rare. I have not verified it in the Readium column layout.
- **Phrase:** anything with two or more words → the toolbar shows no define button (or offers translate if that exists).
- **Expand-to-word:** a long-press on a word gives a one-word selection already. If we want to catch half-selected words, extend the range to word boundaries in JS before sending the quote.

---

## 9. "Save word" and the saved-item model

Current model *[repo]*:

- `SavedItemType` has `Bookmark("bookmark")` and `Highlight("highlight")` only (`feature/saved/domain/.../model/SavedItem.kt:5`). `fromId` falls back to `Bookmark` for unknown ids, so an old client would show a saved word as a bookmark.
- Table `saved_items` (`SavedItem.sq`, created in `34.sqm`): `id, book_key, book_uuid, book_title, book_author, type, href, media_type, progression, total_progression, position, chapter_title, text_before, text_quote, text_after, color, note, audio_href, audio_ms, snippet_pending, created_at, updated_at, deleted_at, remote_revision`. A note is any item with note text; deletes are tombstones.
- Sync: entity type `saved_item` through the existing push/pull protocol, stored in `public.cloud_saved_items` (payload JSONB) with last-write-wins on client `updated_at`. The Supabase function **rejects any `type` not in `('bookmark','highlight')`** (`20261006000000_parrot_cloud_saved_items.sql:112`). The client side is `ParrotCloudSavedItemSync.kt`.

What "Save word" needs:

1. **New type** `Word("word")` in `SavedItemType`, and a change to the `fromId` fallback so unknown types from the future don't become bookmarks (skip or hide instead).
2. **Server migration** to allow `'word'` in the type check (new migration file; this is a server deploy and must ship **before** clients that push words, or pushes fail).
3. **Where to keep dictionary data.** Reuse `text_quote` for the selected form and context fields for location. Add `lemma` and `language` if needed. Storing a definition snapshot preserves offline readability but copies licensed dictionary text into local storage and possibly Supabase; confirm that the source licence permits the intended display, export and sync. The lower-risk initial option is **word/form + lemma + language**, resolving definitions from the installed pack.
4. **Saved UI:** `ReaderSavedItems.kt` (643 lines), filters (`SavedFilter`), export (`SavedItemsExport`), and `SavedMarks.marks()` all assume bookmark/highlight. Words need a row style, a filter chip, an export format, and a decision on whether the word is also underlined in the text (`SavedMarks` draws marks only for items with an anchor and only highlights are tappable).
5. **Dedupe:** the same word saved twice in one book. Decide by `(book_key, lemma)` or allow duplicates by location.
6. **Tests:** `ParrotCloudSavedItemSyncTest`, `supabase/tests/saved_items_test.sql`, and the domain tests need new cases.

Sync risk: this is a protocol and schema change across app, local DB and server, so it needs a forward-compatibility plan (old clients receiving an unknown type).

---

## 10. iOS

- **`UIReferenceLibraryViewController`**: UIKit has a system dictionary lookup controller and a `dictionaryHasDefinition(forTerm:)` existence check. It is a presentation API, not a public API for retrieving definition text, so it cannot supply our inline short definition. Dictionary availability is controlled by the OS/user; do not promise Slovenian coverage without device testing. Check current SDK availability/deprecation and test on the app's minimum iOS version before adopting it.
- **`DCSCopyTextDefinition`**: a macOS DictionaryServices API; no supported public iOS text-return API was verified. Do not base the inline implementation on it.
- **Translation:** Apple's [`Translation` framework](https://developer.apple.com/documentation/translation) is a native iOS 18+ option. Slovenian support/availability and device requirements were not verified; check runtime language availability before showing the action. ML Kit's own iOS SDK is a separate on-device alternative and its supported-language list does include Slovenian (§5).
- **What can't match Android:** no inline definition from the system; no silent lookup; offline availability depends on what the user installed. An own-pack approach (SQLite in shared Kotlin code) is the only way to get the same inline experience on both platforms.
- Note that on iOS the WebView selection menu is suppressed from Swift (`shouldShowMenuForSelection` returns false), so presenting a UIKit sheet from the Kotlin side needs a new bridge call in `ReadiumEpubReaderBridge.swift`.

---

## Recommendation

### Best low-cost option for English definitions + Slovene→English glosses

**Use separately licensed offline data packs, downloaded on demand, for the inline lookup; do not make system dictionary APIs the core implementation.**

- **Pack format:** indexed SQLite (or another measured indexed format), keeping source/licence/version metadata with each pack. A shared lookup interface can be used on Android and iOS; confirm SQLDelight is the right runtime fit for a read-only downloaded DB.
- **English:** generate a trimmed Wiktionary-derived pack (common headwords, selected short senses, required form table). Preserve source attribution and licence information; audit third-party content and ShareAlike implications before distributing.
- **Slovene:** use Sloleks 3.1 for form→lemma resolution (CC BY-SA 4.0) and FreeDict `slv-eng` for English glosses (GPL-3.0-or-later, 5,555 headwords). This gives offline Slovene→English support for covered terms, not SSKJ-style Slovene definitions; coverage needs corpus testing. Confirm redistribution/source-offer obligations for the FreeDict-derived pack before release.

  **I did not verify a redistributable source of Slovene-language definitions.** This remains a product/licensing decision. English glosses for Slovene words are feasible from FreeDict but are not the same feature.
- **UI fallbacks:** show "define none" when no installed pack has a match. "More" opens the full local entry. A system dictionary sheet can be an optional iOS fallback only after availability testing; it still cannot provide the inline definition.
- **Translation:** use bilingual pack data for single words. ML Kit supports Slovenian on Android and iOS as an optional on-device translation fallback; test sentence quality and actual model storage first.

### What it can't do

- Sloleks 3.1 supports a substantial morphology lookup, but lookup recall/ambiguity has not been measured.
- Slovene-language definitions (SSKJ-quality) unless an appropriate source and licence are cleared.
- Context-aware sense choice (it shows the senses; it doesn't pick one).
- Phrases and sentences (no define; translate needs ML Kit or the cloud).
- Inline system definitions on iOS, or any data from an Android "Define" intent.
- Unknown terms, names and brand-new words.

### Rough size and effort

These are estimates from the code I read, not measured. Effort is for one developer.

| Option | Disk / network | Effort | Notes |
|---|---|---|---|
| **A. Own offline packs (recommended)** — English definitions + Slovene morphology/gloss pack, shared inline lookup and sheet on Android + iOS | English pack target ~10–60 MB *[estimate, unmeasured]*; FreeDict slv-eng archive ~120 KB; full Sloleks 3.1 source ZIP 263 MB, trimmed pack size TBD | **~3–5 weeks**: pack pipeline (about 1 wk), reader lookup/selection (1 wk), toolbar/sheet/e-ink states (1 wk), generic downloader refactor (about 1 wk), iOS bridge/QA (0.5–1 wk) | Slovene forms come from Sloleks 3.1; gloss coverage is limited to the FreeDict pack. Licence review is a release gate. |
| **B. System-only fallback** — `ACTION_PROCESS_TEXT` on Android, UIKit dictionary sheet on iOS | 0 MB | **~2–4 days** | No inline definition and no reliable "define none" detection. Handler/dictionary availability varies. Suitable only for an external "More" action. |
| **C. Online API** — Wiktionary endpoint or our Supabase endpoint, with caching | Tiny app; needs network | **~1–2 weeks** (own backend: +1–2 wks) | Terms/rate limits and parsing need validation; fails offline; disclosure/consent required (§7). |
| **D. Translation add-on** — ML Kit on-device | Around 30 MB per language model *[source estimate]* | **~1–2 weeks** across Android + iOS, excluding QA | Supports Slovenian on both platforms; separate platform integration and model download management. Translation, not dictionary definitions. |
| **E. "Save word"** on top of A, B or C | small | **~1–1.5 weeks** including the Supabase migration, model, Saved UI and sync tests | Server migration must ship first (§9) |

### Decisions needed before planning

1. Are English glosses for Slovene words acceptable, or are Slovene-language definitions a requirement?
2. Is online lookup acceptable with an explicit opt-in and disclosure?
3. Can you provide the six `marks-define*` / `marks-dict*` PNGs? They are not in `design/ember/`.

## Sources checked (2026-10-05)

- [Kaikki English dictionary](https://kaikki.org/dictionary/English/index.html), [Kaikki Slovene dictionary](https://kaikki.org/dictionary/Slovene/index.html), and [raw-data sizes/status](https://kaikki.org/dictionary/rawdata.html); extracted 2026-10-03 from the 2026-09-02 English Wiktionary dump.
- [Wiktionary copyright page](https://en.wiktionary.org/wiki/Wiktionary:Copyrights). The page itself warns that it is outdated; use it as a pointer and verify current Wikimedia terms and third-party content before distribution.
- [Sloleks 3.1 CLARIN.SI record](https://www.clarin.si/repository/xmlui/handle/11356/2080), including current entry count, archive size and CC BY-SA 4.0 metadata.
- FreeDict [download/API](https://freedict.org/downloads/) and [licensing/reuse guidance](https://freedict.org/documentation/). The `slv-eng` count, StarDict archive, and GPL-3.0-or-later declaration were checked in the API record and that release's TEI header.
- Google [ML Kit translation supported languages](https://developers.google.com/ml-kit/language/translation/translation-language-support), [Android guide](https://developers.google.com/ml-kit/language/translation/android), and [iOS guide](https://developers.google.com/ml-kit/language/translation/ios).
- Android [`ACTION_PROCESS_TEXT`](https://developer.android.com/reference/android/content/Intent#ACTION_PROCESS_TEXT) API reference.
- Apple [`UIReferenceLibraryViewController`](https://developer.apple.com/documentation/uikit/uireferencelibraryviewcontroller) and [`Translation`](https://developer.apple.com/documentation/translation) API references. Current dictionary availability/deprecation and Slovene language availability remain device/SDK checks.

## Final recommendation and rough estimate

For the requested inline reader experience, build a **local indexed lookup with on-demand packs**, not a system-intent-only feature. Start with a trimmed English definition pack; use **Sloleks 3.1** for Slovene form→lemma lookup and **FreeDict slv-eng** for modest Slovene→English gloss coverage. Keep the sources in separately identified packs and clear the CC BY-SA/GPL obligations before distributing them. State plainly that Slovene-language definitions are not solved by this source set. Add ML Kit later only if sentence translation is needed; Slovenian is supported on Android and iOS, with models around 30 MB per language.

**Planning estimate (one developer): 3–5 weeks** for both platforms and the core offline feature. Aim for an English pack of **10–60 MB**, but treat that as an unmeasured target; FreeDict slv-eng's compressed archive is **about 120 KB**, while the complete Sloleks 3.1 ZIP is **about 263 MB** and must be reduced to a form/lemma table. Budget a short prototype to measure actual installed pack sizes and lookup latency. If a suitable Slovene definitions source is found, allow **1–2 weeks of integration**; source discovery, legal review and rights-holder clearance have an unknown schedule and are not included.
