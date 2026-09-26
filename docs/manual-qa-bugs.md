# Parrot QA bug register

Keep every entry, including fixed and duplicate observations. These first findings are confirmed by source inspection; whether the current Firebase build actually delivered the sensitive values is unverified until the blocked runtime payload checks are performed.

## QA-BUG-0001 — Analytics event parameters are forwarded without privacy filtering

- **Severity / impact:** Medium; potential privacy exposure and high-cardinality telemetry, and values can also appear in debug logs.
- **Status:** Fixed in source; Android host tests and Samsung debug-provider retest PASS; Firebase Analytics ingestion waived by user (not claimed); commit hash pending.
- **Screen/test IDs:** Setup 509, 511; applies across all event-producing screens.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21), debug; exercised APK SHA-256 `f77dc3563cf2b1828af20628ac89ed9054e3d15a442c6130927f13e1b60eb937` from source `956ec8a443d2236396c490a3056efdf9a1181e32` plus QA-BUG-0001 changes; fix commit below.
- **Preconditions:** Emit any event type with free-form/high-cardinality parameters.
- **Reproduction:** Source inspection: `AnalyticsManager.logEvent` forwards `event.parameters` unchanged to Firebase; `DebugAnalyticsManager.logEvent` writes the complete parameter map. Event types include `book_uuid`, `profile_name`, `font_name`, `error_message`, and `server_url_hash`.
- **Expected:** Analytics/debug telemetry excludes private or high-cardinality values; stable event names and safe categorical parameters remain usable.
- **Actual:** The event map is passed through unchanged. Firebase receipt of any particular value has not yet been independently observed.
- **Frequency:** Every emission of an affected event, if the parameter is populated.
- **Evidence:** Original pass-through at `AnalyticsManager.kt:29-32` and `DebugAnalyticsManager.kt:31-39` on `956ec8a`; affected events include `AnalyticsEvent.kt:21-29,133-144` and `BookAnalyticsEvent.kt:276-283`. Retest excerpt: [`setup-debug-provider-events.txt`](manual-qa-evidence/2026-09-26/setup-debug-provider-events.txt); host test report: `lib/analytics/implementation/build/reports/tests/testAndroidHostTest/index.html`.
- **Root cause:** No shared parameter redaction/allowlist at either analytics provider boundary.
- **Affected files:** `lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsParameterSanitizer.kt`, `AnalyticsManager.kt`, `DebugAnalyticsManager.kt`, test configuration and `AnalyticsParameterSanitizerTest.kt`.
- **Fix reference / commit:** Added fail-closed provider-boundary filtering; only registered bounded categorical/numeric/boolean dimensions are emitted. Commit `ffef0a3d03ca73b4ffcff8cf9a47a243e035d2ac`.
- **Retest:** PASS — three Android host unit tests; Android debug/release builds successful; Samsung debug APK installed and hash-matched; cold-start emitted local events with approved fields only. Aggregate iOS `allTests` was BLOCKED by missing `FirebaseCore`; Android host tests pass. User waived Firebase Analytics ingestion verification; delivery is not claimed.

## QA-BUG-0002 — Exception diagnostics include raw throwable/message context

- **Severity / impact:** Medium; exception text/cause chains may contain private paths, request details or user input in diagnostic reporting.
- **Status:** Fixed in source; six Android host tests and Samsung debug local retest PASS; Crashlytics delivery blocked; commit hash pending.
- **Screen/test IDs:** Setup 506, 509, 510; applies to all exception-reporting screens.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; installed package `com.retro99.parrot` v0.4.5 (21), debug APK from commit `ffef0a3d03ca73b4ffcff8cf9a47a243e035d2ac` plus uncommitted QA-BUG-0002 changes.
- **Preconditions:** A handled exception is reported with an optional message or a sensitive throwable message/cause.
- **Reproduction:** Source inspection: production `AnalyticsManager.logException` logs `message` to Crashlytics and passes the original throwable to `recordException`; debug provider logs the throwable and message.
- **Expected:** Preserve actionable operation context and useful stack/cause while sanitizing free-form throwable/message content; report once at the impact-aware boundary.
- **Actual:** Raw message and throwable are passed to the reporting/logging APIs. Runtime content and report count have not yet been observed.
- **Frequency:** Every reported handled exception with free-form context.
- **Evidence:** `lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsManager.kt:18-26`; `DebugAnalyticsManager.kt:17-28`.
- **Root cause:** No diagnostic context/throwable sanitization boundary.
- **Affected files:** `DiagnosticPayloadSanitizer.kt` and Android/iOS actuals; `AnalyticsManager.kt`, `DebugAnalyticsManager.kt`, `AndroidFileLogger.kt`, `IosFileLogger.kt`; common and Android host tests.
- **Fix reference / commit:** Free-form context is omitted; sanitized exception wrappers preserve safe type/cause structure and Android original stack frames; file and debug log paths use the sanitized wrapper. Commit `338d4ab29745d6e1a1b82524161d0fc9c5eabd2d`.
- **Retest:** PASS locally — six Android host tests passed (3 analytics-parameter, 2 diagnostic-payload, 1 Android stack-preservation); iOS main/test source compilation passed; debug/release app builds passed. On Samsung, cold startup produced sanitized exception records with only generic context and exception/cause types, while preserving stack frames per host regression test. Firebase Crashlytics issue/symbolication remains BLOCKED and is not claimed.

## QA-BUG-0003 — Deterministic account/URL hashes are assigned as Firebase user IDs

- **Severity / impact:** Medium; suspected linkability of server usernames/URLs through an unkeyed, low-entropy identifier shared with Analytics and Crashlytics.
- **Status:** Suspected privacy gap; source confirms the identifier reaches the provider, but no real credential or Firebase payload has been inspected.
- **Screen/test IDs:** Setup 509, 511; login cases 22–36 and 534.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21); source audit `956ec8a443d2236396c490a3056efdf9a1181e32` plus the QA-BUG-0001 working-tree fix.
- **Preconditions:** Successful credentials or OAuth login using a controlled test account/server.
- **Reproduction:** Source inspection: `LoginViewModel` calls `setUserId(username.hashCode().toString())` after credentials login and `setUserId(url.hashCode().toString())` after OAuth. `AnalyticsManager.setUserId` forwards the same string to both Firebase Analytics and Crashlytics.
- **Expected:** Use a documented non-identifying analytics identity compatible with the single-device measurement policy; do not derive a stable reporting identifier from username or server URL.
- **Actual:** Java `String.hashCode()` is a deterministic, unkeyed, low-entropy derivative and is sent unchanged as the Firebase user ID. Whether this is accepted by the configured project is not verified.
- **Frequency:** On each successful login; identifier remains stable for equal username/URL values.
- **Evidence:** `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/login/LoginViewModel.kt:117,155`; `lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsManager.kt:34-37`.
- **Root cause:** Hashing was treated as anonymization and reused as provider identity without a reviewed privacy policy.
- **Affected files:** Login ViewModel and analytics user-identity contract/providers (fix pending).
- **Fix reference / commit:** Pending.
- **Retest:** NOT RUN. Requires a controlled QA login identity, Firebase Analytics/Crashlytics access and a reviewed identity policy; do not use production credentials.

## QA-BUG-0004 — Failed login is reported to Crashlytics at repository and UI boundaries

- **Severity / impact:** Medium; expected credential/network failures can create duplicate Crashlytics non-fatals and exception noise for ordinary recovery.
- **Status:** Confirmed source-level duplicate-reporting/instrumentation defect; runtime report count not yet observed.
- **Screen/test IDs:** Setup 510; login cases 23–24, 525, 534.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21); source under audit `ffef0a3d` plus in-progress QA-BUG-0002 changes.
- **Preconditions:** Login operation returns an `AppError` and reaches the ViewModel failure callback.
- **Reproduction:** Source inspection: `LoginDataRepository.login` logs each auth failure in `onFailure`; `LoginViewModel` then logs the same failure with `error.log(analytics, ...)`. The OAuth path has the same repository/UI pattern.
- **Expected:** Ordinary wrong credentials/network conditions produce a bounded outcome event and recovery breadcrumb, not Crashlytics non-fatals; any unexpected user-impacting failure is submitted once at the boundary with useful context.
- **Actual:** Two `logException` calls are reachable for the same failed login; the current provider records each call as a separate Crashlytics exception.
- **Frequency:** Each failed credentials/OAuth operation that reaches both layers.
- **Evidence:** `feature/login/data/src/commonMain/kotlin/com/retro99/login/data/LoginDataRepository.kt:35-41,70-80`; `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/login/LoginViewModel.kt:121-128,159-165`.
- **Root cause:** Both data and presentation boundaries independently report a failure that is propagated to the caller.
- **Affected files:** Login data repository, Login ViewModel and possibly auth error classification (fix pending).
- **Fix reference / commit:** Pending.
- **Retest:** NOT RUN. Required: wrong-credentials and controlled transport failure; verify outcome events/recovery and one-or-zero Crashlytics reports as appropriate in a Firebase-enabled build.

## QA-BUG-0005 — Routine network failures flood handled-exception reporting

- **Severity / impact:** Medium; repeated expected transport/HTTP failures create excessive local diagnostic entries and would submit repeated Crashlytics non-fatals in the production provider, obscuring actionable failures.
- **Status:** Fix committed and locally retested on Samsung; exercised expected-network paths PASS. Firebase submission/count not verified.
- **Screen/test IDs:** Setup 503 (observation), 512/513 (related acceptance checks; not completed); affects network-backed screens.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; `com.retro99.parrot` v0.4.5 (21), debug; fix commit `d1e18128288d7342c211d226a9041387a0974796`.
- **Preconditions:** Launch with a configured media server endpoint that is unreachable while Wi-Fi is enabled and airplane mode is off.
- **Reproduction:** On 2026-09-26 at approximately 15:39 CEST, cold-start `MainActivity` on serial `RFCWC0SSVDM`; filtered app logcat for the sanitized debug exception marker. Nineteen handled-exception records appeared in approximately 14 seconds, including `ConnectException` / `ErrnoException` causes. No endpoint, URL, account or content data is retained in evidence.
- **Expected:** Connectivity/timeout failures and ordinary HTTP outcomes produce bounded breadcrumbs and operation outcome events, not one Crashlytics issue per request. Escalate only unexpected handling/recovery failures.
- **Actual:** Baseline Ktor, HTTP-validator and propagated-AppError paths logged expected transport/HTTP failures as exceptions. Latest source suppresses these reports at those boundaries and retains typed failure outcome events. Baseline and failed-first-retest volumes remain documented below.
- **Frequency:** 19 local exception records in this launch; exact rate varies with active requests/retries.
- **Evidence:** Baseline sanitized excerpt/count: [`case-503-sanitized-diagnostics.txt`](manual-qa-evidence/2026-09-26/case-503-sanitized-diagnostics.txt). Failed retest 1: [`case-503-network-failure-retest1.txt`](manual-qa-evidence/2026-09-26/case-503-network-failure-retest1.txt). Passing retest 2: [`case-503-network-failure-retest2.txt`](manual-qa-evidence/2026-09-26/case-503-network-failure-retest2.txt). Sources include `KtorNetworkClient.kt`, `ServerHttpClientFactory.kt`, `HttpClientProvider.kt`, `AppResult.kt`, and `BaseRepository.kt`.
- **Root cause:** Multiple HTTP layers and BaseRepository propagation independently reported expected transport failures without consulting the outcome classification.
- **Affected files:** `KtorNetworkClient.kt`, `ServerHttpClientFactory.kt`, `HttpClientProvider.kt`, `AppError`, `BaseRepository.kt`, network analytics event and tests.
- **Fix reference / commit:** Classify transport outcomes, remove lower HTTP-validator exception duplication, and suppress expected network/HTTP errors at propagated AppError boundaries. Reuse `network_request_failed`; allowed dimensions are bounded `error_type`, `is_timeout`, `is_connectivity`, and optional HTTP `status_code` (100–599), emitted once per handled request/status at `KtorNetworkClient`; the endpoint field is removed by the analytics allowlist. Commit `d1e18128288d7342c211d226a9041387a0974796`.
- **Retest history:** Attempt 1 FAIL — network classifier tests passed, but Samsung cold-start still produced six debug exception records in about 11 seconds; at least three came from `ServerHttpClientFactory`. Attempt 2 PASS — zero exception markers and nine outcome events on Samsung, recorded in `case-503-network-failure-retest2.txt`; timing was imprecise. Attempt 3 INCONCLUSIVE — the capture was neither PID-scoped nor constrained to the host's 18-second interval; the 372 event / 255 marker lines had device timestamps about 49 minutes before the capture. Discard those counts as stale logcat-buffer history; no raw unfiltered log is retained. Attempt 4 PASS — cold-start, fresh PID-scoped 20-second capture: two `network_request_failed` events and zero sanitized exception markers; see `case-503-network-failure-retest4.txt`. Post-commit attempt 5 PASS — rebuilt and installed APK hash matched, then a fresh PID-scoped 20-second capture again recorded two failure outcome events and zero exception markers; see `case-503-network-failure-retest5.txt`. Firebase delivery remains unverified.

## QA-BUG-0006 — Exception reports lose safe screen/action/stage context

- **Severity / impact:** High for diagnosability; unexpected user-impacting failures cannot be reliably grouped with the screen, action, operation stage, or stable reason that produced them.
- **Status:** Confirmed source-level instrumentation defect. Firebase Crashlytics delivery and issue context remain unverified.
- **Screen/test IDs:** Instrumentation contract applies across screens; directly affects setup 506–509 and all screen-specific failure reporting.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21); source audit at `5b77138a` plus prior QA fixes.
- **Preconditions:** An operation calls `Analytics.logException(throwable, context)` with safe operation context, for example a books-load failure.
- **Exact reproduction:** Source trace `BooksListViewModel` calls `error.log(analytics, "BooksViewModel: Failed to load books")`; `AnalyticsManager` and `DebugAnalyticsManager` pass that to `sanitizeDiagnosticMessage`; the sanitizer unconditionally returns only `Handled exception; free-form context omitted`. The throwable sanitizer retains exception/cause type names; Android copies original stack frames, while the iOS actual does not explicitly preserve original frames. No typed breadcrumb or correlation context is attached by this API.
- **Expected:** Keep arbitrary exception/cause messages private, but report bounded structured context (`screen`, `action`, `operation`, `stage`, `outcome`, `reason_code` and useful bounded dimensions) plus a diagnostic-only operation correlation ID and preceding breadcrumbs.
- **Actual:** The reporting boundary contains a generic message and sanitized type/cause chain. A stack frame can identify source code, but not consistently the user-visible screen/action/stage/recovery outcome. A related Analytics event, if emitted, is not correlated to the Crashlytics record.
- **Frequency:** Every exception reported through the current `logException` boundary.
- **Evidence:** `DiagnosticPayloadSanitizer.kt:14-16`; `AnalyticsManager.kt:18-23`; `DebugAnalyticsManager.kt:17-25`; `AndroidFileLogger.kt:24-36`; `DiagnosticPayloadSanitizer.android.kt:22-25`; `DiagnosticPayloadSanitizer.ios.kt:3-9`; `BooksListViewModel.kt` around the failure report (see `error.log` call).
- **Root cause:** A blanket redaction replaced the entire context field instead of separating validated operation context from untrusted free-form text; no breadcrumb/context API exists in `Analytics`.
- **Affected files:** Analytics API, common diagnostic sanitizer, Firebase/debug/file providers and platform throwable sanitizers; screen operation call sites and regression tests.
- **Fix reference / commit:** Pending. Proposed direction: typed structured diagnostic context and bounded breadcrumb API; keep throwable/cause messages omitted and preserve platform stack/cause behavior.
- **Retest:** NOT RUN. Requires focused host tests proving safe fields survive and private strings do not, plus Samsung local-provider verification. Firebase Crashlytics delivery remains blocked by unavailable authorized Console access.

## QA-BUG-0007 — Setting-change Analytics drops the selected value

- **Severity / impact:** Medium; setting-change events cannot explain which reader preferences users choose, undermining feature-usage analysis and preference defaults.
- **Status:** Confirmed source-level analytics schema/filter mismatch; provider delivery is not independently verified.
- **Screen/test IDs:** Reader settings cases 185–231 and extensions 625–639; `SettingChanged` is also used for playback speed.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21); source audit at `5b77138a` plus prior QA fixes.
- **Preconditions:** A reader setting is changed and `ReaderAnalyticsEvent.SettingChanged` is emitted.
- **Exact reproduction:** `SettingsViewModel.updateReaderSetting` emits `ReaderAnalyticsEvent.SettingChanged(settingName, newValue)`; the event map includes `setting_name` and `new_value`. `sanitizeAnalyticsParameters` allows the former but has no `new_value` rule, and `AnalyticsParameterSanitizerTest.removesPrivateAndUnregisteredDimensions` expects unregistered strings to be removed. Thus the provider removes every selected value.
- **Expected:** Preserve useful preferences as a bounded enum/bucket per setting while excluding user-authored identifiers and arbitrary values (for example, map imported font names to `custom`, not their names).
- **Actual:** Only `setting_name` survives sanitization; different values for the same setting become indistinguishable.
- **Frequency:** Every setting-change event sent through either analytics provider.
- **Evidence:** `AnalyticsEvent.kt:102-115`; `SettingsViewModel.kt:354-369` and setting event call sites; `AnalyticsParameterSanitizer.kt:8-28,34-57`; `AnalyticsParameterSanitizerTest.kt:10-27`.
- **Root cause:** The fail-closed parameter allowlist did not add a safe normalized value dimension or transform event values into setting-specific categories.
- **Affected files:** Analytics event schema, setting-change event producers, provider sanitizer and schema tests.
- **Fix reference / commit:** Pending. Proposed direction: emit an approved categorical/bucket parameter (`value_bucket`) using per-setting normalization; never allow raw generic `new_value` through the provider boundary.
- **Retest:** NOT RUN. Requires tests for enum/boolean/numeric bucket retention and for rejection of custom font names, arbitrary text, out-of-range values and raw color identifiers; then local Samsung debug-provider verification. Firebase Analytics ingestion is waived by the user and will not be claimed.
