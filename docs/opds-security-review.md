# Book catalogues (OPDS): security review

Done in the Phase 5 hardening run, 2026-10-09, branch `opds/phase4-screens`, by reading the
code as a reviewer and writing a test for every finding that was fixed. No device or emulator
was used. "Account details" means the user name and password of a catalogue.

**Result in one paragraph.** Account details go to the catalogue's own https scheme, host and
port and nowhere else; that held for every path that was read and is now tested for each one.
Three security faults were found and fixed: a catalogue on the internet could make Parrot send
requests to devices on the user's own network without asking; a download that sent more bytes
than it declared was written to disk in full before it was refused; and a list could be made to
ask for pages without end. Two things are reported and not fixed: saved catalogue pages and
download rows live in the profile database, which Android includes in device backup, and on
iPhone the account details are stored in the keychain without the "this device only" setting.
Neither is new to this run. Several things could not be verified without a device; they are
listed at the end.

## What was read

| Area | Files |
|---|---|
| Transport | `lib/opds/implementation/.../transport/KtorOpdsTransport.kt`, `lib/opds/api/.../OpdsTransport.kt` |
| Session and its requests | `lib/server-opds/.../OpdsCatalogueRepository.kt`, `OpdsCatalogueRepositoryFactory.kt` |
| Credential store | `lib/server/implementation/.../OpdsCredentialStoreImpl.kt`, `ServerRegistryImpl.kt`, `CatalogueAccessStoreImpl.kt`; `lib/preferences/implementation` (both platforms) |
| Image loader | `composeApp/.../initializer/CatalogueImages.kt`, `CoilInitializer.kt`; `lib/server/api/.../CatalogueImages.kt` |
| Download path | `feature/catalogue/data/.../CatalogueAcquisitionQueue.kt`, `AcquisitionWorker.kt`, `AcquisitionFileSource.kt`, `CatalogueStagingFiles.kt`, `AndroidCatalogueStagingFiles.kt`, `LibraryCatalogueBookAdder.kt`; `lib/epub/implementation/.../check` |
| Saved-pages cache | `lib/server-opds/.../SavedPagesFeedCache.kt`; `lib/opds/implementation/.../cache` |
| Address masking | `lib/server/api/.../CatalogueAddress.kt`, `CatalogueLocalNetwork.kt` |
| Screens' state holders | `feature/catalogue/ui/.../browse/CatalogueBrowser.kt`, `CatalogueBrowseGateway.kt`, `add/CatalogueAddFlow.kt`, `add/CatalogueAddRuntime.kt`, `settings/CatalogueSettingsViewModel.kt` |
| Platform settings | `androidApp/src/main/res/xml/data_extraction_rules.xml`, `full_backup_content.xml`, `AndroidManifest.xml`; `iosApp/iosApp/Info.plist` |

## 1. Can account details reach any host other than the catalogue's own https origin?

**No.** One rule in the transport decides it: the `Authorization` header is added only when
the request's scheme, host and port equal the catalogue's, the scheme is https, and the
request has not left that origin at any earlier redirect. No cookies are kept, no auth plugin
is installed, and each catalogue has its own HTTP client. A link that carries a user name or
password in its address is refused before any request.

| Way out | Answer | Test |
|---|---|---|
| Redirect | The header is dropped when a redirect leaves the origin and is not put back if a later redirect returns. Another port, a subdomain and a look-alike host are other origins. https to http is refused. | `OpdsTransportTest › credentials_are_not_reattached_after_return_to_origin`, `…redirect_to_another_origin_drops_credentials`; `TransportFailureStressTest › a_download_redirected_off_the_catalogue…`, `…a_redirect_to_the_same_host_on_another_port_or_over_http…` (the last two new) |
| Image | A picture is asked for through its catalogue's own session, under the same rule. It never goes through the global image client that holds library servers' bearer tokens. | `OpdsCatalogueImageTest` (9), `CatalogueCoverLoadingTest` (14), `CatalogueImageLoaderSafetyTest` |
| Download link | The file is asked for under the same rule. The listing is fetched with the details, the file on another host without. | `OpdsAcquisitionDownloadTest › account_details_reach_the_catalogue_but_not_a_file_host_elsewhere`; `CatalogueAccountReachTest › a_file_on_another_host…` (new); `CatalogueSignedInEndToEndTest` step 8 (new) |
| Search template | A template on another host gets the search text and no details. The same for a search description on another host and for the template it names, and for a preset's own search address. | `CatalogueAccountReachTest` (4 tests, new) |
| Link in a feed | Another host, another port and a look-alike host get nothing. | `CatalogueAccountReachTest › a_link_in_a_feed_to_another_host_port_or_lookalike_host_gets_nothing` (new) |
| After an address edit | A change of scheme, host or port deletes the saved details before the new address is stored; a change of path keeps them. Running requests and downloads are stopped first. | `OpdsCredentialStoreTest › path_edit_keeps_password_but_origin_edits_clear_it…`; `CatalogueDownloadCancellationTest › moving the catalogue to another address cancels its running download` |
| Plain http | A password is never sent over http: not to the catalogue, not on a redirect. | `OpdsTransportTest › password_over_http_has_distinct_result…`; `CatalogueAccountReachTest › a_catalogue_over_http_never_sends_account_details_to_anything` (new) |

Fixed here: a preset's search address stayed attached when the preset's address was edited to
another server, so search text typed for the new catalogue would have gone to the old host
(without account details). The registry now clears the search address and the "list entries
are books" hint when the scheme, host or port changes. Test: `CataloguePresetHintsTest`.

Worth knowing, not a fault: a catalogue chooses where its search goes. If it names a search
address on a third host, the words the user types go there. That is how OpenSearch works.

## 2. Can anything from another profile or another catalogue be read?

**No path was found.**

| What | How it is kept apart | Test |
|---|---|---|
| Credentials | Stored per profile and per catalogue id. Read only for the open profile. Deleted with the profile. | `OpdsCredentialStoreTest › credentials_are_typed_profile_and_source_scoped…`; `UserRegistryDeleteProfileTest › deleting_profile_removes_catalogue_sources_accounts_and_status_only_for_that_profile` |
| Saved pages | Each profile has its own database. A page is found only under the catalogue and the "access generation" it was saved under; the generation changes on every change of account details and is never reused. Only the open profile is read or written. | `SavedPagesFeedCacheTest` (10), `OpdsSavedPagesTest` (10) |
| Cached pictures | Kept in memory only, never on disk. The key holds the profile, the catalogue, the access generation and the address. | `CatalogueCoverLoadingTest › the cache key of a picture belongs to one profile, one catalogue and one set of account details`, `…another profile's catalogue is never used` |
| Queue rows | In the profile's own database, written only inside `withProfile`, which refuses a profile that is not open. A profile switch stops its downloads first. | `AcquisitionQueueFencingTest` (9), `AcquisitionRaceMatrixTest` (6, new) |
| Staged files | One folder per profile with generated file names. A deleted profile's folder is removed at start. | `CatalogueStagingFilesTest › the files of one profile are listed and the other profile's are not`; `CatalogueAcquisitionStartupTest`; `CatalogueCleanupRulesTest › a deleted profile's staging folder is removed` |
| Places in a catalogue | A reference to a page is refused by another catalogue, another profile's catalogue, and the same catalogue after its address changed. | `OpdsCatalogueRepositoryTest › uri_template_search_expands_before_resolution_and_foreign_targets_are_rejected` |

A decoded picture stays in the image loader's memory until it is evicted, also after a profile
switch. Its key belongs to the old profile, so nothing shows it.

## 3. Can a hostile feed or file cause unbounded memory, CPU or disk use, write outside the staging and library directories, or get a non-book file into the library?

**Memory.** A page is read to at most 5 MiB and the read stops there; a picture to 4 MiB; an
inline picture to 256 KiB, checked on the text before anything is decoded; a description to
100,000 characters. A page has at most 2,000 entries, 64 levels of nesting, 50 pictures and
500 links per entry. A book file is streamed to disk in 64 KiB pieces and never held in
memory. At most 20 pages of one list are kept. New tests put each limit at its value and one
past it: `FeedLimitsStressTest`, `CatalogueInlineThumbnailStressTest`,
`TransportFailureStressTest › an_endless_page…`, `…an_endless_file…`.

**CPU.** XML with a DOCTYPE is refused before any entity is expanded. Nesting is counted
without recursion and tested at depth 100,000. The description cleaner is not recursive.
The file check refuses an archive with more entries than its limit, or one that claims to
unpack past its size limit. The patterns that were read (the `WWW-Authenticate` reader, the
charset reader, the address masking) have no nested repetition.

**Disk.** One file is at most 512 MiB, two download at a time, and free space is checked
before the download, when the size is known, and every 4 MiB while writing. Saved pages are
at most 25 MiB per profile. Staging files that no request refers to are deleted at start.

**Fixed here: endless paging.** A "next" link that pointed at its own page, or back at an
earlier one, was followed for as long as the list was near its end, one request after another.
The list now ends at such a link, and after three pages in a row that bring no books it stops
loading by itself and waits for a tap. Test: `CataloguePagingLoopTest` (6).

**Fixed here: more bytes than declared.** A file that sent more than its `Content-Length`
was written to disk up to the 512 MiB ceiling and only then refused. It is now stopped at the
declared length and none of the surplus is written. Test:
`TransportFailureStressTest › more_bytes_than_declared…`.

**Fixed here: requests to the local network nobody asked for.** Pages ask before following a
link to a device on the local network. Pictures did not, and a list asks for its pictures by
itself. A catalogue on the internet could therefore make Parrot send a GET request to any
address on the user's network (a router, a printer) just by listing it as a cover; the same
for a search description and for the file behind a download link. These three now refuse a
local-network address, directly or through a redirect, before the device is contacted, when
the catalogue itself is not on the local network. A catalogue that is on the local network
keeps working as before. Test: `CatalogueUnaskedLocalNetworkTest` (5).

**Fixed here: a missing host became `localhost`.** The URL parser fills in `localhost` when an
address has no host, so `https://` was asked as `https://localhost/`. Such an address, and one
with an unclosed bracket, is now refused before any request. Tests:
`TransportFailureStressTest › an_address_with_no_host…`, `CatalogueAddressValidationTest`.

**Writing outside the staging and library directories.** Not possible from a feed. A staging
file's name is a generated UUID under the profile's folder; the profile id is passed through
`safeFileName()`. Nothing a catalogue sends (a title, a file name, a `Content-Disposition`
header) reaches a path. The library file's name is built from the library's own book id.
Test: `AcquisitionQueuePipelineTest › the staging name is generated and nothing from the catalogue reaches the path`.

**A non-book file in the library.** A file is added only after Parrot's own check of its
bytes: a ZIP whose first entry is `mimetype` saying EPUB, with a container that points at a
package document that exists, no entry that would escape the book folder, no entry count or
declared size over the limits, not encrypted. The server's content type and the link's file
extension play no part. Tests: `EpubFileCheckTest` (28), `EpubFileCheckDiskTest`,
`AcquisitionQueuePipelineTest › the file is checked by its bytes before anything is handed on`.

A picture is handed to the decoder only when its first bytes are PNG, JPEG, GIF or WebP. SVG
and HTML are refused whatever they are labelled. Test: `CatalogueImageBytesTest` (7).

## 4. Do logs, analytics, crash breadcrumbs, route arguments or saved state contain an address, a query string, search text, a book title, a user name or a password?

Every log, analytics and breadcrumb call in the files this work touched was listed (search
for `Logger`, `logger.`, `log(`, `println`, `analytics`, `logEvent`, `logBreadcrumb`,
`logException`, `Napier`, `Kermit` over the 185 production files changed on this branch) and
read one by one.

| Where | What it writes | Verdict |
|---|---|---|
| `KtorOpdsTransport` | Four fixed words: `opds.request`, `opds.complete`, `opds.redirect`, `opds.failure`. Nothing in the app passes it a logger, so today they go nowhere. | Safe. Test: `TransportSafetyTest › logs_never_include_credentials_paths_queries_or_search_text` |
| `feature/catalogue/*`, `lib/server-opds`, `lib/opds/*` | Nothing. There is no log or analytics call in these modules. | Safe |
| `ServerManagementViewModel` | Events and breadcrumbs with the server type and fixed words. Exceptions from updating a server are reported with fixed context. | Safe. The registry's own exceptions carry fixed messages. |
| `HomeNavigation` | Screen names for navigation analytics. The four catalogue routes map to fixed words. | Safe. Test: `CatalogueDestinationTest › analytics_names_are_fixed_words` |
| `UserRegistryImpl`, `ServerRegistryImpl` | Fixed sentences; a count of profiles. | Safe |
| Route arguments and saved state | A catalogue id and an in-memory reference (letters, digits, `-`, `_`, at most 64). An address or account details cannot be put in a route: the constructor refuses them, and its message does not repeat the value. | Safe. Tests: `CatalogueDestinationTest` (6), `CatalogueSettingsRouteTest` (3), `CatalogueRouteReferencesTest` (5) |
| What objects print | `OpdsAccountDetails`, `CatalogueImageModel`, `CataloguePlace`, `CatalogueBrowseSource`, the page and search references all print "redacted". | Safe. Test: `CatalogueAccountReachTest › account_details_never_appear_in_what_the_session_or_its_models_print` (new) |
| Transport errors | A code, a status and a number. Never an address or a message from the network library. | Safe. Tests: `TransportStatusTest › darwin_error_codes_are_classified_without_echoing_error_details`, `UriTemplateTest › error_messages_do_not_echo_private_templates` |

**`redactAddress()`.** It exists and is tested (`CatalogueAddressTest`), and nothing calls it,
because no catalogue code logs an address. It was left in place for the day one does. There
was no place to wire it in.

**Fixed here.** The one-page check behind "Add by address" built its transport outside its
error handling. An address the URL parser throws on would have escaped as an exception whose
message contains the address. The add screen catches it and reports nothing, so nothing
leaked, but the next caller might not. The check now answers "can't reach" itself.
Test: `CatalogueAddressValidationTest`.

**Stored on the device, by design.** These are not logs, but they hold what the question lists:

- The profile database holds, for each download, the book's title and author, the cover
  address, and until the download finishes the address of the page it was listed on.
- Saved pages are stored under the address they were asked at. A search page's address
  contains the search text. At most 25 MiB, oldest first out, cleared when the catalogue is
  turned off, moved, removed or its account changes.
- A book's provenance holds the catalogue's scheme, host and port only.

## 5. Are account details excluded from device backup and from Parrot Cloud sync?

| | Android | iPhone |
|---|---|---|
| Where they are stored | `SecureSettings.xml`, an encrypted preferences file keyed to the device's keystore | The keychain, service `SecureSettings` |
| Device backup | Excluded. `data_extraction_rules.xml` (Android 12 and later) excludes the file from cloud backup and device transfer; `full_backup_content.xml` does for Android 11 and earlier. A restored copy could not be read anyway: the key stays on the old device. | **Not excluded by Parrot.** The keychain item is created with the library's default settings: not synced through iCloud Keychain, but not marked "this device only", so it is included in an encrypted backup and can move to a new iPhone with it. |
| Parrot Cloud sync | Not synced. No code in `feature/sync`, `lib/cloud`, `lib/server-parrot-cloud` or `feature/cloud-account` refers to catalogues, catalogue accounts or provenance. | The same; the code is shared. |
| Staged downloads | In `noBackupFilesDir`, left out of backup by the system | In Application Support, marked "do not back up". Test: `IosCatalogueStagingLocationTest` |

Found, not fixed:

1. **iPhone keychain.** The same store holds the library servers' tokens, so this is how the
   app already behaved. Changing it means changing how every secret is stored and cannot be
   checked without a device. For the release checklist.
2. **The profile database is in Android's device backup.** It holds the saved catalogue pages
   (which may have been fetched with an account), download rows and provenance. Account
   details are not in it. Leaving the catalogue tables out of backup needs them in a database
   of their own, which is a schema change and not a hardening fix. For the release checklist
   as a product decision.

## Items that could not be verified

1. That the Android backup rules behave as written on a device. Only the rule files were read.
2. The iPhone keychain's behaviour in a real backup and restore.
3. How large a picture's decoded bitmap can get. Parrot limits a picture's bytes (4 MiB), not
   its width and height; the image library decodes to the size of the view on Android. A small
   file that declares a huge size was not tried on either platform.
4. That a host name which resolves to a local address is treated as local. Only addresses
   written as numbers and the names `localhost`, `*.local`, `*.lan` and `*.localhost` are
   recognised; no name is resolved. A public name that points at a private address is not
   caught.
5. The 30-second "no bytes" limit on a real connection. The limit is set on the request and
   tested; whether each platform's network engine enforces it was not run.
6. Real TLS failures (a self-signed or expired certificate). Tested with simulated errors only.
7. Whether the iPhone's system HTTP cache writes catalogue responses to disk. The transport
   installs no cache of its own; the system's default was not inspected on a device.
8. Anything on an iPhone at all, beyond tests in the simulator.
