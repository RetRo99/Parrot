# Project 2: Linked Books Across Servers — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan slice by slice. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** The user can say "these are the same book" across Storyteller, Audiobookshelf and their
own library (device and Parrot Cloud). The app suggests likely matches for the user to confirm. A
linked book shows as one card listing every place it exists.

**Architecture:** A *link* is a small record that groups *copies*. A copy is a book on one source,
named by a portable key: `library:<bookId>`, `storyteller:<uuid>` or `audiobookshelf:<itemId>`.
Links are always explicit: made by hand or approved from suggestions. They are stored locally and
synced through Parrot Cloud. Linking never moves files, never changes progress, and never writes
to Storyteller or Audiobookshelf servers.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight, Koin, Supabase (Postgres
RPCs and pgTAP tests).

**Spec:** Part 1 of this document.

**Depends on:** project 1 (`2026-09-30-one-book-many-copies.md`) being merged. This plan uses
project 1's names: `bookId`, `LibraryBook`, `BookHome`, `HomeBadge`, `LOCAL_SERVER_ID` meaning
"your library", and `book.home`.

**Next:** project 3 (`2026-10-01-progress-across-linked-copies.md`) uses these links to carry your
reading position between copies.

**Level of detail:** the decisions, rules, schemas, interfaces and pure logic below are final.
File paths for code that project 1 is still changing may have moved. Check them with `grep`
before editing, and write each task's test cases as listed before the implementation.

## Decided: anyone can link

The user decided on 2026-10-01 that linking needs no account.
- Anyone can link books on their device, with or without Parrot Cloud.
- Links sync to other devices only while a Parrot Cloud account is active. Until then they stay
  in the outbox, the same as other Parrot data.
- Never hide or disable the link actions because Parrot is inactive.

## Global Constraints

- Everything in project 1's "Global Constraints" still applies: no reusing the abandoned branch,
  minimal functional UI (the user designs later), one slice per PR with a working app each time,
  never commit/push without asking, Supabase local stack only, Kotlin style, strings at the
  bottom of `strings.xml` with `tools:ignore="MissingTranslation"`, the repo's test style, and
  honest reporting.
- **Never link automatically on metadata.** Links are created only when the user acts: a manual
  link, or approving a suggestion (including "Link all confident").
- **Never change another server's data** in this project. No progress, metadata or files are
  written to Storyteller or Audiobookshelf.
- **Performance:** suggestions must not block the UI. Run them on `Dispatchers.Default` and cache
  the results per library snapshot (see §1.4).

---

# Part 1 — Design

## 1.1 Why

- The same book in Storyteller, Audiobookshelf and your library shows as separate cards.
- There's no way to tell the app "these are the same book".
- Project 3 (progress between copies) needs to know which copies belong together.

**Prior art:** BookBridge (`cporcellijr/bookbridge`) and Storyteller's KOReader sync plugin both
use explicit, user-approved links, stored once and reused. BookBridge suggests pairs for batch
review and never merges on metadata. We copy the pattern, not the code.

## 1.2 Decisions

| # | Decision | Why |
|---|---|---|
| L1 | A link groups two or more copies of one book from **different** sources. A copy belongs to at most one link. | Keeps the model a simple partition. Duplicates *within* one server are out of scope. |
| L2 | Links are explicit: a manual link, or an approved suggestion. | Metadata can't tell editions or translations apart. A wrong automatic merge is worse than a duplicate. |
| L3 | A copy is named by a portable key: `library:<bookId>`, `storyteller:<uuid>`, `audiobookshelf:<itemId>`. Server config IDs aren't used. | Server config IDs are random on each device (`ServerRegistryImpl` uses `Uuid.random()`). The cached `books` table already treats server book UUIDs as globally unique (`books.uuid` is the primary key). |
| L4 | To open a copy on a device, **resolve** its key to `(serverId, uuid)`. For `library:*` use `(LOCAL_SERVER_ID, bookId)`. For others, find a configured server of that type whose cached books contain the ID. A key that doesn't resolve is kept but ignored. | The same link works on every device, even one that only has some of the servers. |
| L5 | A linked book shows as one card. The card opens the **primary** copy (see §1.3). The detail screen lists the other copies under "Also in". | One card per book is the whole point. Routing stays `(serverId, uuid)`, so the reader and Android Auto don't change. |
| L6 | Suggestions are computed on the device from all listed books, and scored as in §1.4. The user reviews them one at a time or in a batch. | This is what BookBridge learned works. |
| L7 | "Not the same book" removes a copy from its link and records a **never** decision for each affected pair. "Skip" hides a suggestion for 30 days. | Stops the same suggestion coming back. |
| L8 | Links and never-decisions sync through Parrot Cloud as new entity types (`book_link`, `book_link_decision`) in the existing push/pull protocol. | It reuses the working outbox, revisions and change feed. |
| L9 | `ServerBook` gains `language`, `isbn` and `asin`. Audiobookshelf already returns them. Storyteller returns `language`. EPUB imports read `dc:identifier` for an ISBN. | Identifiers make the strongest suggestions. |

## 1.3 Rules

**Primary copy** of a link, used to route the card:
1. The copy opened most recently on this device. Use `lastOpenedAt`, or "currently reading" if
   it's one of them.
2. Otherwise, by home: Parrot Cloud, then This device, then Storyteller, then Audiobookshelf.
3. Tie-break on the key string, so the choice is stable.

**The card for a linked book:**
- Title, cover and author come from the primary copy.
- The badges show every distinct home in the link, in the priority order above, using project 1's
  `HomeBadge` repeated. Hide them when the list has only one home overall, as today.
- The downloaded icon shows if any copy has a device copy or a cached download.
- Progress comes from the primary copy. Project 3 changes this to "the latest across copies".

**List behaviour:**
- **Search:** a linked book matches if any copy matches.
- **Source filter:** matches if any copy has that home.
- **"Downloaded" filter:** matches if any copy is downloaded.
- **Sort by title:** uses the primary copy's title.

**Detail screen (the primary copy's detail, unchanged apart from these additions):**
- An **"Also in"** list, with one plain row per other *resolved* copy: its `HomeBadge`, its formats
  (ebook, audio, read-aloud), and an **Open** button that navigates to that copy's detail.
  - Each row has a **"Not the same book"** action that unlinks that copy.
- A **"Same book as…"** action. It opens a picker listing books from **other** sources, using
  the existing library list and search components with no new design. Picking one links it.
  - If either copy is already in a link, the links merge, as long as the merged link still has
    only one copy per source. Otherwise show the error `link_error_same_source`.

**Suggestions entry point:** when at least one suggestion is pending, the library list shows a
single text row at the top: "%d books may be the same across your servers · Review".
- Tapping it opens a review screen with one suggestion per row: both titles, both authors, both
  sources as `HomeBadge`, and the score reason.
- Each row has three buttons: **Link**, **Not the same book** and **Skip**.
- At the top: **"Link all %d confident matches"**, where confident means a score of at least 90.

## 1.4 Suggestion scoring (final)

Candidates are books from different sources.
- Library copies count as one source, whether on the device or in Parrot.
- A pair is skipped if either copy is already in a link with a copy from the other copy's source.
- A pair is skipped if it has a never-decision, or a skip-decision younger than 30 days.

```kotlin
/** Returns 0..100, or null when the pair must not be suggested. */
fun scorePair(a: LinkCandidate, b: LinkCandidate): SuggestionScore? {
    if (a.source == b.source) return null
    if (a.language != null && b.language != null &&
        a.language.take(2).lowercase() != b.language.take(2).lowercase()
    ) {
        return null
    }
    val sharedIdentifier = a.identifiers.intersect(b.identifiers).isNotEmpty()
    if (sharedIdentifier) return SuggestionScore(100, SuggestionReason.IdentifierMatch)

    val titleScore = tokenSortSimilarity(normalizeTitle(a.title), normalizeTitle(b.title))
    val authorScore = bestAuthorSimilarity(a.authors, b.authors) // null if either is empty
    val score = if (authorScore == null) titleScore else titleScore * 0.7 + authorScore * 0.3
    // Keep when the combined score is good, or when the title alone is very strong
    // (the author strings may just be formatted differently). Anything under 90 is only
    // reviewable, never "confident".
    if (score < 75 && titleScore < 85) return null
    return SuggestionScore(score.roundToInt(), SuggestionReason.TitleAndAuthor)
}
```

- `identifiers` are normalized ISBN-13s (convert ISBN-10 to ISBN-13; digits only), plus ASINs as
  `asin:<value>`.
- **`normalizeTitle`**, in order:
  1. Lowercase, and fold accents to ASCII.
  2. Straighten curly quotes, and turn dashes into `-`.
  3. Remove leading numbering such as `01. `, `02 ` or `1) `.
  4. Remove up to three trailing parentheticals or brackets, such as `(Unabridged)`,
     `(readaloud)`, `(Dramatized)`, `[2024]`.
  5. Remove a trailing `, book N` or `book N`.
  6. Turn `Title, The` into `the title`, and the same for `a` and `an`.
  7. Remove all punctuation.
  8. Collapse whitespace.
- **`tokenSortSimilarity(x, y)`:** split on spaces, sort, and join. The result is
  `100 * (1 - levenshtein(x', y') / max(len(x'), len(y')))`. Two empty strings score 0.
- **`bestAuthorSimilarity`:** normalize each author as in steps 1, 7 and 8 above, and turn
  `Last, First` into `first last`. The result is the highest `tokenSortSimilarity` over all
  author pairs.
- **Confident** means a score of at least 90. Every other kept pair goes to review. That includes
  pairs under 75 kept because of a strong title.
- **Blocking, to avoid comparing every pair:** build an index from normalized title tokens (at
  least 3 characters, excluding `the`, `and`, `of`, `a`, `an`) to candidates. Only compare pairs
  that share at least one token.
- **Caching:** cache results under a hash of the sorted `(key, title, authors, identifiers)` list
  plus the links and decisions. Recompute only when that hash changes.

## 1.5 Wording (new keys, added at the bottom of `strings.xml`)

| Key | Value |
|---|---|
| `link_also_in` | Also in |
| `link_open_copy` | Open |
| `link_not_same_book` | Not the same book |
| `link_same_book_as` | Same book as… |
| `link_picker_title` | Choose the same book on another server |
| `link_error_same_source` | These books are both from %1$s. Only books from different servers can be linked. |
| `link_suggestions_banner` | %1$d books may be the same across your servers · Review |
| `link_review_title` | Same book? |
| `link_review_link` | Link |
| `link_review_skip` | Skip |
| `link_review_link_all_confident` | Link all %1$d confident matches |
| `link_reason_identifier` | Same ISBN |
| `link_reason_title_author` | Similar title and author |
| `link_unlink_confirm_title` | Not the same book? |
| `link_unlink_confirm_message` | “%1$s” on %2$s will show as a separate book again. |

---

# Part 2 — Slices

| Slice | PR | The app afterwards |
|---|---|---|
| 1 | Backend: link entities | Unchanged. The backend accepts and serves links. |
| 2 | Client: link data, sync and manual linking | "Same book as…" and "Not the same book" work. Cards aren't merged yet. |
| 3 | One card per linked book | Linked books show as one card with "Also in". |
| 4 | Suggestions and review | The banner, review screen and "Link all confident" |

## Slice 1 — Backend: link entities

### Task 1.1: Tables, RPCs and pgTAP tests

**Files:**
- Create: `supabase/migrations/<timestamp>_parrot_cloud_book_links.sql`
- Create: `supabase/tests/book_links_test.sql`

**Schema:**

```sql
create table public.cloud_book_links (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    id uuid not null,
    members text[] not null,
    revision bigint not null default 1,
    created_at timestamptz not null default timezone('utc', now()),
    updated_at timestamptz not null default timezone('utc', now()),
    deleted_at timestamptz,
    primary key (cloud_user_id, id),
    check (cardinality(members) >= 2)
);

create table public.cloud_book_link_decisions (
    cloud_user_id uuid not null references auth.users(id) on delete cascade,
    pair_key text not null,
    decision text not null check (decision in ('never', 'skip')),
    decided_at timestamptz not null,
    revision bigint not null default 1,
    primary key (cloud_user_id, pair_key)
);
```

Enable RLS with owner-only policies, following `cloud_reading_sessions` in
`20260925000003_parrot_cloud_reading_sessions.sql`.

**`push_sync_changes`:** start from the latest definition and add these entity types:
- **`book_link` upsert:**
  - The payload is `{ link_id, members[], deleted }`.
  - Reject when `members` has fewer than 2 entries or two members share a source prefix, with
    reason `invalid_link`.
  - If any member already belongs to another *live* link of the same user, return `conflict` with
    that link's payload. The client merges the two links and pushes again.
  - Otherwise apply the same revision rules as `library_book`, and emit a sync change.
- **`book_link` delete:** set `deleted_at` and emit a change.
- **`book_link_decision` upsert:** last write wins on `decided_at`. Emit a change.

**`pull_sync_changes`:** return both entity types.

- [ ] **Step 1: Write the failing pgTAP tests:**
  1. A link with 2 members from different sources is accepted.
  2. A link with 2 `storyteller:` members is rejected with `invalid_link`.
  3. A second link containing a member of a live link returns `conflict` with the first link.
  4. A stale revision returns `conflict`.
  5. A delete makes the link disappear from active queries, and a pull emits it.
  6. The RLS isolation test: user B can't read or modify user A's links.
- [ ] **Steps 2–4:** Watch them fail, implement, and watch them pass
  (`scripts/supabase/reset.sh && scripts/supabase/test.sh`). Stop and report. Ask before
  committing.

## Slice 2 — Client: link data, sync and manual linking

### Task 2.1: Local tables (migration 28)

**Files:**
- Create: `lib/database/implementation/.../sqldelight/.../28.sqm`, `BookLink.sq`
- Modify: `lib/database/implementation/build.gradle.kts` (`version = 28`)
- Create: `lib/database/api/.../links/BookLinksDatabase.kt`, `BookLinkEntity.kt`
- Test: `lib/database/implementation/src/androidHostTest/.../BookLinkQueriesTest.kt`, plus a
  27→28 migration test in the style of project 1's `OneBookIdMigrationTest`

```sql
CREATE TABLE book_links (
    link_id TEXT NOT NULL PRIMARY KEY,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    remote_revision INTEGER,
    deleted_at TEXT
);

CREATE TABLE book_link_members (
    link_id TEXT NOT NULL,
    member_key TEXT NOT NULL UNIQUE,
    added_at TEXT NOT NULL,
    PRIMARY KEY (link_id, member_key)
);

CREATE TABLE book_link_decisions (
    pair_key TEXT NOT NULL PRIMARY KEY,
    decision TEXT NOT NULL,
    decided_at TEXT NOT NULL,
    remote_revision INTEGER
);
```

`member_key UNIQUE` enforces L1. Deleting a link deletes its member rows in the same transaction.

**Tests:**
- Inserting the same member into two links fails.
- Deleting a link removes its members.
- `observeLinks()` emits the links with their members.

### Task 2.2: Domain model and pure helpers

**Files:**
- Create: `feature/books/domain/.../model/links/CopyKey.kt`, `BookLink.kt`,
  `LinkDecision.kt`, `LinkRules.kt`
- Test: `feature/books/domain/src/commonTest/.../model/links/LinkRulesTest.kt`

```kotlin
enum class CopySource(val prefix: String) {
    Library("library"),
    Storyteller("storyteller"),
    Audiobookshelf("audiobookshelf"),
}

@Serializable
data class CopyKey(val source: CopySource, val id: String) {
    val value: String get() = "${source.prefix}:$id"

    companion object {
        fun parse(value: String): CopyKey? {
            val prefix = value.substringBefore(':', missingDelimiterValue = "")
            val id = value.substringAfter(':', missingDelimiterValue = "")
            val source = CopySource.entries.firstOrNull { entry -> entry.prefix == prefix }
            return if (source == null || id.isEmpty()) null else CopyKey(source, id)
        }
    }
}

data class BookLink(val linkId: String, val members: Set<CopyKey>)

/** Order-independent key for a pair of copies. */
fun pairKey(first: CopyKey, second: CopyKey): String =
    listOf(first.value, second.value).sorted().joinToString("|")

/** Merges links that share members. Null when the result would repeat a source. */
fun mergeLinks(first: BookLink, second: BookLink): BookLink?

fun BookDomainModel.copyKey(): CopyKey = when (this) {
    is BookDomainModel.LibraryBook -> CopyKey(CopySource.Library, uuid)
    is BookDomainModel.StorytellerBook -> if (serverType == ServerType.Audiobookshelf) {
        CopyKey(CopySource.Audiobookshelf, uuid)
    } else {
        CopyKey(CopySource.Storyteller, uuid)
    }
}
```

**Tests:**
- `parse` and `value` round-trip for every source. Bad input gives null.
- `pairKey` is order-independent.
- `mergeLinks` returns null when the result would contain two copies from one source.

### Task 2.3: Links repository, resolution and sync

**Files:**
- Create: `feature/books/domain/.../BookLinksRepository.kt` (the interface), and the use cases
  `LinkBooksUseCase`, `UnlinkCopyUseCase`, `ObserveBookLinksUseCase` and
  `ResolveCopyUseCase`
- Create: `feature/books/data/.../links/BookLinksDataRepository.kt`
- Modify: the Parrot sync adapter, mutation transport and change appliers in
  `lib/server-parrot-cloud`, to handle the `book_link` and `book_link_decision` entity types.
  Follow how `library_book` is handled after project 1.
- Modify: `lib/server/api/.../ServerBooksRepository.kt` (`ServerBook`): add
  `language: String? = null`, `isbn: String? = null` and `asin: String? = null`. Map them in the
  Audiobookshelf mapper (`AudiobookshelfLibraryItemMapper.kt`: `metadata.isbn`, `asin`,
  `language`) and the Storyteller mapper (`language`).
- Modify: `feature/books/data/.../EpubMetadata.kt` and both platform extractors, to read
  `dc:identifier` values that look like an ISBN (`urn:isbn:…`, or 10/13 digits with an optional
  `X`) into `isbn`. Store the ISBN in `library_books.metadata_json`.

```kotlin
interface BookLinksRepository {
    fun observeLinks(): Flow<List<BookLink>>
    fun observeDecisions(): Flow<Map<String, LinkDecision>>
    /** Links the copies, merging existing links. Fails when a source would repeat. */
    suspend fun link(first: CopyKey, second: CopyKey): AppResult<BookLink>
    /** Removes [copy] from its link and records never-decisions for each pair it had. */
    suspend fun unlink(copy: CopyKey): CompletableResult
    suspend fun decide(first: CopyKey, second: CopyKey, decision: LinkDecisionType): CompletableResult
}
```

**Behaviour:**
- Every write goes through one DB transaction that also enqueues the sync outbox entry. This is
  the same pattern as `library_book` after project 1.
- With Parrot inactive, entries stay in the outbox until an account is linked. That's the
  existing behaviour.
- **On a `conflict` whose payload is another live link:** merge both locally with `mergeLinks`.
  Keep the *older* `link_id`, delete the other, and push both changes. If the merge is impossible
  (a repeated source), keep the server's link, drop the local one, and log it.
- **`ResolveCopyUseCase(key): Pair<String, String>?`:**
  - `Library`: return `(LOCAL_SERVER_ID, id)` if the library lists that book.
  - Otherwise: the first authenticated server of that type whose cached books contain `id`. The
    cache lookup is `books.uuid`.
  - Otherwise null.

**Tests:**
- Linking two unlinked copies creates one link and one outbox entry.
- Linking a copy into an existing link adds the member.
- Linking two copies that are each in a link merges the links.
- Linking two copies from the same source fails.
- Unlinking a copy from a 2-member link deletes the link and records one never-decision.
- A pulled link is stored. A pulled deletion removes it.
- A conflict merge keeps the older ID.
- Resolution returns null when no configured server has the book.

### Task 2.4: Manual linking and unlinking UI (minimal)

**Files:**
- Modify: the book detail ViewModel, state, intent and screen:
  - Add a "Same book as…" action that opens the picker.
  - Add "Also in" rows, but show them only when the book is in a link. Cards merge in slice 3.
  - Add "Not the same book" with a confirmation using `link_unlink_confirm_*`.
- Create: `feature/books/ui/.../links/LinkPickerScreen.kt` and `LinkPickerViewModel.kt`. It reuses
  the library list and search components, filtered to books from other sources. Add a route next
  to the existing book-detail route.
- Modify: `strings.xml` with the keys from §1.5.
- **Tests:** in the picker ViewModel, the list excludes the current book's source. Picking calls
  `LinkBooksUseCase` with both keys. A same-source error shows `link_error_same_source`.

### Slice 2 device check

1. Storyteller book X and a library book Y with the same title: open X, choose "Same book as…",
   pick Y. Both details now show each other under "Also in", and **Open** navigates between them.
2. "Not the same book" on Y: the rows disappear, and the pair never comes back as a suggestion
   (checked in slice 4).
3. With Parrot active on two emulators, a link made on A appears on B after a sync. It only
   resolves on B if B also has that Storyteller server configured.

## Slice 3 — One card per linked book

### Task 3.1: Grouping in the book list

**Files:**
- Modify: `feature/books/domain/.../usecase/GetBooksUseCase.kt` and
  `feature/reader/domain/.../usecase/ObserveAllBooksWithProgressUseCase.kt`. After collecting
  every server's books, group linked copies by link (via `copyKey()`) into one entry. Its primary
  copy is chosen by §1.3, and it carries `linkedCopies`.
- Modify: `BookDomainModel`, adding `open val linkedCopies: List<LinkedCopy> = emptyList()` where:

```kotlin
data class LinkedCopy(
    val key: CopyKey,
    val serverId: String,
    val uuid: String,
    val home: BookHome,
    val hasEbook: Boolean,
    val hasAudiobook: Boolean,
    val hasReadaloud: Boolean,
    val isDownloaded: Boolean,
)
```

- Modify: `BookUiModel` and its mapper. Add `linkedCopies`, and `homes: List<BookHome>` (the
  primary's home first, then the others in priority order).
- Modify: `BooksListViewState` (search, the source filter and the "Downloaded" filter, following
  §1.3) and `BookComponents` (one `HomeBadge` per entry in `homes`, and the downloaded icon if any
  copy is downloaded).
- Create: `feature/books/domain/.../model/links/PrimaryCopy.kt`, containing the pure function
  `choosePrimary(copies, lastOpened, currentlyReading): BookDomainModel`.

**Tests:**
- `choosePrimary`: one table test covering each rule of §1.3, including the tie-break.
- Grouping: three copies in one link give one entry, and unlinked books pass through unchanged.
- An unresolved member doesn't create an empty entry.
- Filters: a source filter for Storyteller matches a linked book whose primary copy is in Parrot.

### Task 3.2: Detail "Also in" for the primary copy

Show the rows from slice 2, now fed from `linkedCopies`. Nothing new beyond that.

### Slice 3 device check

1. Linked Storyteller and Parrot copies show as **one** card with two badges.
2. Search by the Storyteller copy's subtitle still finds the card.
3. Filtering to Storyteller shows it, and so does filtering to Parrot Cloud.
4. Opening the card goes to the most recently opened copy.

## Slice 4 — Suggestions and review

### Task 4.1: Scoring (pure)

**Files:**
- Create: `feature/books/domain/.../model/links/LinkSuggestions.kt`, containing
  `normalizeTitle`, `tokenSortSimilarity`, `levenshtein`, `bestAuthorSimilarity`,
  `normalizeIsbn`, `scorePair` and
  `suggestLinks(candidates, links, decisions, now): List<LinkSuggestion>`, following §1.4.
- Test: `LinkSuggestionsTest.kt`

**Required table cases:**
- `normalizeTitle`:
  - "01. The Hobbit (Unabridged)" gives "the hobbit"
  - "Hobbit, The" gives "the hobbit"
  - "Dune: Book 1" gives "dune"
  - "Pride & Prejudice [readaloud]" gives "pride prejudice"
- `tokenSortSimilarity`:
  - "the hobbit" and "hobbit the" score 100
  - "dune" and "dune messiah" score below 75
- ISBN-10 `0-261-10221-4` and ISBN-13 `9780261102217` give the same identifier.
- Language "en" versus "de" gives null. "en-US" versus "en" is allowed.
- The same source gives null.
- A pair with a never-decision is excluded. A skip-decision 10 days old is excluded, and one 40
  days old is included again.
- Blocking: books sharing no title token are never compared (assert with a counting fake).

### Task 4.2: Review screen and banner

**Files:**
- Create: `feature/books/domain/.../usecase/ObserveLinkSuggestionsUseCase.kt`. It combines all
  books with links and decisions, runs `suggestLinks` on `Dispatchers.Default`, and caches by the
  snapshot hash.
- Create: `feature/books/ui/.../links/LinkReviewScreen.kt` and `LinkReviewViewModel.kt`, with
  minimal UI: a list of rows plus the "Link all confident" button.
- Modify: `BooksListScreen` and its ViewModel to show the banner row (§1.3) when
  `suggestions.isNotEmpty()`.
- **Tests:**
  - The ViewModel: Link calls `LinkBooksUseCase`; Not the same book calls `decide(never)`; Skip
    calls `decide(skip)`.
  - "Link all confident" links only suggestions scoring 90 or more, and reports how many it linked.

### Slice 4 device check

With Storyteller and Audiobookshelf both connected and sharing some titles:
1. The banner appears with a sensible count.
2. "Link all confident" merges the obvious pairs into single cards.
3. A skipped pair returns after its 30 days. Simulate this by changing the decision date in a
   debug build, or report it as not tested.
4. A "Not the same book" pair never returns.
5. With a library of about 1,000 books, the library stays responsive while suggestions compute.
   Report the time taken.

---

# Part 3 — Risks and notes

- **Different editions** (abridged and unabridged, translations): identifiers and the language
  rule filter some out, and the user decides the rest. Never auto-link.
- **Audiobookshelf item IDs:** newer Audiobookshelf uses UUIDs, but older installs used `li_…`
  IDs. Both work as opaque strings. Uniqueness across servers is assumed, as the `books` cache
  already assumes it.
- **A book that exists in two places on one server**, such as Audiobookshelf's separate ebook and
  audiobook items: out of scope. L1 allows one copy per source.
- **The first Parrot sync with many links:** links go through the same ordered outbox as project 1.
  Check that `library_book` entries are sent before a `book_link` that references `library:<id>`.
- **Monetization:** linking is free for everyone, and syncing links needs Parrot Cloud. This was
  decided on 2026-10-01; see the top of this plan.
- **Database wipe on upgrade:** the app deletes the local database whenever the schema version
  changes (`PlatformDatabaseModule.android.kt` and `.ios.kt`), so `.sqm` migrations never run on a
  device today.
  - Adding `28.sqm` therefore wipes all local data on update, including links that haven't synced
    yet.
  - Still write the migration and its test: it becomes real once the wipe is replaced with actual
    migrations, which is a pre-launch item the user still has to decide on.
  - Don't change the wipe behaviour in this project.
