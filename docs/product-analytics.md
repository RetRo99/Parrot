# Product usage analytics

These events extend existing Firebase analytics without sending book IDs, titles, text,
search queries, file paths, server URLs, or profile identity. Completion deduplication
uses device-local preferences, not analytics parameters. Debug builds log sanitized
events locally through `DebugAnalyticsManager`; release builds send them to Firebase.

## Events and interpretation

| Event | Trigger / useful dimensions |
| --- | --- |
| `reading_session_summary` | Incremental active time, split into `reading_duration_ms`, `audiobook_duration_ms`, `readaloud_duration_ms`, and `tts_duration_ms`; foreground/background durations; `end_reason`. |
| `first_meaningful_session` | Once per installation when a reader/playback session reaches 120 seconds of active time. `usage_mode`, `since_first_launch_ms`. |
| `reader_open_completed` | Once per open attempt, including each explicit retry. `media_type`, `entry_point`, `outcome`, `duration_ms`, `is_retry`, bounded `reason_code` on failure/cancellation. |
| `search_results_shown` | Library results actually presented, or one terminal in-book scan result. `search_scope`, `result_count_bucket`, `duration_ms`, `has_filters`, `outcome`, `is_capped`, `is_restricted`. |
| `search_result_selected` | User selects a library or in-book result. `search_scope`, `result_position_bucket` (one-based). |
| `listening_source_changed` | A supported narration/device-voice switch is applied, not merely clicked. `previous_usage_mode`, `usage_mode`, `outcome`. Playback start reliability remains in existing playback/TTS events. |
| `playback_session_summary` | Actual playback/buffering deltas, split by source. `usage_mode`, `playing_duration_ms`, `buffering_duration_ms`, `interruption_count_bucket`, `end_reason`. |
| `book_completed` | Crossing the existing 98% finished-book threshold or natural audiobook end; reopening an already-finished book is excluded. `usage_mode`, `completion_method=automatic`. |
| `feature_exposed` | Visible bookmark/TTS controls, sleep timer in the audio sheet, or the enabled library backup control. `feature_name`, `screen`, `is_available`. Deduplicated per screen visit and availability state. |
| `book_backup_operation` | Queue requests and actual transfer outcomes, including retries and cancellation. `backup_scope`, `stage`, `outcome`, `duration_ms`, count buckets, `is_retry`, bounded `backup_error_category`. |

Count buckets: `zero`, `one`, `two_to_five`, `six_to_twenty`, `over_twenty`.

## Important counting rules

- **Sum session durations, not session-summary event counts.** Summaries are non-overlapping
  deltas emitted every 30 seconds and on background/close/clear, or relevant playback
  transitions. A checkpoint is not a new reading session. Process termination may lose
  the last unreported interval; it does not replay previous intervals.
- Reading time means usable content is visible (or narration actually plays), not proof
  of human attention. Loading, buffering, background inactivity, and startup position
  dialogs do not count as active usage. Visible, paused narration can count as reading.
- Shared-reader foreground/background reflects reader visibility. Android standalone
  audiobook foreground/background reflects app visibility, sampled once per second.
- Playback summaries are a **subset** of reading usage, not additional time to add to it.
  Playback interruptions count transitions from playing to paused/inactive, excluding
  buffering and source switches. They are not a count of OS audio-focus interruptions.
- Android standalone audiobooks are tracked by `MediaPlaybackService`, so notifications,
  Android Auto, and playback after leaving the screen still count. Read-aloud/TTS is
  tracked by the shared reader to avoid recording that audio twice.
- `content_access=on_device` means prepared local content, **not network-offline status**.
  No connectivity signal currently exists for accurately reporting offline sessions.
- Reader open latency ends at the shared reader's first locator (usable publication),
  or at prepared audiobook track availability. Audio-engine startup is separate.
- Library search latency measures local filtering; in-book latency measures the scan,
  excluding typing debounce. Cancelled/superseded scans do not report stale results.
  In-book counts respect the spoiler boundary; `is_restricted=true` distinguishes a
  partial-book scan from a whole-book scan. Revealing more matches is not a new search.
- Activation is device/install-level, not per profile/account. On upgrades, “first launch”
  is the first launch of the instrumented version, **not** the original installation date.
- Completion is deduplicated per local profile and server/book copy. Moving back below
  98% permits a later completion transition (rereading). Seeking to the end also counts;
  this event does not prove every page was read. There is no manual-completion action
  to instrument today. Linked copies are not globally deduplicated.
- Backup `stage=queue, outcome=queued` **does not mean uploaded**. Use
  `stage=transfer, outcome=succeeded` for actual backups. Single queue requests within
  bulk backup are also tracked; do not add bulk and single counts together. Transfer
  durations include queue time/retry waits since the durable transfer was created.
- The standalone iOS audiobook screen is currently empty. Shared-reader events are
  cross-platform; standalone audiobook service telemetry is Android-only.

## Firebase / GA4 reporting setup

Register the dimensions you intend to use as **event-scoped custom dimensions**:
`usage_mode`, `previous_usage_mode`, `search_scope`, `feature_name`, `is_available`,
`end_reason`, `outcome`, `entry_point`, `media_type`, `backup_scope`, `stage`,
`backup_error_category`, and relevant count buckets. Register duration parameters as
custom metrics if using GA4 reports; use milliseconds consistently. Alternatively,
query event parameters directly in your existing BigQuery export.

Useful initial reports:

1. **Core usage:** sum the four mode durations by app version/platform; also compare
   distinct users with non-zero time, rather than rewarding frequent button presses.
2. **Activation:** users reaching `first_meaningful_session` / users first opening the
   instrumented app. Separate existing users from new-install cohorts.
3. **Open reliability:** succeeded / all terminal `reader_open_completed` outcomes,
   with latency percentiles split by media type, entry point, and retries.
4. **Search quality:** zero-result rate by scope; users selecting a result / users
   shown results. Without query/search IDs this is aggregate adoption, not an exact
   per-query conversion or abandonment rate.
5. **Feature adoption:** distinct users using each existing feature action / distinct
   users exposed to that available feature. Keep the same time window and eligibility.
6. **Listening reliability:** buffering time / (playing + buffering time), split by
   source; inspect error endings and existing TTS playback failures.
7. **Backup reliability:** transfer success/failure outcomes separately from queue
   outcomes and retry scheduling. No transfer IDs are sent, so use aggregate outcome
   counts rather than claiming exact per-transfer funnels.

Use Firebase's existing anonymous app-instance identity for aggregate user/cohort
analysis; no new account-level identity is introduced. Retention correlations are useful
for prioritization but do not prove a feature causes retention.

## Verification

- Unit tests cover delta accounting, source switching, background listening, inactive/
  buffering exclusion, activation persistence, completion transitions, open-attempt
  deduplication, feature-exposure deduplication, and all 10 provider schemas.
- Transfer tests cover success/failure privacy, terminal deduplication, and telemetry
  provider failure not changing a successful backup.
- Device smoke test: read for 2 minutes, switch to TTS, background/resume, search with
  zero and non-zero results, select one, finish a book, and queue a backup. Verify
  events in debug logs or Firebase DebugView with a Firebase-enabled build.
