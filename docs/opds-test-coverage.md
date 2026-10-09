# Book catalogues (OPDS): test coverage against the plan

Written in the Phase 5 hardening run, 2026-10-09, branch `opds/phase4-screens`.
It maps every line of the test plan (`opds-server-implementation-plan.md` §8) and the
Phase 5 list (§7) to the tests that cover it.

How to read it:

- A test is named `Class › test name`. Test names are shortened where they are long.
- "Added in Phase 5" marks a test written in this run because the line had none, or
  only part of one.
- "Missing" means there is no test. The reason is given.
- "Needs a device" means no automated test can cover it; it is in
  `opds-release-checklist.md`.

Modules, for finding a class:

| Short name | Module |
|---|---|
| opds | `lib/opds/implementation` |
| server-opds | `lib/server-opds` |
| server-api | `lib/server/api` |
| server-impl | `lib/server/implementation` |
| queue | `feature/catalogue/data` |
| domain | `feature/catalogue/domain` |
| ui | `feature/catalogue/ui` |
| app | `composeApp` (Android host tests, the app's real dependency graph) |
| db | `lib/database/implementation` |
| epub | `lib/epub/implementation` |
| books-data | `feature/books/data` |

## 1. Shared protocol tests (§8)

| Plan line | Covering tests | Status |
|---|---|---|
| OPDS 1 navigation feed | opds `Opds1ParserTest › navigation_feed_metadata_and_links`, `…pagination_comes_from_declared_links_only`, `…search_offer_is_the_descriptor_link` | Covered |
| OPDS 1 acquisition feed | opds `Opds1ParserTest › acquisition_feed_entries_variants_format_counts_and_resolution`, `…self_and_up_links_resolved`, `…cover_images_surface_the_model_entry_and_link` | Covered |
| OPDS 1 full entry | opds `Opds1ParserTest › standalone_full_entry_cdata_content_and_root_xml_base_resolution` | Covered |
| Namespace prefixes and defaults | opds `Opds1SyntaxCoverageTest › a_feed_that_writes_atom_with_a_prefix_reads_the_same…`, `…elements_are_matched_by_name_whatever_namespace_they_are_in` | Added in Phase 5. The second test pins a limitation: elements are matched by name only, so an Atom-named element in a foreign namespace is read as Atom. |
| Inherited XML base | opds `Opds1ParserTest › navigation_feed_entries_with_relative_hrefs_and_inherited_xml_base`, `LinkBaseRetentionTest`, `ImageRelationsTest › a_picture_address_is_resolved_against_the_page…` | Covered; the picture case added in Phase 5 with a fix |
| Inherited language | opds `LocalizedTextTest › xml_text_inherits_and_overrides_language` | Covered |
| Text, CDATA, entities, XHTML | opds `Opds1ParserTest › acquisition_feed_xhtml_content_kept_with_format`, `…standalone_full_entry_cdata_content…`, `Opds1SyntaxCoverageTest › named_and_numbered_character_references_are_decoded…`; domain `CatalogueDescriptionSanitizerTest` (20 tests) | Covered; character references added in Phase 5 |
| Facets (OPDS 1) | opds `Opds1SyntaxCoverageTest › opds_1_facet_links_do_not_become_filters_and_do_not_disturb_the_page` | Added in Phase 5 as a pin. **OPDS 1 facets are not implemented**: the page reads and offers no filter. OPDS 2 facets are implemented and tested. |
| Grouping | opds `Opds1ParserTest › calibre_feed_entries_are_publications_and_never_a_grouping_point`; ui `CatalogueBrowseModelsTest › the_grouping_rule_matches_phase_1`, `LinkedBookRuleTest`, `CapturedLinkFeedsTest` | Covered |
| Relative and encoded links | opds `OpdsUrlResolverTest › catalogue_link_cases`, `…normal_examples_unicode_rfc_5_4_1`, `…abnormal_examples_strict_parsing` | Covered |
| Variants | opds `Opds1ParserTest › acquisition_feed_entries_variants_format_counts_and_resolution`; ui `CatalogueBookModelsTest` | Covered |
| Missing optional fields | opds `Opds1ParserTest › missing_feed_title_falls_back_without_rejection`, `…entries_without_id_get_a_document_scoped_fallback_identity_with_warning`, `PublicationSubjectsTest`, `AtomFileLengthTest` | Covered |
| Unknown extensions | opds `Opds1ParserTest › unknown_extension_children_are_ignored_with_bounded_warnings` | Covered |
| OPDS 2 publication, feed, groups, facets, images | opds `Opds2ParserTest › catalog_metadata_topology_and_contributors`, `…optional_unknown_fields_relations_and_facets`, `…landscape_standalone_publication`, `…equivalent_versions_share_the_publication_model` | Covered |
| OPDS 2 contributors and localized fields | opds `Opds2ParserTest › catalog_metadata_topology_and_contributors`, `LocalizedTextTest › json_keeps_all_localized_fields_without_selecting`, `…preference_order_exact_primary_und_then_first` | Covered |
| OPDS 2 relation aliases and arrays | opds `Opds2ParserTest › optional_unknown_fields_relations_and_facets`; `AcquisitionClassifierTest › sample_alias_is_retained…` | Covered |
| OPDS 2 manifests | opds `Opds2ParserTest › landscape_standalone_publication` (a standalone publication document) | Partly. A Readium Web Publication Manifest with a reading order is not a supported acquisition and has no fixture of its own; it is classed by its media type in `AcquisitionClassifierTest › html_wrapper_is_not_direct_epub` and neighbours. |
| Nested indirect acquisition and price | opds `Opds2ParserTest › multiple_indirect_roots_and_standard_price_object`; `AcquisitionClassifierTest › nested_licence_is_protected`, `…epub_outer_with_indirect_tree_is_not_direct` | Covered |
| MIME parameters, order, case | opds `OpdsMediaTypeParserTest › parameters_order_and_case`, `…quoted_parameter_values_and_missing_handling`, `…exact_string_comparison_is_not_used` | Covered |
| Generic JSON and XML compatibility | opds `OpdsMediaTypeParserTest › generic_xml_body_is_classified_by_structure`, `…gutenberg_s_plain_atom_response_type_is_an_accepted_compatibility_case`; `Opds2ParserTest › generic_json_uses_structure_not_first_key` | Covered |
| HTML, auth and error bodies rejected as feeds | opds `OpdsMediaTypeParserTest › html_error_page_is_not_a_catalogue`, `…rss_is_rejected`, `…garbage_header_is_not_a_media_type`; `Opds1SyntaxCoverageTest › an_opds_authentication_document_is_not_a_catalogue_page` | Covered; the authentication document added in Phase 5 |
| Bounded malformed-item warnings | opds `Opds2ParserTest › malformed_items_warn_but_root_fails`, `…malformed_nested_sections_and_contributors_are_recoverable`; `Opds1ParserTest › unknown_extension_children_are_ignored_with_bounded_warnings` | Covered |
| RFC URL cases | opds `OpdsUrlResolverTest` (6 tests, RFC 3986 §5.4 vectors) | Covered |
| Effective redirected URL versus `self` | opds `OpdsUrlResolverTest › relative_resolution_never_inherits_a_feed_self_link_url`; `OpenSearchTest › expanded_reference_resolves_after_expansion_against_effective_xml_base` | Covered |
| Meaningful trailing slash and query | opds `OpdsUrlResolverTest › base_urls_without_path_or_with_existing_query`; server-impl `ServerRegistryPersistenceTest › catalogue_address_is_exact_through_add_update_and_restart` | Covered |
| OpenSearch optional and required terms, result selection | opds `OpenSearchTest` (12 tests) | Covered |
| RFC 6570 expansion, Unicode and reserved characters | opds `UriTemplateTest` (7 tests) | Covered |
| Acquisition relation and type combinations | opds `AcquisitionClassifierTest` (35 tests: direct EPUB, generic acquisition, sample, buy, borrow, subscribe, licence, HTML and archive wrappers, PDF, MOBI, several EPUBs) | Covered |
| Never extension-only acceptance | opds `AcquisitionClassifierTest › missing_type_is_not_inferred_from_extension`; epub `EpubFileCheckDiskTest › a staged file is checked by its bytes and not by its name` | Covered |
| DTD, XXE, entity expansion | opds `Opds1ParserTest › internal_dtd_document_is_rejected_at_DOCDECL_before_entities_expand`, `…external_dtd_document_is_rejected_like_internal_one`, `…deep_nested_entity_document_is_rejected_even_under_byte_budget`, `…walker_DOCDECL_stop_is_reachable…`; `OpdsMediaTypeParserTest › doctype_is_rejected_at_detection_before_any_content_is_read` | Covered |
| Nesting and size limits | opds `FeedLimitsStressTest` (8 tests: each limit at the limit and one past it, both formats, all limits at once) | Added in Phase 5. Before, only "over the limit" was tested, and only for some formats. |
| Unsafe links | opds `TransportSafetyTest › unsafe_targets_are_rejected_before_engine_access`, `…redirect_targets_get_the_same_safety_checks`; `TransportFailureStressTest › account_details_written_into_a_link_are_refused_before_any_request`, `…an_address_with_no_host_or_an_unclosed_bracket_is_refused…`; server-opds `CatalogueAddressValidationTest`, `CatalogueUnaskedLocalNetworkTest` (5) | Covered; the last three added in Phase 5 with fixes |
| Bounded data images | server-api `CatalogueImageBytesTest` (7 tests); server-opds `CatalogueInlineThumbnailStressTest` (5 tests); opds `FeedLimitsStressTest › thousands_of_inline_thumbnails…` | Covered; the stress cases added in Phase 5 |

## 2. HTTP, adapter and registry tests (§8)

| Plan line | Covering tests | Status |
|---|---|---|
| Anonymous 200 | server-opds `OpdsCatalogueRepositoryTest › anonymous_root_maps_metadata_navigation_publications_and_cache_revalidation`; app `CatalogueDownloadToLibraryTest` | Covered |
| Ordinary Basic 401 | opds `OpdsTransportTest › basic_401_needs_sign_in_and_tracks_root_context`; server-opds `OpdsCatalogueRepositoryTest › root_401_is_distinct_from_child_401…`; app `CatalogueSignedInEndToEndTest` | Covered |
| OPDS authentication JSON and Link discovery | opds `OpdsTransportTest › unsupported_challenges_never_trigger_auth_document_discovery` | Covered as "never followed". Authentication-document discovery is not part of this release. |
| Empty password | opds `OpdsTransportTest › negotiates_both_versions_and_publications_with_empty_basic_password`; server-impl `OpdsCredentialStoreTest › credentials_are_typed_profile_and_source_scoped_and_empty_password_survives_restart` | Covered |
| Bad credentials | ui `CatalogueAddFlowTest › preset_add_saves_account_only_after_a_successful_check`; `CatalogueBrowserTest › wrong_details_keep_the_sheet_open…`; app `CatalogueSignedInEndToEndTest` (steps 2 and 12) | Covered |
| Unsupported Digest and OAuth | opds `OpdsTransportTest › basic_challenge_is_recognized_among_multiple_challenges_not_inside_realm`; `TransportDownloadTest › statuses_map_to_the_same_codes_as_feeds`; ui `CatalogueAddFlowTest › unsupported_first_page_is_blocked…` | Covered |
| TLS errors | opds `TransportStatusTest › tls_failure`, `…darwin_error_codes_are_classified…`; ui `CatalogueAddFlowTest › certificate_failure_has_no_continue_action_and_saves_nothing` | Covered with simulated errors. A real untrusted certificate needs a device. |
| 403, 404 | opds `TransportStatusTest › forbidden`, `…not_found` | Covered |
| 429, 503 with Retry-After | opds `TransportStatusTest › rate_limit_with_seconds`, `…unavailable_with_http_date`, `…past_invalid_and_overflow_retry_after_are_safe`; `TransportFailureStressTest › retry_after_in_seconds_as_a_date_and_huge_is_read_safely_for_pages_and_files`, `…a_far_future_retry_date…` | Covered; 14 more cases for pages and files added in Phase 5 |
| Credentials absent on cross-origin feed links | server-opds `CatalogueAccountReachTest › a_link_in_a_feed_to_another_host_port_or_lookalike_host_gets_nothing` | Added in Phase 5 |
| Credentials absent on cross-origin covers | server-opds `OpdsCatalogueImageTest` (9 tests); app `CatalogueCoverLoadingTest` (14 tests) | Covered |
| Credentials absent on cross-origin search | server-opds `CatalogueAccountReachTest › a_search_address_on_another_host…`, `…a_presets_search_address_on_another_host…`, `…a_search_description_on_another_host_and_the_template_it_names_get_nothing` | Added in Phase 5 |
| Credentials absent on cross-origin downloads | opds `TransportDownloadTest › a_link_on_another_origin_gets_no_credentials`; server-opds `OpdsAcquisitionDownloadTest › account_details_reach_the_catalogue_but_not_a_file_host_elsewhere`, `CatalogueAccountReachTest › a_file_on_another_host…`; app `CatalogueSignedInEndToEndTest` (step 8) | Covered |
| Same host, different port or scheme | opds `OpdsTransportTest › same_host_different_scheme_cannot_receive_password`, `…explicit_default_port_is_same_origin…`; `TransportFailureStressTest › a_redirect_to_the_same_host_on_another_port_or_over_http_carries_no_account_details`; server-opds `OpdsCatalogueImageTest › another_port_or_scheme_is_another_origin` | Covered |
| Every redirect hop | opds `OpdsTransportTest › credentials_are_not_reattached_after_return_to_origin`, `…redirect_to_another_origin_drops_credentials`, `…five_redirects_allowed_but_sixth_refused`, `…redirect_loop_is_a_distinct_error`; `TransportFailureStressTest › a_download_redirected_off_the_catalogue…`, `…a_download_that_redirects_in_a_loop…` | Covered; download cases added in Phase 5 |
| No cross-profile or cross-server cookie, auth or cache leakage | opds `OpdsTransportTest › source_clients_never_share_cookies_or_default_auth`; `FeedCacheTest › keys_isolate_source_profile_generation_url_and_representation`; server-opds `OpdsProfileLifecycleTest › profile_switch_cannot_reuse_private_cache_credentials_or_old_references`, `SavedPagesFeedCacheTest › only the open profile is read or written`, `OpdsSavedPagesTest › a profile that closes keeps its saved pages and another profile never sees them`; app `CatalogueCoverLoadingTest › another profile's catalogue is never used`, `…the cache key of a picture belongs to one profile…` | Covered |
| Origin edits invalidate auth | server-impl `OpdsCredentialStoreTest › path_edit_keeps_password_but_origin_edits_clear_it_and_all_edits_invalidate_work`; app `CatalogueDownloadCancellationTest › moving the catalogue to another address cancels its running download` | Covered |
| Root URL persistence round trip | server-impl `ServerRegistryPersistenceTest › catalogue_address_is_exact_through_add_update_and_restart` | Covered |
| Anonymous source not dependent on fake credentials | server-impl `CatalogueRepositoryProviderTest › enabled_registered_public_sources_do_not_need_authentication_or_library_factories`, `CatalogueAccessStoreTest › public_is_not_library_authentication…` | Covered |
| Disable and sign-out distinct | server-impl `CatalogueLifecycleTest` (8 tests); server-opds `OpdsProfileLifecycleTest › registry_disable_remove_account_and_remove_source_have_distinct_real_request_effects` | Covered |
| Every repository-provider lookup capability guarded | server-impl `RepositoryCapabilityTest` (2 tests); server-api `ServerCapabilitiesTest` | Covered |
| Adding OPDS cannot break personal library, series, link suggestions, progress aggregation | server-impl `RepositoryCapabilityTest › catalogue_never_reaches_factories_even_when_reported_authenticated`; settings `CatalogueStatusMappingTest`; auth `CatalogueLogoutEntryPointTest` | Partly. The guard that every such consumer goes through is tested. There is no test inside the series, link-suggestion or progress-sync modules that registers a catalogue and checks their output; the plan (§7, Phase 2 audit) records that each of them filters by exact server type. Checked on a device in the release checklist. |
| Cache validators | opds `FeedCacheTest › validators_are_sent_and_304_serves_cached_document`, `…unexpected_304_is_not_an_empty_catalogue`; server-opds `OpdsSavedPagesTest › a saved page is checked again when the catalogue can be reached` | Covered |
| `Vary` and `no-store` | opds `FeedCacheTest › no_store_response_and_304_no_store_are_honored`, `…malformed_documents_and_vary_star_are_not_cached`, `…no_store_removes_previous_cache_entry`; server-opds `OpdsSavedPagesTest › a no-store page is not saved` | Covered |
| Cache auth isolation | server-opds `OpdsSavedPagesTest › a page fetched with account details is not shown after the details change`, `…even a page left behind under old account details is never served under new ones`; `SavedPagesFeedCacheTest › a page is found only under the catalogue and access generation…` | Covered |
| Stale response invalidation | opds `FeedCacheTest › malformed_documents_and_vary_star_are_not_cached`; server-opds `OpdsSavedPagesTest › a catalogue that answers with an error is not served from the saved copy` | Covered |
| Cancellation | opds `TransportSafetyTest › cancellation_cancels_request_and_is_not_an_error_result`, `…cancellation_during_body_read_cancels_stream`; `TransportDownloadTest › cancellation_propagates_from_engine_and_sink`; server-opds `OpdsCatalogueRepositoryTest › disposal_cancels_real_work_without_recording_a_network_error` | Covered |
| Bounded eviction | opds `FeedCacheTest › entry_count_eviction_is_lru`, `…byte_eviction_updates_replacements_and_rejects_oversize_entries`; server-opds `SavedPagesFeedCacheTest › the pages of a profile stay within the budget…`, `…at the budget a page larger than the room that is left evicts the oldest…`, `…the budget holds after every one of many saves…` | Covered; the last two added in Phase 5 |
| Navigation and pagination loop safeguards | ui `CataloguePagingLoopTest` (6 tests) | Added in Phase 5 with a fix. There was no safeguard: a "next" link pointing at its own page was followed for as long as the list was near its end. |
| Server list with an OPDS entry read by an old decoder | server-impl `ServerRegistryPersistenceTest › adding_catalogue_keeps_registered_servers_readable_by_old_decoder` | Covered |
| Logout-all clears OPDS credentials and leaves books and provenance | server-impl `CatalogueLogoutTest`; settings `CatalogueLogoutTest`; app `SignOutOfEverythingTest` (4 tests) | Covered |
| A secret path segment never appears in logs | opds `TransportSafetyTest › logs_never_include_credentials_paths_queries_or_search_text`; `UriTemplateTest › error_messages_do_not_echo_private_templates`; server-api `CatalogueAddressTest › logs_get_the_address_without_anything_that_could_be_a_key` | Covered |
| …in analytics | home `CatalogueDestinationTest › analytics_names_are_fixed_words` | Covered. Catalogue code sends no analytics event of its own; see the security review. |
| …in route state | home `CatalogueDestinationTest › a_route_is_small_and_holds_no_address`, `…an_address_or_account_details_cannot_be_put_in_a_route`, `CatalogueSettingsRouteTest`; ui `CatalogueRouteReferencesTest` | Covered |
| …in synced provenance | queue `AcquisitionAddToLibraryTest › provenance names the catalogue by origin and never by its full address`, `…the origin is scheme host and port and nothing else`; app `CatalogueSignedInEndToEndTest` (step 9) | Covered. Provenance is not synced at all. |
| Cover requests never get another server's bearer token | app `CatalogueCoverLoadingTest` (14 tests), `CoverAuthCatalogueTest` (4 tests); ui `CatalogueImageLoaderSafetyTest` | Covered |

## 3. Acquisition and database tests (§8)

| Plan line | Covering tests | Status |
|---|---|---|
| Unknown size | opds `TransportDownloadTest › a_missing_content_length_is_normal`; queue `AcquisitionQueuePipelineTest › an unknown length is reported as bytes so far with no total` | Covered |
| Changed links | queue `AcquisitionQueuePipelineTest › the link is resolved again on every attempt and the file starts from zero`; server-opds `OpdsAcquisitionDownloadTest › the_link_is_resolved_again_right_before_the_file_is_fetched`, `…a_file_the_catalogue_no_longer_lists_is_refused` | Covered |
| Declared length mismatch | opds `TransportDownloadTest › fewer_bytes_than_declared_is_a_length_mismatch`; `TransportFailureStressTest › more_bytes_than_declared_is_a_length_mismatch_and_the_surplus_is_never_written` | Covered; "more than declared" added in Phase 5 with a fix |
| Truncated stream | opds `TransportFailureStressTest › a_connection_that_drops_mid_file…`, `…a_file_that_ends_early_and_cleanly…`; server-opds `OpdsAcquisitionDownloadTest › a_truncated_file_and_a_dropped_connection_are_connection_failures`; epub `EpubFileCheckTest › a truncated download is not a book` | Covered |
| Invalid ZIP or EPUB | epub `EpubFileCheckTest` (28 tests), `EpubFileCheckDiskTest`, `EpubFileCheckDiskIosTest` | Covered |
| Oversized archive | epub `EpubFileCheckTest › an archive that claims to unpack past the size limit is rejected`, `…a tiny archive that claims a huge expansion is rejected`, `…more entries than the limit is rejected` | Covered |
| Disk full | queue `AcquisitionQueuePipelineTest › a full disk during the download is a storage failure` and four neighbours; `AcquisitionQueueStressTest › the disk fills up at every point of a download…`, `…the disk fills up while the book is moved into the library…`; app `CatalogueLibraryDiskFullTest` | Covered; the move into the library added in Phase 5 |
| Optional checksum mismatch | none | Missing, on purpose. No checksum is read from a catalogue in this release, so there is nothing to mismatch. The file is identified by the SHA-256 Parrot computes itself (queue `CatalogueStagingFilesTest`). |
| Path attacks | epub `EpubFileCheckTest › entries that would escape the book folder are rejected`; queue `AcquisitionQueuePipelineTest › the staging name is generated and nothing from the catalogue reaches the path` | Covered |
| Duplicate taps, concurrent requests | queue `AcquisitionQueueOrderTest › a second identical request returns the existing one`; `AcquisitionQueueStressTest › twenty downloads asked for at once…`; books-data `LibraryImportRetryAndConcurrencyTest › two identical staged files finalized at the same time become one book` | Covered |
| Exact-byte match across providers | queue `AcquisitionAddToLibraryTest › bytes the library already has attach provenance to that book`; books-data `LibraryOriginRulesTest` (5 tests) | Covered |
| Variant editions with equal titles | books-data `LibraryImportCharacterizationTest › different bytes with the same title and isbn become a separate book`; queue `AcquisitionQueueOrderTest › another file of the same book or the same book in another catalogue is its own request` | Covered |
| No progress reset | app `CatalogueCleanupRulesTest › a book kept without its file is downloaded again into the same book and keeps its progress` | Covered |
| No silent existing-file overwrite | books-data `LibraryImportRetryAndConcurrencyTest › an exact match keeps the existing book row and device file untouched` | Covered |
| Cancel versus completion or import race | queue `AcquisitionCleanupRulesTest › a cancel that lands while the library commits the book still records where it came from`; `AcquisitionQueuePipelineTest › a finished request cannot be cancelled…` | Covered |
| Import, file-move and database crash points | books-data `LibraryImportJournalTest` (8 tests); queue `AcquisitionAddToLibraryTest › dying before the library touched the file…`, `…dying after the book reached the library…`, `…dying after the provenance write…` | Covered |
| Restart during each non-terminal state | queue `AcquisitionQueuePipelineTest › after a restart every running state becomes interrupted and its file is removed`; domain `AcquisitionStateMachineTest › a restart interrupts running work and leaves waiting requests waiting`; app `CatalogueWiringTest › at app start a download that was running when Parrot closed becomes interrupted` | Covered |
| Idempotent finalization and provenance | queue `AcquisitionAddToLibraryTest › finishing the same request twice gives one book and one provenance row`; db `CatalogueDaosTest › writing the same provenance row again changes nothing` | Covered |
| Profile switch or deletion mid-download and mid-finalization | queue `AcquisitionRaceMatrixTest › the profile is switched while a file is downloading - being checked - being added`, `…the profile is deleted while…`; `AcquisitionQueueFencingTest` (9 tests) | Covered; "checking" and "deleted" combinations added in Phase 5 |
| Server remove or disable mid-download and mid-finalization | queue `AcquisitionRaceMatrixTest › the catalogue is removed while…`, `…turned off while…`, `…the account details change while…`; app `CatalogueDownloadCancellationTest` (5 tests) | Covered; "checking" and "adding" combinations added in Phase 5 |
| Provenance survives server removal | app `CatalogueCleanupRulesTest › a removed catalogue takes its unfinished downloads, account and status and leaves its books`; queue `AcquisitionAddToLibraryTest › a catalogue that is no longer registered is named by the origin of its listing` | Covered |
| Merge rekeys correctly | db `CatalogueDaosTest › merging two books moves provenance and finished downloads to the survivor without duplicates` | Covered |
| Deletion removes only owned records and files | db `CatalogueDaosTest › a book removed from the library takes its provenance and its finished downloads with it`; app `CatalogueCleanupRulesTest › a deleted book loses its catalogue records and can be downloaded again`; queue `AcquisitionAddToLibraryTest › staged files no request refers to are deleted at start and the others stay` | Covered |
| Origin policy does not auto-upload downloaded files | books-data `BookFileTransferEngineTest › backupAllNeverUploadsCatalogueDownloads`, `…invalidatingACloudFileKeepsACatalogueDownloadWithTheSameBytes` | Covered |
| SQLDelight schema creation and upgrade | db `CatalogueTablesMigrationTest`, `LibraryJoinMigrationTest`, `SavedPagesMigrationTest`, `PositionOriginMigrationTest`; Gradle `:lib:database:implementation:verifySqlDelightMigration` | Covered |
| Existing importer and cloud restore and upload tests stay green | books-data: the whole module is run in verification | Covered by the verification run |

## 4. UI and device QA (§8)

View-model logic for these lines is covered by ui `CatalogueBrowserTest` (39 tests),
`CatalogueBookPageTest` (13), `CatalogueRowDownloadsTest` (8), `DownloadsPageTest` (5),
`CatalogueSettingsTest` (15), `CatalogueAddFlowTest` (16) and home
`HomeNavigationStateHolderTest` (10). Everything else in this group needs a device and is in
`opds-release-checklist.md`:

| Plan line | Status |
|---|---|
| Navigation back and route restoration on a device | Needs a device |
| Gutenberg's two-step flow | Done once on an Android phone and emulator (see `opds-compatibility.md`); logic covered by ui `CapturedLinkFeedsTest` |
| Anonymous and TLS or Basic Calibre | Needs a device and a Calibre server. Not done. |
| Synthetic OPDS 2 groups, facets and manifest feeds on a device | Needs a device. Parser covered above. |
| Authenticated Standard Ebooks | Needs an account. Not done. |
| Offline reading, position, statistics and highlights of a catalogue book, both platforms | Needs a device |
| Font, accessibility, e-ink | Android: done, see `opds-phase4-final-report.md`. iPhone: needs a device. |
| Memory and disk budgets, lifecycle interruption | Needs a device |

## 5. Phase 5 list (§7)

| Plan line | Covering tests | Status |
|---|---|---|
| Large synthetic catalogues | opds `FeedLimitsStressTest › an_atom_feed_at_the_item_limit…`, `…a_json_feed_at_the_item_limit…`, `…a_feed_of_exactly_the_byte_limit…`, `…a_feed_at_every_limit_at_once_is_read` | Added in Phase 5 |
| Deep synthetic catalogues | opds `FeedLimitsStressTest › atom_nesting_at_the_limit…`, `…atom_nesting_inside_an_element_the_reader_ignores_still_counts`, `…json_nesting_at_the_limit…` (each also at depth 100,000) | Added in Phase 5 |
| Throttling | opds `TransportFailureStressTest › retry_after_…`; ui `CatalogueBrowserTest › each_way_a_page_fails_has_its_own_state_and_buttons` | Covered |
| Image-heavy feeds | server-opds `CatalogueInlineThumbnailStressTest`; opds `FeedLimitsStressTest › thousands_of_inline_thumbnails…`; server-opds `OpdsCatalogueImageTest › a_picture_over_the_ceiling_is_not_returned` | Added in Phase 5. Decoded pixel size is not limited by Parrot; see the security review. |
| Disk exhaustion | queue `AcquisitionQueueStressTest` (two tests); app `CatalogueLibraryDiskFullTest` | Added in Phase 5 |
| Flaky links | opds `TransportFailureStressTest › a_connection_that_drops_mid_file…`; queue `AcquisitionQueuePipelineTest › transport results map to the failure reasons`, `…a session closed under a download leaves a failure that can be retried` | Covered |
| Slow downloads | opds `TransportFailureStressTest › a_download_has_no_deadline_for_the_whole_file_but_a_silent_connection_times_out`, `…a_download_that_stalls_after_some_bytes_ends_as_a_timeout…`, `…a_stalled_download_can_be_cancelled` | Added in Phase 5. The 30-second silence limit itself is enforced by the platform's network engine, which the mock engine does not imitate; the test checks that the limit is set on the request and what happens when the engine reports it. |
| TLS and auth failures | see section 2 | Covered with simulated errors |
| Cache eviction | see "Bounded eviction" in section 2 | Covered |
| Removal and profile races | queue `AcquisitionRaceMatrixTest` (6 tests, 15 combinations and one combined) | Added in Phase 5 |
| Import crash windows | see "crash points" in section 3 | Covered |
| Twenty downloads at once | queue `AcquisitionQueueStressTest › twenty downloads asked for at once - two run - the order survives a restart - nothing is lost or doubled`, `…twenty downloads that fail in every way…` | Added in Phase 5 |
| Signed-in use end to end | app `CatalogueSignedInEndToEndTest` | Added in Phase 5 |
| Source-safe analytics and diagnostics | see "A secret path segment…" in section 2 | Covered; nothing to add because catalogue code logs no address |
| Limits documentation | README "Book catalogues (OPDS)" | Written in Phase 5 |
| Verified provider compatibility matrix | `opds-compatibility.md` | Written in Phase 5. Most rows say "not tried". |
| Recheck provider endpoints and terms at release | none | Needs a person; in the release checklist |

## 6. What the new tests found

Each was fixed in this run, with the test that found it:

1. A "next" link that points at its own page or back at an earlier one was followed without
   end (ui `CataloguePagingLoopTest`).
2. OPDS 1 thumbnail and legacy cover relations were not read as pictures
   (opds `ImageRelationsTest`).
3. OPDS 1 picture addresses were kept as written, so a relative cover address could not be
   asked for (opds `ImageRelationsTest`, found by app `CatalogueSignedInEndToEndTest`).
4. A download that sent more bytes than it declared was written in full before it was refused
   (opds `TransportFailureStressTest`).
5. A catalogue on the internet could make Parrot ask a device on the local network for a
   picture, a search description or a file, without asking the user
   (server-opds `CatalogueUnaskedLocalNetworkTest`).
6. A sign-in from a page, a book or Downloads left the catalogue's status at "Sign in needed"
   until the next page was opened (app `CatalogueSignedInEndToEndTest`, step 12).
7. A preset's search address and "list entries are books" hint stayed attached after its
   address was changed to another server (server-impl `CataloguePresetHintsTest`).
8. An address with no host (`https://`) was asked as `https://localhost/`, and one with an
   unclosed bracket was read past it (opds `TransportFailureStressTest ›
   an_address_with_no_host_or_an_unclosed_bracket_is_refused…`, server-opds
   `CatalogueAddressValidationTest`).
