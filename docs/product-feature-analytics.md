# Product feature analytics

Use unique users and repeated use, not raw tap counts, to prioritize features. Existing reading/session, activation, search, download, settings and completion telemetry remains unchanged.

## Added coverage

| Event | Trigger / dimensions |
| --- | --- |
| `feature_exposed` (expanded) | Visible import entry point, author/series screens, linking suggestions, book-management sheet, read-aloud controls and recap banner. `feature_name`, `screen`, `is_available`. Positions are unavailable when there are no linked copies. |
| `library_load_completed` | Once per library observer's first result: `outcome`, `duration_ms`, `result_count_bucket`, `load_kind=initial_observation`. Reactive progress/book updates do not inflate load counts. |
| `library_discovery_selected` | Selecting an author/series or opening a book through library, search, favorites, filters, author or series browsing. `discovery_route`, `discovery_destination`. |
| `book_import_operation` | Import processing attempt and terminal result. File-picker dismissal is not an import attempt. |
| `recap_interaction` | Actual composed banner exposure (deduplicated per session in the banner ViewModel), expand, collapse, dismiss. `usage_action`, `is_latest_recap`. Stored availability alone is not an exposure. |
| `book_link_operation` | Manual/suggested/bulk linking, reject, skip, unlink. |
| `linked_resume_decision` | Visible offer, accepted/declined action and persistence outcome. |
| `position_conflict_resolution` | Visible prompt, local/remote choice and operation outcome. Reader-controller commands return `queued`, not a fabricated successful relocation. Book-detail resolution reports its use-case result. |
| `copy_position_apply_operation` | Applying a position to selected copies, with target-count bucket and success/partial/failure. Refused writes do not count as successful. |
| `reader_navigation_completed` | TOC/chapter navigation, bookmark jumps and chapter-progress slider. Method, usage mode, outcome, duration. Success requires an observed matching destination; 10-second timeout reports failure, replacement/close/clear reports cancellation. Chapter matching uses the resource href; bookmark/slider matching also checks progression within 5 percentage points to allow paginated locator rounding. |

Operation events contain `usage_action`, `screen`, `stage`, `outcome`, `duration_ms`, `target_count_bucket`. Each executed operation emits one `started` and one terminal (`succeeded`, `failed`, `partial`, `queued`, `cancelled`) result. Visible prompts use `usage_action=shown`, `stage=presented` and are **not** execution attempts. Exclude them from success-rate calculations. Bulk linking emits one bulk operation rather than duplicating it as individual link operations.

### Measurement limits

- Library loading measures the first combined result, **not** a full network refresh. The current domain observer collapses individual server errors into empty lists, so this event cannot diagnose per-server failures or distinguish them from genuinely empty libraries. No server identity or invented server-type attribution is sent.
- Linked-resume success means the position was persisted, not that the next reader relocation or remote sync succeeded.
- Import events measure the existing EPUB import pipeline, not picker abandonment. Existing import failure diagnostics remain available; raw exception messages and filenames are never added to the new events.
- Navigation locators provide approximate page destinations, not proof that the user read the resulting page. Chapter navigation from next/previous/play controls is included in `navigation_method=toc`.
- Analytics is best-effort: provider failures never change action results. Unexpected action exceptions and cancellation are reported and rethrown, preserving existing behavior.

## Firebase / GA4 setup

After shipping, verify events in DebugView on both platforms. Register event-scoped custom dimensions for the fields you will actually use in reports (subject to your property's quota):

- `feature_name`, `is_available`, `screen`
- `usage_action`, `stage`, `outcome`
- `discovery_route`, `discovery_destination`
- `navigation_method`, `usage_mode`
- `is_latest_recap`, `load_kind`, `target_count_bucket`, `result_count_bucket`

Register `duration_ms` as a custom metric if it is not already registered. Existing automatically collected platform/app-version dimensions can segment reports without new events. Registration is a console configuration step; it is not performed by this code change.

## Recommended reports

1. **Feature adoption:** unique users performing an action / unique eligible exposed users, within the same reporting window. Match `screen` where appropriate; exposed users are a discovery denominator, not an experiment assignment.
2. **Reliability:** terminal succeeded / terminal attempted results, separately by action. Keep cancelled, queued and partial visible rather than silently treating them as successes.
3. **Repeat use:** unique users using a feature on at least two different days.
4. **Retention:** 7/30-day meaningful reading/listening retention by first feature-use cohort. Association does not establish causation.
5. **Friction:** library initial-result latency, empty-result buckets, import/link/apply failures and navigation timeout rates.

No new event includes book/server/profile/session identity, titles, author names, search queries, locator hrefs, recap text, filenames, URLs or exception messages. New categorical dimensions are explicitly allowlisted at the analytics provider boundary. Local recap/session and navigation state is used only for deduplication/destination matching and is never transmitted.
