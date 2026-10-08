# OPDS server support: research and implementation plan

**Status:** approved for implementation, starting at Phase 0; no application
implementation changes made yet. Reviewed
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
7. Transport rules with MockEngine: no credentials cross-origin or on any
   redirect hop, same host with different scheme/port, redirect limit, size and
   nesting limits, DTD/entity rejection beyond what the library does (reject
   any document containing a DOCTYPE at the DOCDECL event; xmlutil expands
   internal entities by default).

**Gate:** equivalent protocol behavior on both platforms; no provider-specific
logic required for synthetic/custom catalogues.

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

### Phase 3 — durable acquisition and local-library integration

- Add acquisition/provenance SQLDelight APIs, schemas/migrations/DAOs and cleanup.
  Choose the next migration number at implementation time (current chain includes
  `38.sqm`); do not rewrite existing user data or assume a database reset.
- Implement bounded streaming, cancellation/recovery, fresh-link resolution,
  validation/hash, finalization journal, reusable staged import, and provenance.
- Audit local/cloud codecs, origin handling, file backup, library merge, device
  deletion, server removal, and profile deletion.
- Add the publication-key lookup that prevents re-downloading a book already
  acquired (§10.8), and hold back metadata sync for acquired books until file
  backup (§10.0).

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
| §10.3 Logout-all | Removes catalogue account details and cancels active acquisitions; keeps account-free catalogues, acquired books, and provenance. |
| §10.5 Digest / protected Calibre over HTTP | Unsupported in the first release. Use the `unsupported` / `unsupportedBlocked` and `pwHttp` / `pwHttpBlocked` dialogs. |
| §10.9 Acquired books on other devices | No metadata sync for a catalogue-acquired book until the user backs its file up. The finalization path must not write a metadata outbox entry for `ORIGIN_CATALOGUE_DOWNLOAD` before then. |
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
