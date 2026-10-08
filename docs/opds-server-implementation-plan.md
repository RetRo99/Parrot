# OPDS server support: research and implementation plan

**Status:** Phase 1 protocol core complete and its gate met; Phase 2 test-first
items 1–7 and catalogue plumbing implemented; Phase 2 gate met. Phase 3
complete and its gate met (2026-10-08): durable acquisition into the library,
clean-up rules, sign-out, and the saved-pages cache, all without a screen (see
the Phase 3 status notes for what is left open).
All user-visible OPDS integration remains unimplemented.
No user-visible OPDS application integration yet. Reviewed
against the codebase on 2026-10-08: open gaps are in §10, the designer brief is
in §11, and design passes 1–3 with the remaining open points are in §11.7.  
**Research date:** 2026-10-08.  
**Platforms:** Android and iOS, with shared Kotlin Multiplatform behavior.

## 1. Goal and decisions

Offer **OPDS as a first-class server type** alongside Storyteller,
Audiobookshelf, Parrot Cloud, and Local. Users can add a public catalogue or their
own OPDS server through the existing server-management experience.

The implementation must be generic: Gutenberg is a preset and interoperability
fixture, not a special repository implementation.

Key decisions:

1. Add `ServerType.Opds`, identifier `opds`, and reuse `ServerRegistry` and
   profile-scoped `ServerConfig` persistence.
2. Add a **paginated catalogue capability** to the server architecture. Do not
   reinterpret `ServerBooksRepository.getBooks()` as a public-catalogue crawler.
3. Support OPDS 1.x/1.2 XML and OPDS 2.0 JSON through one normalized model, in
   shared Kotlin. A source can link between the two versions.
4. Browse remote catalogues on demand. Persist only bounded caches, acquisition
   state, and provenance—not every book in every catalogue.
5. Download supported publications **directly from the provider to the device**.
   No Supabase catalogue backend, file bucket, proxy, Gutendex deployment, or
   mirroring service is required for the core feature.
6. A successful EPUB acquisition becomes a normal local library book, with its
   random library UUID and source provenance retained separately.
7. Anonymous access is legitimate server access, not a fake login/token.
8. First release supports direct DRM-free EPUB acquisition and anonymous/HTTP
   Basic catalogue access. Unsupported formats, lending, purchases, indirect
   acquisition, and auth flows are represented honestly, not silently attempted.
9. No OPDS reading-position sync adapter. Local reading and optional Parrot Cloud
   metadata/progress sync continue through the acquired local book.

“Generic” means a reusable protocol implementation with explicit capability
boundaries; it does not mean claiming every OPDS extension is supported on day one.

## 2. Research findings

### 2.1 Existing project architecture

Paths below are existing files unless explicitly marked proposed. Kotlin paths
are relative to their module's `src/commonMain/kotlin/` unless otherwise noted.

| Area | Existing implementation | Consequence for OPDS |
| --- | --- | --- |
| Server types | `base/.../com/retro99/base/server/ServerType.kt` | Add an enum value; audit exhaustive branches and serialization. |
| Registration | `lib/server/api/.../ServerConfig.kt`, `ServerRegistry.kt`; `lib/server/implementation/.../ServerRegistryImpl.kt` | Reuse per-profile server identity, rename/remove, and persistence. |
| URL storage | `ServerRegistryImpl.addServerWithId()` currently calls `trimEnd('/')` | OPDS feed URLs are resource URLs. Removing a trailing slash can change relative-link resolution. |
| URL entry/edit | `feature/login/ui/.../login/ServerAddress.kt`; `feature/settings/ui/.../servers/ServerAddress.kt` | Both normalize around server-base-URL assumptions. OPDS must preserve paths, query strings, and meaningful trailing slashes. |
| Authentication | `ServerCredentials.kt` requires `accessToken`; `ServerRegistryImpl` usually treats credential presence as authentication | Do not encode anonymous access or a Basic password as a bearer token. |
| Auth extensions | `ServerAuthStateProvider.kt`; `ServerRegistryImpl.saveCredentials()` rejects credentials for types with these providers | An OPDS auth provider cannot simply be added while continuing to store credentials through the current method. |
| Factory routing | `CompositeRepositoryFactory.kt` and composite books/reader/series factories | Extend the factory pattern with a catalogue repository; do not register dummy OPDS library repositories. |
| Library aggregation | `AuthenticatedRepositoryProviderImpl.kt`, `feature/books/domain/.../usecase/GetBooksUseCase.kt` | Books factories are called for every authenticated source; catalogue-only servers must be filtered before factory creation. |
| Reader routing | `AuthenticatedRepositoryProviderImpl.getReaderRepository()` | Also assumes all authenticated types have a reader repository; add a capability guard. |
| Book domain | `feature/books/domain/.../model/BookDomainModel.kt` | Existing types are personal-server books and local library books, not remote catalogue navigation items. Keep discovery models separate. |
| Network client | `lib/network/implementation/.../KtorNetworkClient.kt` | Resolves endpoint paths against a configured base URL; not the right contract for arbitrary feed/CDN links. |
| Server HTTP auth | `ServerHttpClientFactory.kt` | Bearer-specific, with eager token sending. OPDS needs origin-scoped optional auth for feeds, covers, and downloads. |
| Reader downloads | `feature/reader/data/.../BookDownloadManagerImpl.kt` and platform `source/EbookFileDownloader.*.kt` | Designed around an existing server book/cache. Cache/state identities omit profile/server in some places; do not attach catalogue IDs to this unchanged. |
| Cloud transfers | `feature/books/data/.../transfer/BookFileTransferEngine.kt`, `DownloadFinalizer.kt` | Durable machinery exists, but downloads require a cloud file record, known size/hash, and existing library identity. OPDS does not guarantee any of these. |
| Local import | `feature/books/data/.../source/LibraryLocalDataSource.kt`, platform `AndroidFileImportManager.kt` / `IosFileImportManager.kt` | Already extracts metadata, calculates hashes, deduplicates bytes, creates random UUIDs, moves files, and writes metadata outbox entries. Reuse finalization logic. |
| Device file identity | `lib/database/api/.../library/DeviceFileEntity.kt` | One file per `(libraryBookId, mediaType)`; different editions must not overwrite each other under the same key. |
| Local presentation | `lib/server-local/.../LocalBooksRepository.kt`, `LocalReaderRepository.kt` | Acquired books can reuse offline reading, progress, statistics, saved items, and library presentation. |
| Profile fencing | `lib/database/api/.../ProfileDatabaseSession.kt`, implementation `DatabaseManager.withProfile()` | Capture the initiating profile; fence mutations and cancel jobs on profile changes. Do not hold the database lock for a network download. |
| Secure preferences | Android `EncryptedPreferenceFactory`; iOS `IosSettingsFactory` uses Keychain | Reuse secure platform storage for a separately typed OPDS credential store. |
| UI/navigation | `feature/settings/ui/.../servers/*`; `feature/home/ui/.../navigation/HomeDestination.kt`, `HomeNavigation.kt` | Reuse server management; add catalogue routes and browsing actions. |
| Dependency injection | module `@Configuration`/`@ComponentScan`; `composeApp/.../di/ParrotKoinApp.kt` | Follow existing Koin compiler-plugin registration and test composition on both targets. |

Important naming trap: `GetBooksUseCase.observeCatalogue()` currently means the
**personal-library snapshot used for linking**, not remote OPDS discovery. Keep
that behavior unchanged and use distinct catalogue-browser contracts.

### 2.2 Specification findings

- **OPDS 1.2** distinguishes navigation feeds, acquisition feeds, and standalone
  full entries. Partial entries can link to full entries. Search is typically
  advertised via an OpenSearch description document.
- **OPDS 2.0 is a living standard**, not just a draft to be deferred. It supports
  `navigation`, `publications`, `groups`, `facets`, multiple link relations,
  flexible contributor metadata, and URI-template search.
- Media types include `application/opds+json`,
  `application/opds-publication+json`, and Atom OPDS media types with parameters.
  Do not compare MIME types as exact unparsed strings.
- Pagination follows advertised `next`, `previous`/`prev`, `first`, and `last`
  links. Do not invent page numbers or endpoint names.
- OPDS 2 aliases (`download`, `acquisition`, `buy`, `borrow`, `preview`,
  `subscribe`) and the historical OPDS relation URIs are both valid.
- Open access describes acquisition conditions, **not worldwide copyright
  status**. OPDS is also used for copyrighted personal libraries and stores.
- Indirect acquisition can describe an HTML transaction, container, or licence
  resource before the final EPUB. Finding an EPUB in that tree does not make the
  outer link a direct EPUB download.
- Authentication discovery has its own document type,
  `application/opds-authentication+json`, and can be advertised in a 401 body or
  HTTP `Link` header. The reviewed Authentication for OPDS 1.0 document is a
  draft and includes legacy OAuth flows; do not implement them unquestioningly.
- URI templates must be expanded **before** relative-URL resolution. OpenSearch
  optional parameters such as `{startPage?}` are not the same syntax as RFC 6570
  query expressions such as `{?query,title}`.

### 2.3 Live provider checks

These observations are from read-only requests, not an end-to-end app test.

**Project Gutenberg**

- Documented machine-to-machine entry point:
  `https://www.gutenberg.org/ebooks/search.opds/`.
- GET returned Atom XML, 25 entries, relative `subsection` links, an OpenSearch
  link, `start`, `next`, and inline `data:image/png;base64,...` thumbnails.
- Those list entries were **navigation to publication feeds**, not directly
  downloadable publications. Do not infer acquisition solely from title/cover.
- GET `https://www.gutenberg.org/ebooks/1342.opds` returned an acquisition feed
  with two edition/variant entries (`urn:gutenberg:1342:2` and `:3`), XHTML
  descriptions, rights, authors, language, related links, and multiple formats.
  One variant exposes more than one EPUB link. Preserve both variant identity
  and format selection; do not deduplicate by title.
- `https://www.gutenberg.org/catalog/osd-books.xml` advertises a legacy
  **HTTP** search template on `m.gutenberg.org` with `{searchTerms}` and plain
  `application/atom+xml`. A HEAD request to the HTTPS equivalent succeeded and
  redirected to the corresponding `www.gutenberg.org` HTTPS resource.
- HEAD to the advertised EPUB link
  `https://www.gutenberg.org/ebooks/1342.epub.noimages` succeeded, redirecting to
  a `cache/epub/...` file with `application/epub+zip`.
- Gutenberg documents an OPDS2 feed available for testing by contacting them,
  and expects to sunset XML feeds in 2027. Its OPDS2 endpoint/access details were
  **not obtained or tested** during this research.
- The documented OPDS interface is explicitly for apps. Their broader anti-bot
  policy is not permission to crawl HTML pages or bulk-download the collection.
  Confirm preset launch usage/download guidance and the upcoming endpoint with
  Gutenberg; use user-initiated acquisition, not background mirroring.

**Standard Ebooks**

- Documents `https://standardebooks.org/feeds/opds`; requesting
  `Accept: application/opds+json` selects OPDS2.
- Full feed access is a patron/contributor/sponsor benefit. Their instructions
  say to use the patron's email address with an **empty password**.
- The catalogue returned 401 without credentials. Earlier research also got
  401 for the OPDS new-releases feed. Only RSS/Atom new releases are explicitly
  documented as public. Do not advertise this as an anonymous full catalogue.
- No authenticated request or OPDS2 response was tested without an account.

**Calibre / custom catalogues**

- Calibre is a useful self-hosted interoperability target, including URL-prefix
  reverse proxies and multiple libraries.
- Its documentation distinguishes Basic and Digest authentication. First-release
  Basic support does not imply all default Calibre auth configurations work.
  Test an anonymous server and a TLS/Basic configuration; report unsupported
  Digest challenges clearly unless Digest is deliberately implemented.
- Do not guess `/opds` for arbitrary user-entered websites. Ask for the feed URL;
  bounded standards-based autodiscovery can be added independently later.

LibriVox's JSON API is not automatically an OPDS catalogue. A future LibriVox
adapter could implement the generic catalogue capability without changing OPDS
parsing, but it is outside this implementation.

### 2.4 Parser/library choice

Readium Kotlin has an OPDS module supporting both versions, full entries, facets,
groups, and indirect acquisition. Its reviewed README marks search unfinished;
its Gradle configuration is Android-specific. It is not a drop-in `commonMain`
dependency for this app's Android/iOS shared feature.

**Recommendation:** shared protocol parsing using the project's existing
`kotlinx.serialization` JSON and `xmlutil` dependencies. Use Readium's model,
fixtures, and implementation as references, not a second platform-specific
parser whose behavior can diverge from iOS. Verify licences before copying code
or fixture content. Do not write XML parsing with regex/string slicing.

## 3. Target architecture

```text
Shared server management / profile-scoped ServerRegistry
  ├─ personal-library capability → existing books/reader repositories
  └─ catalogue capability → ServerCatalogueRepositoryFactory
                              └─ OpdsCatalogueRepository
                                  ├─ shared OPDS parser
                                  ├─ link/search resolver
                                  ├─ origin-scoped HTTP/auth transport
                                  └─ bounded feed cache

Catalogue UI → acquisition request → durable download → validated staged EPUB
             → existing library finalization + provenance
             → LocalBooksRepository / normal reader
```

### 3.1 Module boundaries (proposed)

| Module | Responsibility |
| --- | --- |
| `lib/server/api` | Generic catalogue contracts/models and capability/access contracts. No XML/JSON wire DTOs. |
| `lib/server/implementation` | Catalogue factory routing, capability-safe repository selection, shared registry/access lifecycle. |
| `lib/opds/api` | Protocol parser/transport interfaces and version-specific protocol model where necessary; no UI, database, or server registry dependencies. |
| `lib/opds/implementation` | OPDS 1/2 parsing, OpenSearch, URI templates/resolution, HTTP fetching, bounded parsing. Depends on its API, not feature modules. |
| `lib/server-opds` | Maps protocol models to generic server catalogue models; per-server auth/access and cache policy. Depends on server API and OPDS modules. |
| `feature/catalogue/domain` | Browse/search/detail/acquisition use cases and acquisition-manager interface. |
| `feature/catalogue/data` | Bounded catalogue cache, durable acquisition orchestration, provider-to-device downloads; delegates EPUB finalization to books-domain contract. |
| `feature/catalogue/ui` | Catalogue source list/browser, search, publication details, format actions, transfer state. |
| `feature/books/domain` + `data` | Generic staged-file import/finalization contract and existing library implementation. No dependency on catalogue UI or OPDS implementation. |
| `lib/database/api` + `implementation` | Acquisition/provenance/cache entities, DAOs, migrations, merge/deletion integration. |

The dependency direction is deliberate: protocol parsing does not need the app's
server model, and the server adapter does not need books-data or catalogue UI.
Follow existing KMP target, Koin annotation, and domain/data/UI build patterns.

### 3.2 Generic catalogue contracts

Add a `ServerCatalogueRepository` (with `serverId`) and server-specific/composite
factories following `ServerSpecificFactory` and `CompositeRepositoryFactory`.
Suggested operations, using the existing `AppResult` convention:

```kotlin
suspend fun getRoot(): AppResult<CatalogueDocument>
suspend fun getDocument(target: CatalogueTarget): AppResult<CatalogueDocument>
suspend fun discoverSearch(document: CatalogueDocument): AppResult<CatalogueSearch?>
suspend fun search(search: CatalogueSearch, query: CatalogueQuery): AppResult<CatalogueDocument>
```

`CatalogueDocument` distinguishes a feed from a standalone publication. A feed
retains metadata, ordered navigation/publication sections, groups, facets,
pagination, search, root/up links, response URL, and fetch/cache status.

`CataloguePublication` retains publication identity, contributors, languages,
description format, rights, publisher/date/identifiers, images, detail links,
related links, and **all** acquisition choices. A Readium manifest-only
publication remains representable but is not downloadable as an EPUB by default.

`CatalogueLink` retains original href/template, effective base URI, normalized
relations, parsed media type/parameters, label, optional size/dimensions, price,
indirect-acquisition tree, and relevant extension properties. Templates must not
be prematurely normalized as concrete URLs.

Targets and identities are opaque to UI/use cases. Never derive identity from
title, array position, ISBN alone, or a download URL. For OPDS1 use `atom:id`;
for OPDS2 prefer a publication `self` resource identity, with a documented
provider-scoped identifier fallback. Missing stable identity needs a deterministic
document-scoped fallback and warning; it cannot promise cross-feed deduplication.

### 3.3 Static capabilities versus discovered capabilities

Extend `ServerCapabilities` with explicit concepts such as:

- `supportsCatalogueBrowsing` (OPDS true).
- `contributesToLibrary` (Storyteller, Audiobookshelf, Local, Parrot Cloud true;
  OPDS false).
- `supportsReaderRepository` (existing readable library types true; OPDS false).

The existing `supportsUserLibrary` flag is not suitable as the filter: Local has
it set to false but must remain in the personal library.

Keep OPDS remote mutation/sync capabilities false. A feed's author navigation is
not the app's series repository; a facet is not a mutable collection. EPUB
acquisition is supported by the client, but search/format/auth availability is
discovered per source/document, not guaranteed by the enum.

Audit `AuthenticatedRepositoryProviderImpl` before exposing Opds in the picker:

- Filter both books repository-list methods by `contributesToLibrary`.
- Guard single-books and reader repository lookups before factory creation.
- Retain series capability filtering and test it with catalogue-only sources.
- Add a separate catalogue provider using registered/enabled sources and access
  state, not `observeAuthenticatedServers()` alone.
- Do not register an empty books/series repository or no-op progress adapter to
  make factory assumptions disappear.

### 3.4 Access and credentials

Keep auth state separate from connectivity. Do not show an anonymous catalogue as
“Signed out”, or a server with stored credentials as “online” without a request.

Add a shared access contract for catalogue-capable sources, for example
`ServerAccessState`/`ServerAccessStateProvider`, representing anonymous access,
credential-backed access, authentication required/failed, unsupported auth, and
disabled access. Network availability/last validation is a separate status.
Retain existing personal-server `ServerAuthState` behavior during this targeted
extension; avoid rewriting all authentication in the same change.

Use a typed secure `OpdsCredentialStore` scoped by profile and server instance.
Basic username/password must not live in `ServerCredentials.accessToken` or flow
through `ServerTokenProvider`. Accept empty Basic passwords (Standard Ebooks).
Parse both ordinary `WWW-Authenticate` challenges and OPDS auth documents.

Add persisted `ServerConfig.enabled` with a backward-compatible default of true
for explicit catalogue deactivation; existing personal servers remain gated by
their current sessions. Clearing credentials cannot disable anonymous access.
Define sign-out as removing optional credentials; define disconnect/disable as
stopping all access/jobs. Re-enable is an explicit action.

Changing a catalogue URL must revalidate it and invalidate old caches/search
descriptors. An origin/protection-space change clears or explicitly re-confirms
credentials; never silently retarget passwords to a new host. Publication
provenance already acquired is not rewritten to the new endpoint.

## 4. Protocol and network implementation

### Parsing

- OPDS1: namespace-aware Atom feeds/entries, inherited `xml:base`/`xml:lang`,
  authors/contributors, Dublin Core terms, categories, text/HTML/XHTML content,
  artwork, links, facets, partial/full entries, acquisition trees, and grouping
  links. Preserve relative-link semantics at the element that declared the link.
- OPDS2: feed/publication documents, groups/facets/navigation, responsive images,
  relation string/array forms, localized strings, contributor string/object/array
  forms, languages, publication metadata, price, and nested indirect acquisition.
- Detect using parsed content type and bounded structural validation. Permit
  known generic JSON/XML types when structure proves OPDS; do not accept arbitrary
  RSS, HTML error pages, or arbitrary JSON as catalogues.
- Treat `kind` as a hint, not truth. Classify entry actions from relations and
  document structure; tolerate mixed/non-ideal real feeds without guessing EPUB
  links from file extensions.
- Unknown extension fields are ignored or retained within bounded containers.
  Recoverable item errors produce warnings; malformed roots fail visibly.
- Enforce feed bytes, nesting, items, attributes, text, and inline image limits.
  Disable DTD/external entity resolution and entity expansion. Test actual
  Android/iOS parser behavior, not merely a pre-scan for `DOCTYPE`.

### URLs, search, and navigation

- Resolve RFC 3986 references against the **effective response URL after
  redirects**, plus any inherited XML base. Handle root/path/query-only links,
  protocol-relative references, dot segments, Unicode, and encoded delimiters.
- Preserve full root resource URLs, including meaningful trailing slash and
  query. Reject embedded userinfo; do not log private query strings.
- Maintain separate identity and fetch URLs: a feed's `self` is useful identity
  metadata, but must not silently change the base for all of its relative links.
- OPDS1 OpenSearch: lazily fetch/cache description documents; prefer OPDS/XML
  response templates over HTML/suggestions; support searchTerms, optional/default
  pagination/encoding parameters, and explicit unsupported-required-parameter
  errors. Support observed plain Atom media type as a validated compatibility
  case, not only Atom types with `profile`.
- OPDS2: RFC 6570 expansion with `query` and advertised optional advanced fields.
  Choose a tested KMP template implementation or implement the declared supported
  level with RFC fixtures. Unsupported expressions must fail explicitly.
- Never hardcode `?search=`, `?query=`, `/search`, or page increments in the UI.
- The Gutenberg legacy HTTP OpenSearch endpoint needs a release-blocking
  compatibility spike: attempt the advertised host's HTTPS equivalent with
  validation and no credentials, or obtain the updated descriptor/OPDS2 endpoint.
  Do not rewrite arbitrary hostnames or silently downgrade TLS as a generic fix.
- Follow root/up/related/facet links only on user action. Do not recursively
  crawl navigation or fetch every complete entry for a visible list.

### HTTP security and lifecycle

Create an isolated OPDS HTTP transport using existing Ktor engine infrastructure,
not the bearer-specific server client unchanged. Required behavior:

- Content negotiation for both feed versions and standalone publication types.
- Explicit bounded redirect handling or equivalently tested engine policy.
  Apply URL/auth checks to every redirect and every separately followed link.
- Send Basic credentials only to the configured HTTPS origin/protection space.
  CDN/download/cover requests may be cross-origin but receive no credentials by
  default. Matching host without scheme/port is insufficient.
- Never share cookies/auth caches across profiles or unrelated servers; clear
  sensitive cached resources on sign-out/removal/profile switch.
- Covers use the same auth/redirect policy as feeds. Do not hand authenticated
  image requests to a global unrestricted bearer client.
- HTTPS by default; allow deliberately added anonymous LAN HTTP catalogues with
  clear warning and platform policy checks. Do not silently send Basic passwords
  over HTTP. Digest/OAuth are explicit follow-ups, not a fallback credential leak.
- No disabled certificate validation. Surface untrusted/self-signed TLS clearly.
- Permit legitimate custom LAN hosts; don't impose a public-provider host
  allowlist. Guard unsolicited cross-origin feed links to local/loopback services
  with an explicit trust policy/confirmation, including redirect hops.
- Unsupported schemes cannot trigger file reads or fetches. Allow bounded raster
  `data:` images only in the image path; reject `file:`, `javascript:`, and unsafe
  schemes for catalogue/download targets. External HTML opens only after a user
  action, without app credentials being embedded in the URL.
- Cache using HTTP semantics/validators (`ETag`, `Last-Modified`, `Vary`,
  `Cache-Control`). Respect `no-store`; auth-sensitive keys include profile,
  server/access generation, request URL, and representation.
- Bound memory/disk caches, concurrent requests, prefetch, connection lifetime,
  and retries. Honor `Retry-After` on 429/503; no perpetual background retry loop.
- Cancellation propagates unchanged. Error/log categories are bounded and never
  include passwords, auth documents' private data, search text, full URLs, signed
  query strings, or publication titles.

Initial tunable budgets: 5 MiB decoded feed/description response, nesting depth
64, 2,000 items per response, 5 redirects, 20 parsed pages / 25 MiB disk metadata
cache per profile, 2 concurrent acquisitions, and a 512 MiB per-EPUB download
ceiling. Validate these against image-heavy fixtures/device measurements and
offer a clear size-limit error; do not market them as OPDS specification limits.

## 5. Acquisition, persistence, and library identity

### 5.1 Acquisition policy

Use an extensible `CatalogueAcquisitionHandler` selected by relation, outer
content type, indirect tree, and client capabilities.

First handler: direct complete EPUB for generic acquisition/open-access/download
relations, without a required unsupported transaction/licence step. Authenticated
generic acquisition can use Basic access. Do not label every generic relation
“public domain” or “free”.

Show format, provider label, optional size, access requirements, and rights in the
detail sheet. Preserve multiple EPUB choices; selection must not depend on
Gutenberg filename patterns. Preview/sample is not imported as the complete book.
Buy/borrow/subscribe and indirect resources receive a clear unsupported action or
user-initiated provider-page option, not a misleading Download button.

### 5.2 Durable catalogue acquisition—not cloud restore

Add a profile-scoped `CatalogueAcquisitionManager` backed by a database queue.
Do not create fake `CloudBookFileEntity` records or reuse the cloud engine's
required remote hashes/revisions as placeholders.

Reuse/extract platform file-store operations and the general streaming approach
already present. Keep cloud and catalogue orchestration separate; avoid an
unrelated rewrite of every reader/cloud download mechanism.

Lifecycle:

1. Capture profile, server/access generation, stable publication identity, and
   selected acquisition; persist a request id and pending snapshot.
2. Deduplicate active requests per source/publication/selected representation.
3. Resolve/revalidate the acquisition link immediately before downloading;
   expiring links are not permanent identity.
4. Stream to a profile-scoped generated staging filename ending `.epub.part`
   (rename to `.epub` for validation if the metadata reader requires it). Do not
   use remote path components, title, or Content-Disposition as filesystem paths.
5. Enforce actual byte budget, disk space, completion, and optional declared
   length/checksum. Unknown Content-Length is valid: report bytes and indeterminate
   progress rather than refusing the download.
6. Validate as EPUB using the existing metadata extractor/reader opening path;
   do not trust MIME type, extension, or ZIP magic alone. Apply archive entry,
   expansion-size, traversal, and encryption/DRM rejection limits.
7. Hash the downloaded bytes locally and finalize into the library.
8. Persist the returned library UUID, provenance, and completed transfer state;
   only then expose Open in reader. Reconcile filesystem/database crash windows
   with an idempotent finalization journal.

Persisted states: pending, downloading, validating, importing, completed,
failed/retryable, authentication-required, and cancelled. App restart recovers
non-terminal requests safely. Initial recovery may restart bytes from zero;
range resume is enabled only with proper validators and verified 206/
Content-Range behavior, never naive append.

Downloads can outlive a screen, but must not continue into another profile.
Capture/fence each database commit with `ProfileDatabaseSession.withProfile()`;
do not wrap the entire network operation in its mutex. Server disable/removal,
profile deletion/switch, and credential changes cancel or invalidate matching
jobs. No correctness claim depends on OS background execution; Android foreground
services/iOS background URLSession are a separate execution enhancement.

### 5.3 Reusable import finalization

Introduce a staged-EPUB finalization API in `feature/books/domain` (proposed
`StagedBookImportManager` or equivalent) and implement it in books-data.

Both the file picker and catalogue downloads delegate to this API. Avoid sending
the staged OPDS file through Android's current `platformFile.readBytes()` copy
path, which would re-buffer the whole downloaded book.

Generalize `ImportedFileCandidate` to carry device origin and optional source
provenance. Preserve existing random-UUID, metadata extraction, byte-hash
deduplication, media-overlay detection, cover extraction, and outbox semantics.
Serialize finalization/deduplication so concurrent identical acquisitions cannot
create duplicate library books. Make finalization retry-safe; an interrupted
import must not leave a library row referring to partial/missing bytes.

Different bytes with the same title/ISBN are different edition candidates, not
an automatic overwrite. Reacquiring a changed provider representation must not
replace a file whose progress/annotations refer to the old edition without an
explicit update workflow. Exact-byte matches can attach provenance to an
existing library book without resetting progress.

### 5.4 Proposed local database records

Use explicit SQLDelight records instead of a growing preferences JSON catalogue:

- `catalogue_acquisitions`: request id, initiating server id, publication key,
  selected representation key, bounded metadata/link snapshot, state, staging
  path, optional expected size, bytes, local hash, optional resulting library
  UUID, timestamps, attempts/retry deadline, and bounded error category.
- `catalogue_book_sources`: random source-record id, library UUID, original
  server id, provider/root identity snapshot, publication key, original detail
  identity, selected format, rights/attribution snapshot, content hash, and
  acquired timestamp. Support more than one source per library book.
- `catalogue_cache` or an equivalent bounded disk HTTP cache: validators,
  representation/access key, response URL, timestamp/expiry, and bounded payload.
  Cache storage must honor sensitive/no-store policy.

Signed acquisition URLs/auth headers/passwords are not durable provenance and
must not appear in portable/cloud metadata. Persist only what is needed to retry
or rediscover links, with an explicit sensitivity policy for private query URLs.

Add `DeviceFileEntity.ORIGIN_CATALOGUE_DOWNLOAD` and audit origin-sensitive code:
`backupAll()` currently only selects `ORIGIN_IMPORT`. Recommended behavior is
**no silent cloud file upload caused by catalogue acquisition**. Explicit backup
continues through the existing rights-attestation flow; OPDS open access is not
proof of redistribution rights. Metadata/progress may sync under normal account
settings; file backup is a separate user choice.

Acquired books open as `(LOCAL_SERVER_ID, libraryBookId)` and keep the existing
`library:<uuid>` copy key. Do not create a second OPDS reader identity for the
same local bytes or insert browsed entries into the linked-copy catalogue.

Removing an OPDS server clears its credentials/cache and pending jobs, **not
books acquired from it**. Retain detached provenance snapshots without requiring
the preferences server row to exist. Re-adding the same endpoint does not
automatically transfer credentials or trust. Library merges move provenance and
completed mappings to the surviving UUID; library/profile deletion cleans the
relevant records and files. Download deletion does not call a remote delete API.

## 6. User experience and presets

This section states behavior. The screens, states, and copy that design must
produce before Phase 4 are listed in §11.

- Extend Add server with an OPDS choice and catalogue presets. Selected OPDS
  uses feed URL + optional display name; credentials appear only when selected
  or challenged. The primary action is Connect/Add, not compulsory Sign in.
- Probe/validate one bounded root response before registration. Handle 401 as
  an auth challenge, not “not supported”; distinguish bad URL, HTML page,
  unsupported auth, unreachable host, TLS failure, and malformed feed.
- Update login/probe contracts rather than forcing a successful anonymous probe
  to invent `ServerCredentials`. `LoginDataRepository` currently probes only
  Storyteller/Audiobookshelf; OPDS detection must parse a feed/challenge, not
  try their API paths against every navigation link.
- Server management shows the OPDS type, full editable feed URL, anonymous or
  account access, last validation/error, Browse, edit credentials, disable,
  rename, and remove. An anonymous source has no inappropriate Sign out action.
- Add catalogue entry points to server management and the library's add/browse
  experience. A separate catalogue destination can list registered OPDS sources;
  no new bottom-navigation tab is required for the initial feature.
- Add proposed `HomeDestination.CatalogueSources`,
  `CatalogueBrowse(serverId, target)`, and `CataloguePublication(...)` routes.
  Persist only bounded serializable route references; never credentials/full
  feeds in Nav3 state. Sensitive fetch details remain in the repository.
- Browser preserves provider-defined groups/navigation/facets, advertised search,
  pagination, scroll, and back behavior. Bound loaded pages; stale results cannot
  overwrite a new search after cancellation or source/profile switch.
- Separate initial load, page load, detail load, download, empty, auth-required,
  offline-cache, and failure states. A failed next page is not an empty catalogue.
- Details show provider, authors/languages, sanitized description, rights,
  variants/formats, download progress/cancel/retry, and Open after import.
- Reuse Ember components, accessibility semantics, e-ink behavior, and strings
  from `translations/src/commonMain/composeResources/values/strings.xml`.

Preset metadata is a small local data list (name, URL, description, access note,
terms link). It does not auto-register or auto-fetch a catalogue at startup.
Gutenberg is the first candidate after launch guidance/endpoint confirmation.
Standard Ebooks is labelled patron access, not a free anonymous catalogue.
Do not bundle shared sponsor credentials or bypass feed restrictions.

## 7. Implementation sequence and acceptance gates

Each phase has a **Test-first order**: tests to write and see fail before the
production code, in that order. It covers logic with a written spec or a known
failing scenario. Spikes, UI layout, DI wiring, and migrations are tested after
the fact. Fixtures only prove that saved input parses; live-provider device QA
(§8) stays mandatory.

### Phase 0 — focused compatibility/security spikes

- Save small licensed/synthetic OPDS1/OPDS2/OpenSearch fixtures in test resources,
  including Gutenberg navigation → variants → acquisition behavior.
- Validate xmlutil namespace/mixed-content/DTD behavior on Android and iOS.
- Verify URI-template implementation choice and URL resolution against RFC tests.
- Confirm Gutenberg usage guidance, HTTPS search descriptor behavior, and OPDS2
  endpoint/testing access. Do not block the generic custom-server implementation
  on this provider; withhold the preset if necessary.
- Define cache/download budgets and Basic/HTTP policy with device QA.
- Confirm iOS cleartext behavior against a LAN HTTP catalogue (§10.4) and Coil
  handling of inline `data:` thumbnails (§10.7).
- Decisions are recorded in §10.0; the spikes below are verification only.
- Decide the generic "one book with editions versus a list" rule against the
  Gutenberg two-step feeds and a Calibre feed (§11.7, Still open 1).
- Design is delivered (§11.7); record the result of each of its "Still open"
  verifications.

**Test-first order:** none. Spikes are exploratory; their output is the fixture
set and the recorded parser/network behaviors that Phase 1 tests assert against.

**Gate:** parser and transport choices are demonstrated, unverified provider
behaviors are explicit rather than assumed, and each §11.7 verification has a
recorded result.

### Phase 1 — protocol core (not user-visible yet)

- Create `lib/opds/api` and `implementation`, shared models/parser, safe resolver,
  OpenSearch/template support, acquisition classifier, and bounded transport.
- Implement fixture/MockEngine tests for both versions and security policies.
- Verify Android host tests and iOS compilation/test execution.

**Test-first order:**

1. URL resolution against RFC 3986 vectors, then effective-response-URL versus
   `self`, trailing slash, and query preservation.
2. Media-type parsing and document detection (parameters, case, HTML/error
   bodies rejected).
3. OPDS1 parser: fixture in, expected normalized model out, starting with the
   Gutenberg navigation and publication feeds, then `xml:base`, XHTML content,
   and missing optional fields.
4. OPDS2 parser against the same normalized model, so both versions are held to
   one expected shape.
5. Acquisition classifier as a decision table: relation + type + indirect tree →
   download, unsupported, or open provider page.
6. OpenSearch and RFC 6570 expansion from the RFC vectors, including explicit
   failure for unsupported expressions.
7. Transport rules with MockEngine: credentials only on same-origin HTTPS hops,
   never reattached after leaving the configured origin; same host with different
   scheme/port, redirect limit, size and
   nesting limits, DTD/entity rejection beyond what the library does (reject
   any document containing a DOCTYPE at the DOCDECL event; xmlutil expands
   internal entities by default).

**Gate:** equivalent protocol behavior on both platforms; no provider-specific
logic required for synthetic/custom catalogues.

**Gate verification (2026-10-08, `opds/phase1-protocol-core`): met.** Test-first
steps 1–7 are complete. The shared fixture/MockEngine suite passes 159/159 on
Android host and 159/159 on iOS simulator, with no failed or skipped tests;
`:lib:opds:api:assemble` and `:lib:opds:implementation:assemble` both pass after
retiring the Phase 0 harness. No provider-name strings or provider-specific
branches occur in production `lib/opds` sources; remaining name hits are in
fixture registries and fixture-driven tests. Private-network advisories classify
literal IP addresses and local hostnames, not DNS resolution. Persisted caching,
live-provider/device QA and application integration remain later-phase work;
sample downloading remains an open product decision and is not implemented.

### Phase 2 — first-class server and capability routing

- Add Opds enum/capabilities, catalogue/access interfaces, composite factory,
  enabled-state persistence, typed credential storage, and `lib/server-opds`.
- Preserve existing config defaults/serialized identifiers. Audit every enum
  branch, serializer, factory, onboarding picker/probe, auth/status handler,
  managed-server rule, and analytics label.
- Move `StorytellerAuthenticatorFactory`'s generic map dispatch into a neutral
  composite authenticator factory, or explicitly avoid calling it for anonymous
  catalogue connect. No Storyteller dependency from the OPDS module.
- Capability-guard books/reader/series selection; introduce catalogue provider.
- Add add/edit/connect/reauthentication persistence rollback and profile fencing.
- Implement the downgrade-safe persistence chosen in §10.1, the auth/access state
  mapping for existing consumers (§10.2), and logout-all semantics (§10.3).
- Add the OPDS branch to the exhaustive `when` blocks in
  `feature/books/ui/.../components/BookComponents.kt` and
  `StorytellerAuthenticatorFactory`; both fail to compile otherwise.

**Test-first order:**

1. Downgrade safety (§10.1): a stored server list containing an OPDS entry,
   read by a decoder that does not know the type, keeps existing servers and
   does not overwrite them. This fails against today's code.
2. Root URL round trip through add, edit, and reload without slash or query
   changes.
3. Capability guards: with an OPDS source registered, no books, reader, or
   series factory is invoked for it, and library aggregation is unchanged.
4. Access state: anonymous source is usable without `ServerCredentials` and maps
   to a non-"signed out" state for the server-management view model (§10.2).
5. Credential store: scoped by profile and server, empty password accepted,
   origin change clears or re-confirms credentials.
6. Logout-all (§10.3): OPDS credentials removed, active acquisitions cancelled,
   anonymous sources and acquired books kept.
7. Disable versus sign-out versus remove, each with its own expected effect.

**Gate:** OPDS registers without credentials; all existing library/reader/sync
flows work; no factory is invoked for an unsupported repository type; an
anonymous source never renders as "Signed out"; a build without OPDS support
reading the new preferences keeps its existing servers.

**Partial implementation (2026-10-08, `opds/phase2-server-type`):** test-first
items 1–3 only. Catalogue configs use the user-scoped `CatalogueSources` key;
`RegisteredServers` continues to contain only the existing library types.
Catalogue add/update/remove use persistence rollback, `enabled` defaults to
true, and OPDS resource addresses are stored verbatim. Books and reader routing
is capability-guarded; the existing series filter remains. Bearer-network lookup
by server ID returns null for OPDS. Global Coil auth excludes OPDS and suppresses
bearer tokens on any origin shared with a catalogue, since it has no source
identity. Existing-only profiles retain their prior routing behavior.

At the end of that items 1–3 run, no catalogue picker, screen, repository, access
provider, credential store, sign-out or enable/disable lifecycle had been added.
The Phase 2 gate was not yet met. Historical all-server consumer audit:

- `CoilInitializer`: fixed as above; shared-origin authenticated library covers
  will need source-aware loading once OPDS can be added through UI.
- `AuthenticatedRepositoryProviderImpl`: filters both books lists, guards single
  books/reader lookups before factories, and retains series capability filtering.
- `LogoutUseCase`: attempts to clear bearer credentials for OPDS and then clears
  all database data; catalogue logout/acquisition preservation remains item 6.
- `SettingsDataRepository`: attempts to clear bearer credentials for every
  non-Local source; no OPDS-specific account lifecycle yet.
- `CheckAuthStateUseCase`: a configured OPDS source counts as a remote setup and
  bypasses welcome, even without authentication; unchanged pending access work.
- `ObserveHasAuthenticatedRemoteServersUseCase`: anonymous OPDS is absent from
  the authenticated list; an artificially bearer-authenticated OPDS would count.
- Storyteller/Audiobookshelf progress sync adapters: exact type filters exclude
  OPDS before client creation; no changes needed.
- `AuthorsRemoteDataSource` / `SeriesRemoteDataSource`: anonymous OPDS never
  enters their authenticated list. If it does enter, guarded network lookup now
  returns null instead of throwing or dispatching to a bearer client. Existing
  endpoints and existing-type behavior were not changed.
- `PositionsDataSource`: includes the source name in metadata; repository lookup
  is guarded, so no OPDS book factory is called.
- `BookDetailViewModel`: uses all servers only to label already-linked copies;
  the current implementation does not observe all auth states (unlike §10.2's
  earlier review). No OPDS copy or reader route is created.
- `ServerManagementViewModel`: would list a programmatically registered OPDS
  source as signed out and offer the existing sign-in/edit actions. Mapping and
  address editing are deferred; no OPDS source can be added from the current UI.
- `AppSettingsViewModel`: includes catalogue display names in its server-name
  list; no networking or factory dispatch.
- `ObserveSeriesBrowseUseCase`: includes its name in the lookup map, but only
  Storyteller/Audiobookshelf IDs count as series sources.
- `LocalServerInitializer` / `ParrotCloudServerRegistrar`: observe all servers
  only to ensure their respective fixed IDs exist; unrelated OPDS IDs are ignored.

Before exposing registration, also add `CatalogueSources` to
`UserRegistryImpl.clearUserPreferences()`'s explicit profile-deletion key list;
currently it leaves this isolated preference behind. Profile lifecycle cleanup
was not expanded in this items 1–3 run.

**Items 4–7 and catalogue plumbing (2026-10-08, `opds/phase2-server-type`):**

- Generic catalogue contracts retain the protocol model's metadata, topology,
  language-tagged text, identity scope/warnings, artwork, media parameters,
  extension properties, price, indirect acquisition and every acquisition choice.
  Targets/search contexts are opaque, session-owned references. No XML/JSON or
  OPDS implementation types occur in the generic API.
- The composite factory follows `ServerSpecificFactory` /
  `CompositeRepositoryFactory`. The catalogue provider selects registered,
  enabled sources, not authenticated servers. Its observed source/status snapshot
  is explicitly profile-scoped; synchronous registry getters/mutations also reload
  the profile before accessing catalogue state.
- `lib/server-opds` uses the Phase 1 parser, classifier, transport, search and
  bounded in-memory cache, with no Storyteller dependency or authenticator call.
  One shared cache bounds total retained payloads across sources; keys include
  profile/source/access generation. Clearing a source conservatively clears the
  shared payload cache too; other sources may subsequently refetch. Search
  descriptors and targets are invalidated with their source session. Persisted
  opened-document caching remains later-phase work, as requested for this run.
- Link declaring bases now survive protocol normalization, including a link's own
  `xml:base`; concrete resolution uses that base as well. This was needed to avoid
  losing inherited bases in the generic adapter and search expansion.
- `CatalogueAccessStatus` persists verified Public/SignedIn/SignInNeeded/
  SignInUnsupported access, last successful request time, last error kind/time,
  and whether the root itself answered 401. Enabled-state persistence supplies
  TurnedOff as an overlay without erasing the last real check. Success retains the
  historical last error; root success clears the root-401 flag. No polling or fake
  Authenticated state is introduced.
- `ServerManagementViewModel.catalogueSources` maps access state separately from
  legacy auth-only `servers` cards. Public catalogues cannot map to Signed out or
  offer the legacy Sign in again action. No screen consumes the new field yet;
  catalogue rows are excluded from the old cards, and login intents for catalogue
  types cannot navigate to the bearer login screen. No screen/picker/route was added.
- Typed profile/source-scoped Basic account details live under `OpdsCredentials`
  in SecureSettings, never in `ServerCredentials.accessToken` or
  `ServerTokenProvider`. Empty passwords are accepted; account `toString()` is
  redacted. Catalogue bearer sessions are rejected/ignored.
- Address edits invalidate source work/cache/search. Scheme/host/effective-port
  changes clear account details first; path/query-only changes retain them.
  On a failed address write, work can remain cancelled and origin-changed account
  details can remain cleared: this deliberately fails closed instead of restoring
  a secret that might be retargeted. Re-registering an existing catalogue ID is
  rejected; editing must use the explicit update operation.
- Both logout-all entry points clear catalogue account details and cancel source
  work, retaining sources. For profiles containing catalogues, the auth logout
  entry point skips the legacy bulk cleaner because it deletes local reading
  metadata/outbox rows too. Existing-only profiles keep their prior cleaner
  behavior. There is no acquisition/book/file deletion in this lifecycle; Phase 3
  must bind durable acquisition cancellation to the same work contracts.
- A catalogue-only profile bypasses welcome as a configured setup (including a
  temporarily disabled catalogue). The specifically named authenticated-remote
  indicator remains false: anonymous access is not a library account/session.
- Turn off persists `enabled=false`, excludes the source from the provider and
  cancels work while keeping account details. Remove account retains the enabled
  source and historical status until the next request yields Public/SignInNeeded.
  Remove catalogue clears the source, account details, cache/search and persisted
  status. Existing server deactivation still only clears its existing credentials.
- A neutral `ProfileWorkRegistry` cancels catalogue work synchronously before
  switching/deleting profile context, fencing late registrations even if flow
  observers are delayed. Profile deletion also clears CatalogueSources,
  OpdsCredentials and CatalogueAccessStatus. Old session references cannot make
  requests or expose another profile's credentials/cached responses.
- Device-backup audit: Android's manifest has `allowBackup=true`, but both its
  `fullBackupContent` (Android 11 and lower) and `dataExtractionRules` (Android 12+
  cloud backup and device transfer) already exclude `sharedpref/SecureSettings.xml`.
  `PreferencesModule` creates exactly SecureSettings; Android encrypts it with
  AndroidX Security, iOS uses Keychain. No backup exclusion had to be changed.
  The Cloud/session implementation reads explicit cloud-session keys, not arbitrary
  preferences; the sync modules do not depend on preferences or credential stores.
  OpdsCredentials has only the secure-store and profile-cleanup consumers, and is
  absent from portable/cloud payloads. It is not synced to Parrot Cloud.

**Final verification and Phase 2 gate (2026-10-08, independent review at
`ce24901c`): met.** The implementing run stopped before verifying; the results
below were re-run afterwards. Android host and iOS simulator, passed/total on
each: OPDS implementation 162/162, server API 19/19, server implementation
26/26, server-opds 11/11, user implementation 6/6, auth domain 8/8, settings
data 1/1, settings UI 19/19; composeApp 15/15 on Android host only (its iOS test
binary still fails to link FirebaseCore, unchanged and unrelated).
`:androidApp:assembleDebug` and `:composeApp:linkDebugFrameworkIosSimulatorArm64`
both pass. No screen, picker, or route can reach OPDS.

**Carry into Phase 3 (found in review):** `LogoutUseCase.logoutAll()` now skips
`databaseCleaner.clearAllData()` entirely whenever the profile has a catalogue.
That keeps catalogue data safe, but it also leaves a signed-out Storyteller or
Audiobookshelf server's cached library and positions on the device, which
existing-only profiles still clear. Phase 3 must replace the skip with a cleaner
that clears library-server data as before and leaves catalogue acquisitions,
provenance, and acquired books alone, with a test for a profile that has both.

The items 1–3 verification found four existing Android host failures in
`LinkPickerViewModelTest` and `LinkReviewViewModelTest`; the same four failures
reproduce in an untouched worktree of `opds/phase1-protocol-core` (`20c89e78`).
Their assertions and production behavior remain unchanged. Two Storyteller test
method names had commas removed because Kotlin/Native rejects those names;
their bodies and assertions are unchanged.

The items 1–3 final verification: OPDS implementation 161/161, server API 19/19, server
implementation 12/12, and Storyteller 35/35 pass on both Android host and iOS
simulator. Base passes 13/13 Android and 11/11 iOS; books UI is 67/71 on each
platform (the four failures above). Compose passes 15/15 Android; its iOS test
binary cannot link because `FirebaseCore` is not found, so no current-run Compose
iOS test result is claimed. Preferences API has no tests and assembles successfully.
`:androidApp:assembleDebug` and
`:composeApp:linkDebugFrameworkIosSimulatorArm64` both pass. The final native
checks used two workers and an 8 GiB JVM heap after cancelling a heap-constrained
verification attempt; no project memory settings were changed. The remaining
Compose iOS test-link blocker was left for later at the owner's request.

### Phase 3 — durable acquisition and local-library integration

- Add acquisition/provenance SQLDelight APIs, schemas/migrations/DAOs and cleanup.
  Choose the next migration number at implementation time (current chain includes
  `38.sqm`); do not rewrite existing user data or assume a database reset.
- Implement bounded streaming, cancellation/recovery, fresh-link resolution,
  validation/hash, finalization journal, reusable staged import, and provenance.
- Audit local/cloud codecs, origin handling, file backup, library merge, device
  deletion, server removal, and profile deletion.
- Add the publication-key lookup that prevents re-downloading a book already
  acquired (§10.8). Metadata sync is not held back for acquired books (§10.0,
  reversed 2026-10-08).

**Test-first order:**

1. Acquisition state machine: legal transitions including `waiting` and
   `interrupted` (§11.7); restart turns each non-terminal state into
   `interrupted` and nothing restarts until the user taps "Start again"; at most two run and the
   rest start in request order; cancel removes the request.
2. Request deduplication: duplicate taps and concurrent identical requests yield
   one job and one library book.
3. Download validation: unknown length, length mismatch, truncated stream,
   oversize, invalid ZIP/EPUB, protected file, path-traversal entries.
4. Staged-import finalization: exact-byte match attaches provenance without
   resetting progress; different bytes never overwrite an existing file; crash
   at each file-move/database step leaves no row pointing at missing bytes.
5. Already-acquired lookup by source + publication key (§10.8), including the
   regenerated-file case.
6. Profile fencing: switch or delete mid-download and mid-finalization writes
   nothing into another profile.
7. Cleanup: server removal keeps books and provenance; library merge rekeys
   provenance; catalogue acquisition triggers no file upload.

Write the existing-importer characterization tests before extracting the staged
import API, so the refactor is checked against current behavior.

**Gate:** acquisition produces one usable local book, survives retries/restarts
without duplicates, cannot write into a different profile, and never needs a
Parrot Cloud file record or hosted EPUB. Acquiring the same publication again
after the provider regenerated its file does not silently add a second book.

**Phase 3 status after the staged-import run (2026-10-08, branch
`opds/phase3-acquisition`).** Done: the reusable staged import only. Not started:
the acquisition queue, the database tables and migration, download code, the
publication-key lookup, and the logout cleaner carried over from Phase 2.

- `StagedBookImportManager` (books-domain) takes an EPUB already on local disk, a
  `BookFileOrigin` and optional `BookFileProvenance`, and returns the library
  book id, media type and `NewBook` / `ExistingBook`. `StagedBookImporter`
  (books-data, common) implements it; both file pickers copy the picked file to
  staging and delegate. The staged path sizes and hashes the file from disk in
  8 KiB chunks and moves it. Android's picker still fills its staged copy with
  `platformFile.readBytes()`, as before.
- Contract: on success the staged file is consumed (moved, or deleted for an
  exact-byte match). On failure it is left in place for the caller. The path
  must end in `.epub`.
- `LibraryLocalDataSource.addStagedFile` holds one mutex across duplicate lookup,
  move and database write, and runs them uncancellable. A failed write moves the
  file back to staging and deletes the cover. A process death between the move
  and the commit still leaves an orphan file under a new UUID: there is no
  journal yet, so the acquisition journal (§5.2 step 8) must cover it.
- Finalizing the same staged file twice is idempotent only while the caller
  still holds the candidate (hash known). `importStagedEpub` on a staged path
  that has already been consumed fails with "File is empty"; the acquisition
  record must store the returned library id before treating a job as retryable.
- Origin rules: `ORIGIN_CATALOGUE_DOWNLOAD = "catalogue_download"`. A new book
  from that origin is inserted through `insertBookWithoutSync` and gets no
  metadata outbox entry. `backupAll` skips it (it only takes `import`).
  `keepDeviceFilesAsImports` now converts `cloud_download` rows only.
  Provenance is carried to `ImportedFileCandidate` and not stored anywhere yet.
- Open for the next run: an explicit backup of a catalogue-origin file cannot
  work yet. `enqueueUpload` refuses a book with no remote revision, nothing
  writes the held-back outbox entry at backup time, and the backup banner and
  sheet only list `localOrigin == "import"`. Positions, bookmarks and reading
  sessions of an unsynced catalogue book still go to the outbox through their
  own paths; only the `library_book` upsert is held back.
- Found while pinning today's behavior: on iOS a file the Swift metadata bridge
  cannot read is not rejected. `IosEpubMetadataExtractor` falls back to the
  file name and returns success, so any non-empty file imports. Android rejects
  it through Readium. Catalogue downloads need real validation on iOS (§5.2
  step 6) before they reach the staged import.

**Verification of the staged-import run (2026-10-08).** Android host / iOS
simulator, passed/total: books-data 112/112 and 104/104; database
implementation 111/111 (it has no iOS tests); server-parrot-cloud 62/62 and
58/58; server implementation 26/26 and 26/26; server-local 2/2 and 2/2;
books-domain 60/60 and sync-data 74/74 on Android host only, because their iOS
test sources do not compile: test names containing a comma in
`ObserveLinkSuggestionsUseCaseTest`, `LibraryMutationSyncEngineTest` and
`ProgressSyncEngineTest`, all unchanged by this run. books-ui is 67/71 on each
platform (the four known link-screen failures). `:androidApp:assembleDebug` and
`:composeApp:linkDebugFrameworkIosSimulatorArm64` both pass. The 20
characterization tests written before the extraction are byte-identical after
it. The Android picker entry point itself is not host-testable: FileKit's
`PlatformFile` is Java 21 bytecode and host tests run on JDK 17, so Android is
covered one level below it.

**Phase 3 status after the acquisition-queue run (2026-10-08, branch
`opds/phase3-acquisition`).** Done: the three tables and one migration, the
acquisition state machine, the durable queue, streaming download, and the file
check, up to "a checked, hashed EPUB is in staging". Not done: calling the
staged importer, writing provenance rows, the publication-key lookup, any
screen.

- **Database.** `39.sqm` takes the schema from version 39 to 40 and creates
  `catalogue_acquisitions`, `catalogue_book_sources` and `catalogue_documents`.
  They are profile-scoped the way every table is: one database file per
  profile. DAOs: `CatalogueAcquisitionsDatabase`, `CatalogueBookSourcesDatabase`,
  `CatalogueDocumentsDatabase` (lib/database/api, package `catalogue`). None of
  them is a `DataClearable`, so sign-out keeps provenance (§10.0). Timestamps in
  these tables are epoch milliseconds.
- **States.** `AcquisitionStateMachine` (feature/catalogue/domain) is the only
  place a legal move is defined: Waiting, Downloading, Checking, Adding, Done,
  Failed(reason), Interrupted, with reasons connection, too_large, storage,
  invalid, protected, refused, sign_in. Cancel deletes the request from every
  state except Done. Retry: connection, storage, refused. Dismiss: too_large,
  invalid, protected. A sign_in failure goes back to Waiting through `SignedIn`.
- **Queue.** `CatalogueAcquisitionQueue` (feature/catalogue/data) implements
  `CatalogueAcquisitionManager` and `CatalogueWorkController`. Two run at once;
  the order is the stored `queue_position`. A retried or restarted request goes
  to the back. The first time a profile is touched in a process, rows left in
  Downloading, Checking or Adding become Interrupted and their staging files
  are deleted; Waiting rows stay and nothing starts until `request`, `retry`,
  `startAgain`, `signedIn` or `start`. Nothing calls `restoreAfterRestart()` at
  app start yet; the same recovery runs on first use.
- **Fencing.** A worker keeps the profile id it started with. Every write is
  `withProfile(thatProfile) { … }` and is refused when another profile is open.
  The lock is held for the write only. On a profile switch or deletion
  `ProfileWorkRegistry` calls the queue before the database closes; running
  requests are stored as Interrupted. A source that is turned off, removed or
  gets new account details cancels its unfinished requests, except one that is
  Failed(sign_in), which is what the new details are for.
- **Download.** `OpdsTransport.download` streams to a sink under the feed rules
  for redirects, origin and credentials, with `OpdsBudgets.MAX_DOWNLOAD_BYTES`
  (512 MiB). `CatalogueAcquisitionRepository` (lib/server/api, implemented by
  `OpdsCatalogueRepository`) reloads the listing page outside the cache, finds
  the file by publication key and representation key, then streams it. The
  stored locator is the listing page address; the file link is never stored.
  The representation key is the media type plus its place among files of that
  type in catalogue order (`application/epub+zip#1`).
- **Staging.** Android: `noBackupFilesDir/catalogue_staging/<profile>/`. iOS:
  the temporary directory, same layout. Names are a random UUID plus
  `.epub.part`, renamed to `.epub` once checked. Free space is checked before
  the request, against the declared length, and every 4 MiB written; 16 MiB
  must stay free.
- **File check.** `EpubFileChecker` (lib/epub) runs in common code on both
  targets, on the ZIP reader lib/epub already had. See the limits in
  `EpubFileLimits`.
- **Importer seam.** The queue calls `CatalogueBookAdder`. The app binding is
  `DeferredCatalogueBookAdder`, which answers "not added yet", so a request
  stops in Adding with `staging_path` and `local_hash` set. The next run
  replaces that binding with one that calls `StagedBookImportManager` and
  writes `catalogue_book_sources`.
- **Found, not fixed.** The generated Koin module builds `ServerRegistryImpl`
  with its default `catalogueWork = emptyList()` (and private credential and
  access stores), because constructor parameters with defaults are skipped. In
  the running app `cancelCatalogueWork` therefore reaches no controller: not
  the Phase 2 repository factory and not this queue. The queue takes
  `Lazy<CatalogueRepositoryProvider>` so that fixing the registry does not
  create a dependency cycle.

**Verification of the acquisition-queue run (2026-10-08).** Android host /
iOS simulator, passed/total: database implementation 124/124 (it has no iOS
tests); opds implementation 177/177 and 177/177; epub implementation 45/45 and
30/30 (the 15 extra Android tests are host-only JDK fixtures); server api 19/19
and 19/19; server implementation 26/26 and 26/26; server-opds 25/25 and 25/25;
catalogue domain 11/11 and 11/11; catalogue data 56/56 and 54/54 (the 2 extra
Android tests resolve the generated Koin module); composeApp 15/15 on Android
host (its iOS test link error is the known FirebaseCore one). opds api has no
tests. `verifyCommonMainAppDatabaseMigration` passes, run uncached.
`:androidApp:assembleDebug` and `:composeApp:linkDebugFrameworkIosSimulatorArm64`
both pass. The link was forced to run again on the final code. Running both
targets with `--rerun-tasks` in one 4 GiB Gradle daemon runs the iOS link out of
Java heap; built one after the other they pass.

**Phase 3 status after the library-join run (2026-10-08, branch
`opds/phase3-acquisition`).** Done: the Phase 2 wiring bug, adding a checked
download to the library, the crash journal, the "in your library" lookup,
restart recovery at app start, persistent iOS staging, and the sizes the design
copy needs. No screen. Not done (next run): the sign-out cleaner, holding back
positions and bookmarks for unsynced catalogue books, backup of catalogue
books, library merge, book deletion and catalogue removal cleanup, the
saved-pages cache, and the Phase 3 gate.

- **Wiring bug (fixed).** The generated Koin module keeps a constructor
  parameter's default value and does not inject it. `ServerRegistryImpl` ran
  with private account and access stores and zero `CatalogueWorkController`s;
  `UserRegistryImpl` (same Phase 2 commit) ran with a private
  `ProfileWorkRegistry`, so a profile switch stopped no catalogue work either.
  The four defaults are removed. `composeApp/src/androidHostTest/.../di/`
  boots `ParrotKoinApp` itself (`RealAppGraph`) with only the Android-only
  edges replaced (preferences, database file, network engine, staging and
  library folders, the Readium metadata reader, Firebase, cloud configuration)
  and checks instances and behavior there. **Rule: never give a DI-injected
  parameter a default value.** A repo-wide scan found one more instance outside
  this work, `AppVisibilityReporter.productUsage` (on main since a258e941);
  it is not fixed here. `BookFileTransferEngine` and `SyncDataRepository` have
  such defaults but are built by module functions that pass every argument.
- **Account details.** `CatalogueAccountEditor.saveAccount` (lib/server/api,
  implemented by the registry) is the way to save new details: it cancels the
  source's work first. `OpdsCredentialStore.save` alone cancels nothing.
  Removing details stays `ServerRegistry.clearCredentials`.
- **Adding.** `LibraryCatalogueBookAdder` (feature/catalogue/data) calls
  `StagedBookImportManager.importStagedEpub` with `CatalogueDownload` origin and
  provenance. catalogue-data depends on books-domain; nothing in feature/books
  depends on a catalogue or OPDS module. The importer is resolved lazily, for
  the same cycle reason as the repository provider. A metadata failure comes
  back as `StagedBookNotReadable` and becomes `invalid`; every other import
  failure is `storage`.
- **Order on success** (`CatalogueAcquisitionQueue.completeAdd`): library book
  id on the row, `catalogue_book_sources` row, then Done with `detail_url` and
  `staging_path` cleared. The provenance row's id is the request id and is
  written with INSERT OR IGNORE, so repeating the sequence changes nothing.
  `catalogue_origin` is scheme, host and port of the catalogue's registered
  address (read from preferences, lock-free), or of the listing address when
  the catalogue is no longer registered. `selected_format` is the media-type
  part of the representation key. Rights text and the catalogue's `updated`
  value are carried on the request and the acquisition row.
- **Journal.** `library_import_journal` (40.sqm, schema 40 to 41) belongs to the
  importer, so the file picker is covered too. `LibraryLocalDataSource`
  records library path and cover path before the move and forgets them after
  the rows are committed. An entry found later: with its device-file row it is
  kept; without it the library file and cover are deleted. It is settled
  before every import and through
  `StagedBookImportManager.settleInterruptedImports`.
- **Restart.** `CatalogueAcquisitionStartup` (an `AppInitializer`) calls
  `restoreAfterRestart()` at app start and for every profile opened later.
  Loading a profile: settle the library journal; finish each Adding row that
  names its book or whose bytes the library has on this device
  (`findBookOnDevice`); interrupt everything else that was running; turn rows
  in an unknown state into Interrupted; delete staged files no row refers to.
  The same recovery runs when a profile is closed, so an add that committed
  during a switch ends Done.
- **Already in the library.** `CatalogueBookSourcesDatabase.findInLibrary`
  matches `publication_key` or `detail_identity` and joins `library_books`
  where `deleted_at IS NULL`. `CatalogueLibraryLookup` (catalogue domain)
  answers for one entry or a page. `request()` now returns
  `CatalogueRequestOutcome`: `Queued`, or `InLibrary(libraryBookId)` with
  nothing stored and nothing started, for any file of an acquired publication.
- **Staging on iOS** is `Application Support/catalogue_staging/<profile>/`,
  marked `NSURLIsExcludedFromBackupKey`. Files left in the old temporary
  location are not migrated; the system clears them.
- **Sizes.** `too_large` keeps the declared size in `expected_size_bytes`
  (the transport now reports it); the limit is
  `CatalogueAcquisitionLimits.MAX_FILE_BYTES`, checked equal to
  `OpdsBudgets.MAX_DOWNLOAD_BYTES`. `storage` keeps `needed_bytes` (declared
  size plus the 16 MiB margin) when the size was declared.
- **Changed behavior to know about.** A request in Adding holds its download
  slot until it is added (the "not added yet" result is gone). Cancelling a
  request, or removing its catalogue, in the instant the library commits the
  import can leave the book in the library with no request and no provenance
  row.

**Verification of the library-join run (2026-10-08).** Android host / iOS
simulator, passed/total: catalogue data 89/89 and 87/87 (the 4 extra Android
tests resolve the generated Koin module; iOS has 2 staging-location tests of
its own); catalogue domain 11/11 and 11/11; books data 121/121 and 113/113;
books domain 60/60 on Android host only (its iOS test sources do not compile,
as before); database implementation 132/132 (no iOS tests); opds
implementation 177/177 and 177/177; server-opds 25/25 and 25/25; server api
19/19 and 19/19; server implementation 29/29 and 29/29; server-local 2/2 and
2/2; user implementation 6/6 and 6/6; settings data 1/1 and 1/1; composeApp
29/29 on Android host, 14 of them in the real app graph (its iOS test link
error is the known FirebaseCore one). `verifyCommonMainAppDatabaseMigration`
passes. `:androidApp:assembleDebug` and
`:composeApp:linkDebugFrameworkIosSimulatorArm64` both pass, built in one
invocation with `--max-workers=2`.

**Phase 3 status after the clean-up run (2026-10-08, branch
`opds/phase3-acquisition`). Phase 3 is complete and its gate is met.** Done:
the sync decision change, sign-out, the clean-up rules, the saved-pages cache.
No screen.

- **Decision change (§10.0, §10.9).** A catalogue book writes the same
  `library_book` outbox entry as a picked file. `insertBookWithoutSync` is
  removed. `backupAll`, the backup banner and the backup sheet still leave
  catalogue-origin files out; whether such a file can be backed up is open
  (§11.7 "Still open", item 5). Position, bookmark, highlight and
  reading-session sync are untouched.
- **What the cleaner does today.** `DatabaseCleanerImpl` is handed
  `getAll<DataClearable>()` by the generated Koin module, and no table is bound
  under that type (`provideDataClearables` returns a `List`, which Koin does
  not use for a `List<T>` constructor parameter). In the running app
  `clearAllData()` therefore removes **nothing**. This is the same class of
  fault as the Phase 2 wiring bug, it predates this work, and it is **not
  fixed here**: fixing it would start deleting data on sign-out, which is a
  product decision. As written, the eight clearable tables would remove: the
  server book cache with its people, series, tags, collections, statuses and
  media files; **every** reading position and remote position, including
  those of library books; favourites; authors; reading sessions; session
  recaps; the whole sync outbox; sync checkpoints; and the Parrot Cloud file
  mirror and transfers. They would keep `library_books`, `device_files`, the
  files and covers on disk, saved items, reader settings and book links. So a
  picked book would keep its row and file and lose its position, its
  favourite mark, its reading history and its unsent changes. Both facts are
  pinned by tests in `SignOutOfEverythingTest` (app graph).
- **Sign out of everything.** `LogoutUseCase.logoutAll()` no longer skips the
  cleaner when the profile has a catalogue. A catalogue book and a picked book
  come out in the same condition as each other, with or without the wiring
  fault, because the cleaner has no knowledge of a file's origin and the
  catalogue tables and the import journal are not clearable. For a catalogue,
  `ServerRegistry.clearCredentials` and `clearAllCredentials` now do nothing
  when it has no account details; when it has, they call the new
  `CatalogueWorkController.forget`, which removes every unfinished download
  (also one waiting for sign-in) and the saved pages, and then the details.
- **Clean-up rules.**
  - *Book deleted* (`deleteBookFromDevice`): its provenance rows and finished
    downloads go in the same transaction; it can be downloaded again.
  - *File removed, book kept:* `findInLibrary` requires a device file, so the
    lookup and `request()` no longer answer "in library". The same bytes go
    back to the same book (`findLibraryBookBySourceHash`) and its position
    stays; the provenance row is not written a second time. Different bytes
    become a separate book, as for any import.
  - *Merge:* provenance and downloads move to the surviving book; rows for
    the same catalogue, publication and bytes collapse to the oldest.
  - *Catalogue removed:* unfinished downloads, staged files, saved pages,
    account details and status go. Books and provenance stay; provenance
    carries the catalogue's name and origin and reads without the catalogue.
  - *Profile deleted:* `CatalogueAcquisitionStartup` watches the profile
    list and removes staging folders of profiles that no longer exist, also
    ones left from a deletion while Parrot was closed. An empty list is
    treated as "not loaded yet".
  - *The race from the last run is closed.* After the library returns the
    book, the queue finishes under `NonCancellable`, and a request whose row
    is gone (cancelled, or its catalogue removed, in that instant) still gets
    its provenance row from the worker's copy of the request. Tested by
    cancelling, and by removing the catalogue, from inside the library's
    commit. Left: a process that dies in that same instant, after the row was
    deleted and before the provenance write, leaves the book without
    provenance; the import journal does not carry provenance.
- **Access generation.** `OpdsCredentialStore.accessGeneration` is stored per
  profile (`CatalogueAccessGenerations`), changes on every save of different
  details and every removal, and never repeats in a profile. The in-memory
  counter in the repository factory is gone.
- **Saved pages.** `SavedPagesFeedCache` (lib/server-opds) implements the
  Phase 1 `OpdsFeedCache` over `catalogue_documents` and replaces the
  in-memory cache in the app. Budget `OpdsBudgets.MAX_SAVED_PAGES_BYTES`
  (25 MiB per profile database), oldest `stored_at` first. Never saved: a
  `no-store` page, a page with a `Vary` other than `Accept` (the loader's
  existing rule), a body over `MAX_RESPONSE_BYTES`. Downloads and search
  descriptors do not pass through the cache. Validators are stored and every
  load revalidates. `41.sqm` (schema 41 to 42) adds `effective_url`, the
  address after redirects, because links in a saved page are relative to it.
- **Offline results.** Only `OpdsTransportError.Code.UNREACHABLE` counts: any
  network failure that is not a timeout or a TLS failure (no connection, host
  not found, connection refused). A timeout, a TLS failure and a server error
  do not. With a saved page the repository returns
  the document with `CatalogueFetchStatus.savedCopyAt` set to its stored time
  (`opds-offline`); without one the error is
  `CatalogueErrorKind.OfflineNoSavedCopy` (`opds-offlineNone`). The stored
  last check says `Unreachable` in both cases; `OfflineNoSavedCopy` is never
  persisted.
- **Who sees a saved page.** Rows are keyed by source, access generation and
  request address, in the profile's own database, and are read and written
  only while that profile is open. A change of account details, sign-out of
  a catalogue with details, turning it off, moving it and removing it delete
  that source's pages (`CatalogueWorkController.cancel` in the factory);
  saving a page also drops the source's pages of other generations. A profile
  switch ends the session and keeps the pages.

**Phase 3 gate (checked 2026-10-08): met.**

| Gate item | Result | Evidence |
| --- | --- | --- |
| One usable local book | Met | `CatalogueDownloadToLibraryTest`: one library row, one device file with the served bytes, cover, provenance, the import's outbox entry. Not opened in the reader on a device. |
| Survives retries and restarts without duplicates | Met | Queue restart and crash-step tests (`AcquisitionAddToLibraryTest`, `AcquisitionQueuePipelineTest`), the import journal tests, and the cancel-at-commit tests added in this run. |
| Cannot write into a different profile | Met | `AcquisitionQueueFencingTest`; saved pages: `SavedPagesFeedCacheTest`, `OpdsSavedPagesTest`. |
| Never needs a Parrot Cloud file record or hosted EPUB | Met | The app-graph tests run with Parrot Cloud not configured; no `CloudBookFileEntity` is written. |
| Same publication again after the provider changed its file | Met | `request()` answers `InLibrary` before any download; the user is told the book is already in the library. An explicit "get updated copy" is deferred (§10.0). One case does add a book: the book was kept but its file removed from this device, and the catalogue's file has changed since. That follows the rule that different bytes never overwrite. |

**Run report of the clean-up run (2026-10-08).**

```text
--- REPORT ---
Branch: opds/phase3-acquisition

Commits this run (hash + subject, oldest first):
6a71843a docs(opds): record that catalogue books sync like imports
d803747e feat(books): sync a catalogue book's details like a picked file
98f15ac3 feat(auth): sign out of everything the same way with or without a catalogue
49724037 feat(database): clean up catalogue records when a book is deleted or merged
5f73aef9 feat(catalogue): keep a book's source when a cancel lands as it is added, and clean up staging
8c606fb8 feat(database): remember the address a saved catalogue page was served from
51f4ab8c feat(server): keep a catalogue's access generation across restarts
0c8aa1c1 feat(opds): save opened catalogue pages and show them when the catalogue cannot be reached
0c4fba34 test(app): asking again after the catalogue changed its file adds no second book
06df52f6 docs(opds): record the clean-up run and the Phase 3 gate
dac70f6a test(catalogue): keep commas out of test names so they compile for iOS
fd186445 docs(opds): add the run report of the clean-up run
(plus the commit that records the build results in this report)

Test command(s) run:
./gradlew :<module>:testAndroidHostTest ... --max-workers=2 --continue   (16 modules, list below)
./gradlew :<module>:iosSimulatorArm64Test ... --max-workers=2 --continue (12 modules that have iOS tests that compile)
./gradlew :lib:database:implementation:verifyCommonMainAppDatabaseMigration --rerun --max-workers=2
./gradlew :androidApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64 --max-workers=2

Per module: <module> Android host <passed>/<total>, iOS <passed>/<total>
composeApp                    Android host 39/39,   iOS not run (known FirebaseCore link error)
feature/auth/domain           Android host 8/8,     iOS 8/8
feature/books/data            Android host 121/121, iOS 113/113
feature/books/domain          Android host 60/60,   iOS not run (known: its iOS test sources do not compile; not touched this run)
feature/catalogue/data        Android host 96/96,   iOS 94/94
feature/catalogue/domain      Android host 11/11,   iOS 11/11 (not touched this run)
feature/settings/data         Android host 1/1,     iOS 1/1 (not touched; it has a sign-out test)
feature/sync/data             Android host 74/74,   iOS not run (known: its iOS test sources do not compile)
lib/database/implementation   Android host 138/138, iOS has no tests
lib/opds/implementation       Android host 178/178, iOS 178/178
lib/server/api                Android host 19/19,   iOS 19/19
lib/server/implementation     Android host 30/30,   iOS 30/30
lib/server-opds               Android host 43/43,   iOS 43/43
lib/server-parrot-cloud       Android host 62/62,   iOS 58/58
lib/server-local              Android host 2/2,     iOS 2/2
lib/user/implementation       Android host 6/6,     iOS 6/6
lib/opds/api, lib/preferences/api, lib/database/api: no tests.
feature/books/ui was not touched and not run (its four known failures are unchanged).

Migration added (number) or "none", and verification result:
41.sqm (schema version 41 to 42): one nullable column, catalogue_documents.effective_url.
verifyCommonMainAppDatabaseMigration: passed, run with --rerun on the final schema.
SavedPagesMigrationTest (3 tests) and MigrationChainTest pass.

App build results (Android assemble, iOS framework):
Both passed in one invocation with --max-workers=2 (BUILD SUCCESSFUL in 1m 52s; both tasks executed, not up to date).

Steps complete (0-4): 0, 1, 2, 3 and 4 are complete. Step 1 is complete as specified, but read the first known problem: the cleaner it now always calls removes nothing in the running app.

Phase 3 gate met (yes/no) and why:
Yes.
- One usable local book: one library row, one device file with the served bytes, a cover, a provenance row and the same outbox entry an import gets (app-graph test). Not opened in the reader on a device.
- Retries and restarts without duplicates: queue restart and crash-step tests, import-journal tests, and the new cancel-at-commit tests.
- Cannot write into another profile: queue fencing tests; saved pages are read and written only for the open profile.
- No Parrot Cloud file record or hosted EPUB: the app-graph tests run with Parrot Cloud not configured.
- Same publication after the provider changed its file: request() answers "in your library" before any download, no file request is made and no second book is added (app-graph test). "Get updated copy" stays deferred.
One case does add a second book, by the rule given for this run: the book was kept but its file was removed from this device, and the catalogue's file has changed since. Different bytes never overwrite.

What clearAllData removes today for file-picker imports:
In the running app: nothing at all, for imports or anything else. DatabaseCleanerImpl takes List<DataClearable>; the generated Koin module fills that with getAll<DataClearable>(), and no table is bound under that type (provideDataClearables returns a List, which Koin does not use for a List<T> parameter). The list is empty, so clearAllData() is a no-op. Pinned by a test in the app's own graph. This predates this run.
As the code is written (if the eight tables were handed over): rows kept: library_books, device_files, saved items, reader settings, book links. Files kept: the book file and the cover. Rows removed: every reading position and remote position (including the import's own), favourites, reading sessions, session recaps, the whole sync outbox (including the import's unsent library_book upsert and any unsent position), sync checkpoints, the Parrot Cloud file mirror and transfers, and the whole server book cache (books, people, series, tags, collections, statuses, media files, readalouds, authors). Also pinned by a test.

What sign-out now does for a profile with a server, an import and a catalogue book:
1. Every non-local server gets clearCredentials. Storyteller: its credentials are cleared as before. A catalogue without account details: nothing. A catalogue with account details: every unfinished download (also one waiting for sign-in), its staged files and its saved pages are removed, then the details. All catalogues stay registered.
2. databaseCleaner.clearAllData() is called, as for a profile without a catalogue. Today that removes nothing (see above), so the server's cached data is exactly as it is for an existing-only profile: still there. The test compares a profile with a catalogue against one without and they match.
3. The import and the catalogue book end in the same condition as each other: library row, device file row, file and cover on disk, position and unsent changes all unchanged. If the cleaner is ever wired, both lose the same things, because it has no knowledge of a file's origin.
4. Provenance rows, finished downloads and the import journal are untouched.

Done this run:
- Step 0: plan 10.0, 10.9 and 11.7 updated; catalogue books write the same library_book outbox entry as imports; insertBookWithoutSync removed everywhere; backupAll and the backup banner/sheet unchanged and still tested.
- Step 1: the catalogue skip in logoutAll removed; CatalogueWorkController.forget added; sign-out of a catalogue with account details removes all its unfinished downloads and saved pages.
- Step 2: book deleted, file removed but book kept, library merge, catalogue removed, profile deleted (staging folders), and the cancel-at-commit race closed in the queue. Each has tests; the first four and the race also in the app's own graph or with the cancel injected at the commit point.
- Step 3: durable access generation; SavedPagesFeedCache over catalogue_documents behind OpdsFeedCache (25 MiB per profile, oldest first, no-store honored, validators kept, revalidation on every load); "saved copy" with stored time and "offline, no saved copy"; pages cleared on account change, sign-out with details, turn off, move and removal.
- Step 4: gate checked, plan status and Phase 3 section updated.

Not done or partly done, and why:
- The cleaner wiring fault is not fixed, on purpose: fixing it would start deleting reading positions, history and unsent changes on sign-out for every user. That needs a product decision.
- A process that dies in the same instant as a cancel lands on the library's commit (after the request row is deleted, before the provenance write) still leaves a book without provenance. The import journal does not carry provenance, so start-up cannot repair it. The in-process race is closed.
- Nothing was run on a device or emulator; all checks are host and simulator tests.

Tests skipped, ignored or weakened (file + name + reason), or "none":
- lib/database/implementation LibraryJoinMigrationTest, "a new database is at version 41 or later and has the import journal": the exact-version check (== 41) became >= 41, because 41.sqm moved the schema to 42. The exact version is now asserted in SavedPagesMigrationTest.
No test was skipped or ignored.

Existing tests that had to change, and why:
- LibraryOriginRulesTest (2 tests), InsertBookRowsTest (1), CatalogueDownloadToLibraryTest (1 assertion): they asserted "no outbox entry" for a catalogue book; the decision is reversed.
- BookFileTransferEngineTest: one comment only.
- CatalogueLogoutEntryPointTest: the cleaner is now called for a profile with a catalogue.
- lib/server/implementation CatalogueLogoutTest: sign-out no longer cancels the work of a catalogue without account details.
- CatalogueDownloadCancellationTest "removing account details...": it now saves details first, because removing details that were never saved does nothing.
- CatalogueDaosTest: its library-book fixture now adds a device file, because "in library" requires one.
- PositionOriginMigrationTest: LATEST constant 41 to 42.
- OpdsProfileLifecycleTest: two new constructor arguments of the repository factory.
- CatalogueAcquisitionStartupTest: its fake user registry now emits the profile list.
- Test fakes: four lost insertBookWithoutSync; two staging fakes gained deleteFoldersExcept; the EPUB fixture moved to CatalogueTestEpub.

Places the plan was ambiguous and what I chose:
- Sign-out and catalogues without account details. Plan 10.0 said sign-out "cancels active acquisitions"; this run's brief said acquisition rows are not deleted except for a catalogue that had account details. I followed the brief: a catalogue without details is left alone, running downloads included. 10.0 is updated.
- "Server's cached data is gone as before." It is not gone today (the cleaner is a no-op), so I tested "the same as a profile without a catalogue" and pinned the actual value.
- Cancel landing at the commit: the book is kept and gets its provenance row; the request is gone. I did not undo the import, because the bytes may have matched a book that already existed.
- "Device file removed, book kept": the lookup simply stops answering "in library" (the SQL requires a device file). There is no separate "in library, not on this device" answer yet.
- "No duplicates" on merge: one row per catalogue, publication and file hash; the oldest is kept. Rows with different hashes are kept as separate acquisitions.
- "Offline or host unreachable": only the transport's UNREACHABLE code (any network failure that is not a timeout or a TLS failure). A timeout does not show the saved copy.
- The saved page needs the address it was served from after redirects, and the table had no column for it, so I used the one allowed migration.
- The generation is bumped in the credential store (every save of different details, every removal), not in the registry, so no caller can change details without it.
- Oldest-first eviction uses the stored time, which a successful revalidation refreshes.
- Profile deletion has no hook in lib/user, so the staging folder is removed by watching the profile list; this also cleans folders left by a deletion while the app was closed.

Files changed outside feature/catalogue, feature/books, feature/auth, lib/database, lib/server and lib/server-opds:
- docs/opds-server-implementation-plan.md
- composeApp/src/androidHostTest/.../di/: CatalogueCleanupRulesTest.kt (new), SignOutOfEverythingTest.kt (new), CatalogueTestEpub.kt (new), CatalogueDownloadCancellationTest.kt, CatalogueDownloadToLibraryTest.kt, RealAppGraph.kt (tests only)
- lib/opds/api: OpdsFeedCache.kt (generation is a Long), OpdsFeedLoader.kt (SavedCopy result), model/OpdsBudgets.kt (saved-pages budget)
- lib/opds/implementation: cache/CachedOpdsFeedLoader.kt, and its test FeedCacheTest.kt
- lib/preferences/api: Preferences.kt (one key, CatalogueAccessGenerations)
- lib/user/implementation: UserRegistryImpl.kt (that key added to the list cleared when a profile is deleted)
- feature/sync/data LibraryBookSyncApplierTest.kt and lib/server-parrot-cloud ParrotTestLibraryBooksDatabase.kt (test fakes lost insertBookWithoutSync)

Known problems I am leaving:
1. clearAllData() removes nothing in the running app (wiring, predates this run). "Sign out of everything" therefore leaves reading positions, history, the outbox, sync checkpoints and the server book cache in place for every profile.
2. If that wiring is fixed as written, sign-out deletes the reading position, favourite mark, reading history and unsent changes of file-picker imports (and now catalogue books) while keeping the books. That damages imports; not fixed here, as instructed.
3. A catalogue book now syncs its details, so another device lists a book it has no file for, like an import that was never backed up. Provenance is not synced.
4. Backup eligibility is unchanged in code, but its effect moved: a catalogue book can now get a remote revision, so enqueueUpload no longer refuses it for lacking one. Nothing in the UI offers it. Recorded in plan 11.7 as open.
5. Crash in the cancel-at-commit instant can still leave a book without provenance (see "Not done").
6. After sign-out a catalogue's stored status still says "signed in as <name>" until its next request. Existing behavior.
7. UserRegistryImpl.deleteProfile carries a note that the database file is deleted elsewhere; I did not find where and did not check further.
8. AppVisibilityReporter.productUsage still has a DI default value (reported last run, on main).
9. Saved pages and the new SQL are covered on iOS only through fakes: lib/database/implementation has no iOS tests.
--- END REPORT ---
```

### Phase 4 — complete browsing feature

- Create `feature/catalogue/domain`, `data`, and `ui`; add source/browser/detail
  routes, navigation/search/facets/page controls, auth sheets, and transfer UI.
- Integrate server management and library browse/add entry points.
- Add translations, safe description/image rendering, accessibility, and e-ink QA.
- Add validated presets as data, including accurate access/rights wording.
- Implement the per-server cover loading path (§10.7) and every screen/state in
  §11, building from the boards and exact copy (§11.7).
- Close the "Still open" points in §11.7 before building the affected screens.
- Add `tools/ember-fixtures` fixtures for every board and capture them to
  `design/screens/catalogue-*.png` in Day and E-ink.

**Test-first order** (view-model and use-case logic only, once §11 states are
agreed; layouts are not test-first):

1. Browser state reducer: first load, next-page failure distinct from empty,
   offline copy, sign-in required.
2. Stale results: a cancelled or superseded search, or a source/profile switch,
   cannot overwrite the current list.
3. Page budget behavior at the limit (§10.11).
4. Publication action mapping: classifier result + acquisition state + library
   lookup → the single action shown (Download, In your library, Open, Not
   available here).
5. Cover requests for an OPDS source go through the per-source path and never
   carry another server's token (§10.7).
6. Route arguments contain no credentials or full feed URLs (§10.6).
7. Default-file choice (§11.7): first openable file in catalogue order;
   fallback labels; unsupported formats listed but not selectable.
8. Paging per theme: auto-load near the end on Day/Night, button-only on E-ink,
   failure keeps loaded items and retries the same next link.
9. Row download: fetches one publication on tap, falls back to the book page
   for several editions or no openable EPUB.
10. Description sanitizer: allowed tags kept, links flattened to text, images,
    tables, scripts, and styles dropped.
11. Key masking for addresses; failure reason → copy and action; catalogue
    status → Libraries row; waiting copy for one versus two active downloads.

**Gate:** anonymous and Basic custom servers work end-to-end on Android/iOS;
Gutenberg works without a custom parser and offline acquired books work after
server removal. Unsupported transactions never masquerade as supported downloads.

### Phase 5 — hardening and release

- Load-test large/deep synthetic catalogues, throttling, image-heavy feeds, disk
  exhaustion, flaky links, slow downloads, TLS/auth failures, cache eviction,
  removal/profile races, and import crash windows.
- Add source-safe analytics/diagnostics, limits documentation, and a verified
  provider compatibility matrix.
- Recheck provider endpoints/terms at release rather than trusting this research
  date indefinitely.

**Test-first order:** none new. Every defect found in hardening or provider QA
gets a failing regression test, with a reduced fixture where the cause is feed
content, before its fix.

Follow-ups, not hidden first-release promises: Digest auth, modern OAuth/PKCE,
OPDS lending/holds/availability extensions, LCP/other DRM, PDF/comics, audiobook
manifest acquisition, robust range resume/background execution, explicit edition
updates, cross-catalogue search, HTML/HTTP-Link catalogue autodiscovery, and
portable/cloud provenance/source settings. Each extends a defined contract.

## 8. Test plan

### Shared protocol tests

- OPDS1 navigation/acquisition/feed/full entry, namespace prefixes/defaults,
  inherited XML base/language, text/CDATA/entities/XHTML, facets/grouping,
  relative/encoded links, variants, missing optional fields, unknown extensions.
- OPDS2 publication/feed/groups/facets/images, contributors/localized fields,
  relation aliases/arrays, manifests, nested indirect acquisition and price.
- MIME parameters/order/case; generic JSON/XML compatibility; HTML/auth/error
  bodies rejected as feeds; bounded malformed-item warnings.
- RFC URL cases; effective redirected URL versus `self`; meaningful trailing
  slash and query; OpenSearch optional/required terms and result selection;
  RFC 6570 search expansion and Unicode/reserved-character encoding.
- Acquisition relation/type combinations: direct full EPUB, authenticated generic
  acquisition, sample, purchase, borrow, licence/HTML/archive wrappers, unsupported
  formats, multiple EPUB choices. Never extension-only acceptance.
- DTD/XXE/entity expansion, nesting/size limits, unsafe links, bounded data images.

### HTTP/adapter/registry tests

- Anonymous 200, ordinary Basic 401, OPDS auth JSON/Link discovery, empty password,
  bad credentials, unsupported Digest/OAuth, TLS errors, 403/404/429/503.
- Credentials absent on cross-origin feed links, covers, search, and downloads;
  same-host different-port/scheme and every redirect hop covered.
- No cross-profile/server cookie/auth/cache leakage; origin edits invalidate auth.
- Root URL persistence round trip without slash/query corruption.
- Anonymous source not dependent on fake credentials; disable/sign-out distinct.
- Every repository-provider list/single lookup capability guarded; adding OPDS
  cannot break personal-library/series/link suggestions/progress aggregation.
- Cache validators/Vary/no-store/auth isolation, stale response invalidation,
  cancellation, bounded eviction, navigation/pagination loop safeguards.
- Server list containing an OPDS entry decoded by a serializer that does not know
  the type: existing servers survive and are not overwritten on the next save.
- Logout-all clears OPDS credentials and leaves acquired books and provenance.
- Feed URL with a secret path segment never appears in logs, analytics, route
  state, or synced provenance.
- Cover requests for an OPDS source never receive another server's bearer token,
  including when two servers share an origin.

### Acquisition/database tests

- Unknown size, changed links, declared length mismatch, truncated stream, invalid
  ZIP/EPUB, oversized archive, disk-full, optional checksum mismatch, path attacks.
- Duplicate taps/concurrent requests, exact-byte match across providers, variant
  editions with equal titles, no progress reset, no silent existing-file overwrite.
- Cancel versus completion/import race; import/file-move/database crash points;
  restart during each non-terminal state; idempotent finalization/provenance.
- Profile switch/deletion and server remove/disable mid-download/finalization.
- Provenance survives server removal; merge rekeys correctly; deletion/GC removes
  only owned records/files. Origin policy does not auto-upload downloaded files.
- SQLDelight schema creation and supported upgrade verification; existing importer
  and cloud restore/upload tests remain green.

### UI and device QA

- Navigation back/route restoration, search changes and stale requests, next-page
  errors/retry, credential sheets, multiple variants/formats, unsupported actions,
  offline cache, cancellation/retry/Open, source edits/removal/profile reset.
- Gutenberg's observed two-step feed flow; anonymous and TLS/Basic Calibre;
  synthetic OPDS2 groups/facets/manifest feeds. Authenticated Standard Ebooks only
  with an authorized test account. No dependence on public endpoints in unit tests.
- Android and iOS offline reading, position/statistics/highlights persistence,
  font/accessibility/e-ink, memory/disk budgets, and lifecycle interruption.

Use each module's existing `commonTest` + Android host-test conventions and iOS
simulator tests. During implementation verify actual Gradle task names with
`./gradlew :<module>:tasks --all`; run focused tests, SQLDelight migration
verification, Android app build, and iOS simulator framework build/tests. This
planning task did not run builds or application tests.

## 9. File/change checklist

- `base/src/commonMain/kotlin/com/retro99/base/server/ServerType.kt`.
- Server API capabilities/config/access/catalogue contracts and serializer tests.
- `lib/server/implementation` composite factories, registry lifecycle, repository
  selection, neutral authenticator dispatch, and tests.
- New `lib/opds/{api,implementation}` and `lib/server-opds` modules.
- `feature/login/{domain,data,ui}` connection/probe/picker/validation flows.
- `feature/settings/ui/.../servers/*` status/actions/full feed URL editing.
- New `feature/catalogue/{domain,data,ui}` modules.
- `feature/home/ui/.../navigation/{HomeDestination,HomeNavigation,...}.kt`, route
  analytics mappings, library entry point, and navigation restoration tests.
- Books-domain staged-import API; books-data import/file-store/finalization,
  `LibraryLocalDataSource`, provenance/merge handling, origin-sensitive backup.
- Database API catalogue records, implementation SQLDelight schemas/migrations,
  DAOs/module registrations, profile/library cleanup and merge queries.
- Preferences API typed credential key; secure preferences/access-store plumbing.
- `ServerTypeSerializer.kt` / registry loading (downgrade safety),
  `feature/auth/domain/.../usecase/{LogoutUseCase,CheckAuthStateUseCase}.kt`,
  `feature/settings/data/.../SettingsDataRepository.kt` (logout semantics).
- `composeApp/.../initializer/CoilInitializer.kt` (cover auth routing) and
  `feature/books/ui/.../components/BookComponents.kt` (exhaustive type branch).
- `iosApp/iosApp/Info.plist` if LAN HTTP catalogues are supported on iOS.
- `settings.gradle.kts`, relevant module `build.gradle.kts`,
  `composeApp/build.gradle.kts`, and Koin composition.
- `translations/src/commonMain/composeResources/values/strings.xml`, tests, README
  supported-source/OPDS instructions, and provider QA evidence.

No Supabase schema, Storage bucket, or server-hosted EPUB changes are needed.

## 10. Review findings: gaps to resolve

Found by reading the current code on 2026-10-08; nothing was built or run.

### 10.0 Decisions recorded (2026-10-08, product owner)

These are decided. Where an item below still reads as a recommendation or an
alternative, this table wins.

| Item | Decision |
| --- | --- |
| §10.1 Downgrade safety | Catalogue sources are stored under their own preferences key, separate from `RegisteredServers`, so builds without OPDS never decode them. `ServerRegistry` merges both lists for callers. |
| §10.3 Logout-all | Removes catalogue account details. A catalogue that had account details also loses its unfinished downloads and saved pages, because they may hold private content; a catalogue without account details is left alone, running downloads included. Catalogues stay registered; acquired books, provenance, finished downloads and the import journal stay. The database cleaner then runs as for any profile and treats a catalogue book exactly like a file-picker import (Phase 3 status, sign-out run). |
| §10.5 Digest / protected Calibre over HTTP | Unsupported in the first release. Use the `unsupported` / `unsupportedBlocked` and `pwHttp` / `pwHttpBlocked` dialogs. |
| §10.9 Acquired books on other devices | **Reversed on 2026-10-08.** A catalogue-acquired book syncs its details and progress exactly like a file-picker import: finalization writes the same `library_book` outbox entry for `ORIGIN_CATALOGUE_DOWNLOAD` as for `ORIGIN_IMPORT`. Its file is still never uploaded automatically. Reason: holding the entry back required changing position, bookmark and highlight sync, and made catalogue books behave unlike imports. The earlier decision (no metadata sync until the file is backed up) no longer applies. |
| §10.10 Feed cache | Keep a small persisted cache of documents the user opened (within the §4 budgets), enough for the offline boards. Full HTTP-semantics caching beyond validators and `no-store` is not required in the first release. |
| §10.10 Auth documents | Ordinary `WWW-Authenticate` handling only; no auth-document discovery in the first release. Both OPDS parsers stay. |
| "Get updated copy" | Deferred to a later release. Do not build `detailUpdate`; do not store a change signal beyond the catalogue's `updated` value in provenance. |

Still requiring verification, not a decision: iPhone plain HTTP (§10.4), the
edition grouping rule and Coil `data:` thumbnails (Phase 0), and preset terms
and usage guidance (§2.3).

### 10.1 Downgrade can wipe the registered-server list

`ServerTypeSerializer.deserialize()` throws on an unknown identifier, and
`Preferences.getObject()` catches every exception and returns null. A build
without `ServerType.Opds` (rollback, or a device backup restored to an older
version) therefore loads **zero servers** for the profile, and the next
`persistServers()` overwrites the stored list.

Recommended: store OPDS sources under their own preferences key so older builds
never see them. Alternative: ship a tolerant per-element decoder one release
before OPDS. The new `ServerConfig.enabled` field is safe by itself because
`preferencesJson` ignores unknown keys.

### 10.2 Two auth-state systems without a mapping

§3.4 adds `ServerAccessState` beside `ServerAuthState`, but
`ServerManagementViewModel` and `BookDetailViewModel` read
`ServerRegistry.observeAllAuthStates()`. An OPDS server without
`ServerCredentials` is always `NotAuthenticated` there, so it renders as "Signed
out" with a "Sign in again" action that opens the login screen.

The same fact already keeps OPDS out of `observeAuthenticatedServers()` and
therefore out of the books/reader/series factories, so the §3.3 guards are
defence in depth, not the primary fix. Do not solve the UI problem by adding an
OPDS `ServerAuthStateProvider` that reports anonymous access as `Authenticated`:
that would pull OPDS into library aggregation and the progress sync adapters.

Decide and document which state each existing consumer reads for catalogue
sources, and filter or map OPDS entries explicitly in those view models.

### 10.3 Logout-all and data-clearing semantics

`LogoutUseCase.logoutAll()` and `SettingsDataRepository.logout(null)` clear
`ServerCredentials` for every non-Local server, then `logoutAll()` calls
`databaseCleaner.clearAllData()`. OPDS credentials live in a separate store and
would survive; whether the new acquisition/provenance tables are cleared is
unspecified.

Recommended: logout-all removes OPDS credentials and cancels active
acquisitions, keeps registered anonymous sources, and keeps acquired books and
their provenance. `CheckAuthStateUseCase` treats any non-Local server as
"configured"; confirm that an OPDS-only profile skipping onboarding is intended.

### 10.4 Plain HTTP on iOS

Android permits cleartext globally (`network_security_config.xml`). No App
Transport Security exception was found in `iosApp`, so a LAN catalogue such as
`http://192.168.1.10:8080/opds` is expected to work on Android and fail on iOS.
Verify on a device, then either add a scoped exception
(`NSAllowsLocalNetworking`) or state that iOS requires HTTPS and show that in
the add-server error.

### 10.5 Password-protected Calibre on a LAN is unsupported in v1

Calibre's default auth mode uses Digest when the server is not on HTTPS. With
Basic-only support and no Basic over HTTP, the most common protected self-hosted
setup cannot connect. This is a product decision, not a footnote: either accept
it and show a specific "this server uses Digest sign-in, not supported yet"
message, or move Digest into the first release.

### 10.6 Secrets in the feed URL path

Some servers (Kavita-style) put an API key in the URL **path**. §4 protects only
query strings, §6 shows the full editable feed URL, `ServerConfig.baseUrl` is
stored in ordinary preferences, and §5.4 snapshots a "provider/root identity"
into provenance.

Treat the whole feed URL as potentially secret: mask it in the UI by default,
keep it out of logs/analytics/route state, store the provenance root identity as
a display name plus origin, and decide whether the URL belongs in secure storage.

### 10.7 Cover loading has no mechanism

The app has one global Coil `ImageLoader`; `CoilInitializer.resolveTokenForUrl()`
picks the first registered server with a matching origin and attaches its bearer
token. §4 says not to use this for OPDS but names no replacement, and an OPDS
source sharing an origin with a Storyteller/Audiobookshelf server could receive
that server's token.

Specify a per-source image path (a dedicated loader or a custom fetcher keyed by
server id that goes through the OPDS transport) and exclude OPDS sources from
the global origin match. Verify bounded `data:` thumbnails through Coil.

**Resolved in Phase 4 (branch `opds/phase4-screens`).** The Phase 2 limitation,
where `CoilInitializer` suppressed bearer tokens for any origin shared with a
catalogue and so broke a library server's covers on that address, is gone.

- **Catalogue pictures** are requested with their own Coil model,
  `CatalogueImageModel(sourceId, url)` (`lib/server/api`), never with a bare
  address. `CatalogueImageFetcher` (`composeApp/.../initializer/CatalogueImages.kt`)
  finds the catalogue by id among the open profile's turned-on catalogues and
  asks its session (`CatalogueImageRepository.loadImage`, implemented by
  `OpdsCatalogueRepository`) for the bytes. The request goes through the OPDS
  transport, so the rules of a feed apply: Basic only on HTTPS hops on that
  catalogue's configured origin and never again after leaving it, nothing
  cross-origin, the same redirect and address checks, no cookies, and a 4 MiB
  ceiling (`CatalogueImageLimits.MAX_IMAGE_BYTES`). A turned-off, removed or
  other-profile catalogue id resolves to nothing and no request is made.
- **Only raster bytes are handed to a decoder:** PNG, JPEG, GIF and WebP,
  recognised by their first bytes, not by the catalogue's label. SVG and
  everything else is refused. An inline `data:` picture must be base64, be
  labelled as one of those four types, match its label, and decode to at most
  256 KiB (`MAX_INLINE_IMAGE_BYTES`); it is never requested.
- **Caching:** the bytes are not written to Coil's disk cache. The memory-cache
  key carries the profile id, the catalogue id and the catalogue's access
  generation, so a picture fetched with one account is not shown for another.
- **Library server covers** are back to the pre-catalogue rule: the global
  loader attaches the bearer token of the non-catalogue server whose scheme,
  host and port match exactly. Catalogues are not candidates and no longer
  suppress anything.

Tests: `CatalogueCoverLoadingTest` (the app's real graph), `OpdsCatalogueImageTest`,
`CatalogueImageBytesTest`, `CoverAuthCatalogueTest`.

**Rule for Phase 4 screens:** a catalogue cover is always a
`CatalogueImageModel`. Passing a catalogue's image address to Coil as a string
would send it through the global loader, which is wrong on a shared address.

### 10.8 Re-acquisition creates duplicate books

Deduplication is by byte hash, and providers such as Gutenberg regenerate EPUB
files. Without a lookup in `catalogue_book_sources` by source + publication key
before download, the same title lands twice. Add that lookup, use it to drive an
"In your library" state while browsing (§11), and make a second download of a
changed file an explicit "get updated copy" choice.

### 10.9 Acquired books on other devices

The import path writes metadata outbox entries, but this plan uploads no file
and syncs no provenance. A second device then shows a book it cannot open or
re-fetch. Decide between: not syncing metadata for catalogue acquisitions until
the user backs the file up, or syncing minimal provenance so the other device
can offer "Download again from <source>".

**Decided, then reversed (2026-10-08, product owner).** The first decision was
to hold the metadata outbox entry back until the file was backed up. It is
reversed: a catalogue book syncs its details and progress exactly like a
file-picker import, and its file is still never uploaded automatically. The
hold-back needed changes to position, bookmark and highlight sync (those write
to the outbox through their own paths) and made catalogue books behave unlike
imports. A second device therefore lists the book the way it lists an import
whose file was never backed up. Provenance is still not synced. Whether a
catalogue book's file can be backed up at all is open (§11.7 "Still open").

### 10.10 First-release scope

The first release currently bundles both OPDS versions, auth-document discovery,
RFC 6570, an HTTP-semantics disk cache, and a finalization journal, with no
estimates. Recommended cuts: in-memory feed cache only, and ordinary
`WWW-Authenticate` handling without auth-document discovery. Keep both parsers,
since Gutenberg expects to drop XML feeds in 2027.

### 10.11 Smaller items

- `ServerRegistry.deactivateServer()` currently just clears credentials; the new
  `enabled` flag changes its meaning. Audit callers.
- `ServerRegistryImpl.updateServer()` and `removeServer()` do not use
  `persistStateMutation`; URL edits need the same rollback as adds.
- `ServerType.displayName` is a hard-coded English string used directly in UI;
  the OPDS label must come from translations (§11.1).
- The 20-parsed-pages budget needs defined behavior at the limit (evict earliest
  pages and refetch on scroll back, or stop with a message).
- No OS background execution means an iOS download stops shortly after the app
  is backgrounded; recovery restarts from zero. The UI must account for this.

## 11. Designer brief

Today every server screen assumes "a place you sign in to that holds your
books". A catalogue is different: often no account, nothing in the library until
the user downloads something, and content the app does not control. The items
below are what design needs to deliver.

### 11.1 Naming

Choose the user-facing word for an OPDS source (for example "Book catalogue"),
with "OPDS" as secondary text for people who look for it. It appears in the
add-server picker, server list, server detail, and errors.

### 11.2 Existing screens that need changes

| Screen | Today | Needed |
| --- | --- | --- |
| Add server (`LoginScreen`) | Two fixed cards (Storyteller, Audiobookshelf), then address + username + password, primary action "Sign in". | Third option for catalogues; feed URL field; credentials hidden until chosen or required; primary action "Add"/"Connect"; a preset list with name, description, access note, terms link. |
| Server list (`ServerManagementScreen`) | Status is connected / signed out / can't sign in, with "Sign in again". | Catalogue statuses: public (no account), account, sign-in required, sign-in method not supported, disabled, last checked / last error. A Browse action. |
| Server detail (`ServerDetailScreen`) | "Account" section: "Signed in as / Not signed in", "Sign out…", "Sign in". | No sign-out for public sources; "Add credentials" / "Edit credentials"; Disable and Re-enable; Browse; Remove copy that says downloaded books stay. |
| Address display and edit | Short host only (`books.example.com`); dialogs use the host. | Show and edit a long URL with path; two catalogues on one host must be distinguishable; mask URLs that may contain a key (§10.6). |
| Book detail | Home chips: This device / Parrot Cloud / Storyteller / Audiobookshelf. | Decide whether an acquired book shows "From <source>" with rights/attribution. It is otherwise an ordinary device book. |

### 11.3 New screens

1. **Entry point.** Where Browse lives: from the library's add action, from
   server management, or both. No new bottom tab is planned.
2. **Catalogue sources list.** Registered catalogues plus presets not yet added.
3. **Catalogue browser.** One screen that can show any mix of: folders
   (navigation), book lists, grouped shelves, filter facets, and a search field
   that exists only when the catalogue offers one. No totals or page numbers are
   available, only "next". Choose infinite scroll or a "Load more" button, with
   an e-ink-friendly variant.
4. **Publication detail.** Cover, authors, language, description, rights,
   source. Descriptions arrive as HTML and the app has no HTML renderer: choose
   plain text or limited rich text.
5. **Format/edition picker.** One book can list several editions, each with
   several EPUB files, labelled only by what the catalogue provides (sometimes
   poorly). Needs a default choice and a readable list.
6. **Downloads.** A place to see active and failed downloads after leaving the
   detail screen.
7. **Credential sheet.** Username + password, shown when a catalogue asks for
   sign-in mid-browse; password may legitimately be empty.

### 11.4 States to design

- Browser: first load, loading next page, next page failed (not the same as
  empty), empty catalogue, no search results, showing saved copy while offline,
  sign-in required, rate-limited (try again later).
- Book in a list: available, "In your library", downloading.
- Download: queued, downloading with known size, downloading with unknown size
  (no percentage), checking file, adding to library, failed with retry, sign-in
  required, cancelled, done with Open.
- Not downloadable here: buy, borrow, sample only, unsupported format, protected
  file. Show why, and offer "Open provider page" where one exists.
- Already acquired but the catalogue has a changed file: "Get updated copy" as
  an explicit choice.

### 11.5 Dialogs and messages

- Adding a plain-HTTP catalogue: warning before continuing.
- Password over plain HTTP: blocked, with the reason.
- Certificate not trusted.
- Catalogue links to a device on the local network: confirm before following.
- Sign-in method not supported (Digest, OAuth).
- Add-server failures, each distinct: not a catalogue (web page), address
  unreachable, malformed catalogue, sign-in required, HTTPS required on iOS (if
  §10.4 lands that way).
- File too large; not enough storage; file is not a valid book.
- Download interrupted because the app was closed or backgrounded, with resume.
- Remove catalogue: downloaded books are kept.

### 11.6 Constraints design should know

- Content comes from third parties: titles, covers, and labels can be missing,
  long, or low quality. Layouts need fallbacks.
- "Free to download" is not "public domain". Avoid copy that promises either
  unless the catalogue states it.
- Standard Ebooks needs a patron account; it must not be presented as a free
  open catalogue.
- Use existing Ember components, accessibility semantics, and e-ink behavior.

### 11.7 Design passes 1–3 (received 2026-10-08)

Source: `design/ember/catalogues/CATALOGUE_PROMPT.md` (one file covering all
three passes; its §9 is pass 3 and its §10 the "Can't be downloaded here"
copy) with 84 boards in
`design/ember/catalogues/screens/opds-<view>-<theme>.png`. That file holds the
exact copy and accessibility labels; use them verbatim, do not paraphrase them
in code. This section records the decisions and how they bind the
implementation. §11.1–11.6 remain the behavior reference; where the design
decides something, the design wins.

**Boards delivered**

| Area | Boards (`opds-…`) |
| --- | --- |
| Add catalogue | `add`, `addError`, `addInvalid`, `addUnreachable`, `addSignin` |
| Add dialogs | `http`, `httpIos`, `pwHttp`, `pwHttpBlocked`, `cert`, `unsupported`, `unsupportedBlocked` |
| Get books | `catalogues`, `cataloguesFew`, `cataloguesEmpty`, `preset` |
| Catalogue root | `browse`, `browsePlain`, `firstLoad`, `emptyFolder`, `limited`, `offline`, `offlineNone`, `localNet` |
| Lists and search | `list`, `listStates`, `listFailed`, `search`, `noResults`, `filter` |
| Book page | `detail`, `detailWait`, `detailDl`, `detailUnknown`, `detailIos`, `detailAdding`, `detailDone`, `detailUpdate`, `blocked` |
| Choose a file | `editions`, `editionsGrouped`, `editionsList` (ungrouped fallback) |
| Downloads | `downloads`, `downloadsFailed` |
| Libraries (server management) | `servers`, `serversMore`, `serverPublic`, `serverAccount`, `serverOff`, `serverUnsupported`, `editAddress`, `removeCat`, `signOutAll` |
| Sign-in sheet | `signin` |

Day exists for every view; Night and E-ink for the main ones.

**Decisions**

- **Name:** "Book catalogue", secondary text "OPDS". Never "server" for a
  catalogue. Resolves §11.1.
- **Entry points:** library add action → "Get books"; each catalogue in
  Libraries has Browse. No new tab.
- **Add:** third kind on "Add a library": one "Catalogue address" field and a
  "Needs an account" switch (off). A 401 on add turns the switch on and shows
  the fields. Field errors are distinct for web page, unreachable, invalid, and
  sign-in needed.
- **Presets** on Get books, from a data file, any number. Boards show Gutenberg
  and Standard Ebooks ("Patron account needed") only; a preset detail screen
  carries description, host, terms link, a "Parrot isn't part of <Name>" note,
  and Add. An account preset opens the sign-in sheet before adding.
- **Addresses in rows:** host only on Get books and Libraries, unless two
  catalogues share a host: then host + path with key-looking segments masked,
  then port, never the query string. The full address
  appears only in catalogue settings, with key-looking parts masked behind
  Show/Hide; the edit dialog shows it unmasked.
- **Paging:** Day/Night auto-load near the end; E-ink uses "Load more" in lists
  and search results. A failed page keeps loaded books and shows an inline
  error row.
- **Catalogue root:** shelf, "See all", filter chips, search field, and folder
  subtitles each render only when the catalogue supplies them
  (`browsePlain` is the minimum).
- **Search:** field becomes the top bar; results are normal book rows.
- **Filter picker:** bottom sheet per facet group, single choice, applies on
  tap, catalogue counts when given, a local search field above 12 options.
- **Descriptions:** limited rich text; links become plain text.
- **Choose a file:** titles are the catalogue's labels, unclamped; no label →
  "EPUB file 1", "EPUB file 2" in catalogue order. The app adds only size (or
  "Size unknown"), "Best" on the default, and "Can't be opened in Parrot".
  Several editions are grouped under the edition label (fallback "Edition 1").
- **Ungrouped same-title entries** (`editionsList`): when entries with the same
  title and author cannot be grouped, show them as rows in catalogue order
  with a header line and a "telling line" (edition label · year · file count).
  Same-title siblings in ordinary lists and search results get the telling
  line too. Never hide an entry.
- **Catalogue that turns unsupported:** Libraries row with Details, which opens
  settings without Browse (`serverUnsupported`); Get books opens the same
  screen.
- **Offline:** banner over the saved copy when one exists; `offlineNone`
  message screen with Try again when the page was never opened.
- **Default file:** the first file in catalogue order that Parrot can open.
  This supersedes pass 1's "prefer EPUB 3 / with images" and removes the
  conflict with §5.1.
- **Row download:** tapping it shows "Getting ready…" (cancellable) while that
  one publication loads; several editions or nothing openable opens the book
  page silently instead.
- **Download states** in rows, book page, and Downloads: getting ready,
  waiting, known size, unknown size (no bar), adding, done. At most two run;
  the rest wait in order. Cancelled downloads disappear. Finished ones stay on
  Downloads until the user leaves it or for 24 hours.
- **Failure reasons**, each with fixed copy and action: connection (Retry), too
  large (Dismiss), storage (Retry), invalid (Dismiss), protected (Dismiss),
  refused (Retry), interrupted (Start again), sign-in (Sign in).
- **iOS:** the downloading card says to keep Parrot open; an interrupted
  download shows "Stopped when Parrot closed" with "Start again" (it restarts
  from zero; "Resume" only if true resume ships). If iOS blocks plain HTTP,
  the single-button `httpIos` dialog applies, worded with the device helper;
  if it does not, iOS uses the Android dialog unchanged.
- **Libraries row statuses:** public ("Ready · no account needed"), signed in,
  sign-in needed, sign-in method not supported, turned off, last error; each
  with its own action. "Checked <time> ago" shows on healthy rows only when
  the last check was within 7 days; error rows always keep their time. A
  public catalogue never shows "Signed out".
- **Catalogue settings:** Address, Account (add/edit/remove details), a "Use
  this catalogue" switch, Remove. Remove states how many downloaded books stay.
- **Dialogs:** HTTP warning, password over HTTP, untrusted certificate (no way
  to continue), unsupported sign-in. The last two have a blocked variant with
  no add action, used when the catalogue's first page itself needs the account
  (`pwHttpBlocked`, `unsupportedBlocked`). Also local-network link, sign-out-of-everything
  detail line, rate limited.
- **Get updated copy (deferred, §10.0; designed but not built now):** a note under Read now when the catalogue changed the
  book; the existing copy keeps its progress and the new file is added next to
  it.
- **"Can't be downloaded here" card** (prompt §10): shown only when no file
  can be downloaded. Six reasons with fixed title/body: sold, subscription,
  borrow, sample only, format, protected. "Open provider page" appears only
  for sold, subscription, borrow, and sample, and only when the entry has a
  web link; never for format or protected. With several reasons, show the
  first of: sample only, sold, subscription, borrow, protected, format.
  `<Provider>` is the entry's seller/lender name when given, otherwise the
  catalogue name. Format names: PDF, MOBI, Kindle (AZW3), audiobook, else the
  catalogue's type label.
- **Rights:** the catalogue's rights text, then the app's own line "Check the
  law where you live before sharing.", shown only when the catalogue gives
  rights text.
- **Wording:** device name through the shared helper (fallback "this device");
  never "free" or "public domain" unless the catalogue states it.
- **Design deliverables:** ViewModel tests for paging, the two-at-a-time queue
  and waiting order, cancel at each state, default file and fallback labels,
  key masking, failure reason → copy, and catalogue status → Libraries row.
  Fixtures in `tools/ember-fixtures` for every board, captured to
  `design/screens/catalogue-<view>-<theme>.png` in Day and E-ink.

**§10 items the design settles**

- §10.3: sign-out-of-everything removes catalogue account details and keeps
  account-free catalogues, as recommended.
- §10.5: Digest and other unsupported sign-in are out of v1, with a dedicated
  dialog and Libraries status.
- §10.6: UI masking is defined. Logging, route state, storage, and provenance
  rules in §10.6 still apply.
- §10.8: "In your library" in lists. The explicit "Get updated copy" is
  designed but deferred (§10.0).
- §10.11: iOS backgrounding is handled in copy and an interrupted state.
- Pass 1 conflicts on default file, list-row download, presets, the rights
  line, the root layout, and row addresses are resolved as above.

**What the design now requires from engineering (add to the phases)**

1. **Acquisition states (§5.2).** Add `waiting` (FIFO, persisted order) and
   `interrupted`. On app restart, non-terminal downloads become `interrupted`
   and wait for the user's "Start again" instead of restarting by themselves. Cancel
   deletes the request row. "Getting ready" is view-model state, not persisted.
2. **Error categories (§5.4).** The bounded error category is exactly the eight
   failure reasons above, plus the size and limit values the copy needs.
3. **Completed-row retention.** Keep completed requests with a completion time
   for the 24-hour rule; purge on leaving Downloads or on expiry.
4. **Per-source status (§3.4).** Persist last successful check time, last error
   and its time, and the access state behind each Libraries status. Statuses
   reflect the last real request; no background polling to refresh them.
5. **Provenance (§5.4).** Store the listing/detail identity as well as the
   publication key (for "In your library" in lists), the catalogue's `updated`
   value at acquisition (for "Get updated copy"), and support a per-source
   count of acquired books (for the Remove dialog).
6. **Key masking.** One tested function decides which URL parts are masked
   (named query keys, or a 20+ character random-looking path/query value), used
   by settings and by log/analytics redaction.
7. **Credentials stay on the device.** "Saved on this phone only" requires
   excluding OPDS credentials from Parrot Cloud sync and OS device backup, with
   a test.
8. **Facets and search (§3.2).** The model must expose facet group name,
   options, active option, optional counts, and the "all" option.

**Still open (verification only; decisions are in §10.0)**

1. **Grouping rule for "one book".** `editionsGrouped` applies when entries can
   be grouped into editions, `editionsList` when they cannot. Rule to confirm
   in the Phase 0 spike: a listing entry without acquisition links whose target
   is an unpaginated acquisition feed whose entries share one title is a single
   book with editions; anything else is a list, with the telling line on
   same-title siblings. A wrong guess degrades to `editionsList`.
2. **iPhone HTTP (§10.4).** Verify on a device, including whether existing
   Storyteller and Audiobookshelf servers work over http on iOS today. Blocked
   → `httpIos`; allowed → the Android dialog.
3. **Preset terms links.** Each preset needs a verified terms URL and access
   note before it ships (§2.3 limits still apply to Gutenberg).

Decided: the offline boards ship on a small persisted cache, and "Get updated
copy" (`detailUpdate`) is deferred. Item 4 below is a product decision.

4. **"Download sample" (prompt §10).** The sample-only card offers "Download
   sample · <size>" when the sample is an openable file. §5.1 says a sample is
   never imported as the complete book, and nothing in the library design marks
   a book as a sample. Recommended for the first release: no sample download;
   show the body with "Open provider page" only. Shipping it later needs a
   "Sample" marker in the library and book details, and a rule for what
   happens when the full book is acquired.

5. **Backing up a catalogue book's file.** `backupAll` and the backup banner
   and sheet leave catalogue-origin files out, and that is unchanged and
   tested. Whether the user can back one up explicitly, and with what rights
   wording, is not designed. Its details and progress now sync like an
   import's (§10.0), so the book can have a remote revision and
   `enqueueUpload` no longer refuses it for lacking one; nothing in the UI
   offers it.

**Engineering notes from the "Can't be downloaded here" copy**

- The classifier needs a separate `SUBSCRIPTION` reason (Phase 1 mapped
  subscriptions to sold), the reason priority order above, the provider name
  when the entry supplies one, and the media type of the unsupported file so
  the UI can name the format.

**Engineering notes from pass 3**

- "Shows nothing without an account" means the catalogue's root document
  itself answers 401. That one check selects the blocked variants of the
  unsupported-sign-in and password-over-HTTP dialogs.
- Row address disambiguation uses the same masking function as settings
  (item 6 above) and needs its own tests: shared host, shared host and path,
  key-looking segment, port fallback.
- The telling line needs edition label, year, and acquisition-link count per
  entry in the catalogue model (§3.2).

## 12. Sources and verification limits

Primary references checked on 2026-10-08:

1. [OPDS 1.2](https://specs.opds.io/opds-1.2).
2. [OPDS 2.0 living standard](https://specs.opds.io/opds-2.0).
3. [Authentication for OPDS 1.0 draft](https://drafts.opds.io/authentication-for-opds-1.0).
4. [RFC 6570 URI Templates](https://www.rfc-editor.org/rfc/rfc6570).
5. [OpenSearch 1.1 reference](https://github.com/dewitt/opensearch/blob/master/opensearch-1-1-draft-6.md)
   (linked by OPDS1; implementation should use its parameter rules/fixtures).
6. [Gutenberg catalogue/feed documentation](https://www.gutenberg.org/ebooks/offline_catalogs.html).
7. [Gutenberg robot policy](https://www.gutenberg.org/policy/robot_access.html)
   (checked earlier in this research conversation).
8. Live Gutenberg [listing](https://www.gutenberg.org/ebooks/search.opds/),
   [publication feed](https://www.gutenberg.org/ebooks/1342.opds), and
   [OpenSearch descriptor](https://www.gutenberg.org/catalog/osd-books.xml).
9. [Standard Ebooks feed documentation](https://standardebooks.org/feeds).
10. [Calibre Content server documentation](https://manual.calibre-ebook.com/server.html).
11. [Readium Kotlin OPDS README](https://github.com/readium/kotlin-toolkit/blob/develop/readium/opds/README.md)
    and [module build configuration](https://github.com/readium/kotlin-toolkit/blob/develop/readium/opds/build.gradle.kts)
    (develop branch references, not an assertion about the app's pinned release).

No EPUB payload was downloaded/validated in this task, no authenticated provider
account was used, no provider contact was made, and no live OPDS2 provider feed
was verified. Protocol support/production readiness must be established by the
implementation gates above, not inferred from a successful metadata/HEAD request.
