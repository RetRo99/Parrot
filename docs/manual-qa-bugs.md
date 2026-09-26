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
- **Status:** PARTIALLY REMEDIATED / OPEN. Typed bounded context and a dedicated `logBreadcrumb(DiagnosticContext)` API are implemented and used by startup plus Books load/import boundaries. Numerous other exception call sites still use legacy free-form strings (intentionally redacted to generic text) and lack operation breadcrumb sequences. Firebase Crashlytics delivery and issue context remain unverified.
- **Screen/test IDs:** Instrumentation contract applies across screens; directly affects setup 506–509 and all screen-specific failure reporting.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21); source audit at `5b77138a` plus prior QA fixes.
- **Preconditions:** An operation calls `Analytics.logException(throwable, context)` with safe operation context, for example a books-load failure.
- **Exact reproduction:** Legacy calls such as `error.log(analytics, "BooksViewModel: Failed to load books")` pass through `sanitizeDiagnosticMessage` and still become generic `Handled failure; free-form context omitted`. Typed context calls are now supported and preserve allowlisted fields; a separate bounded breadcrumb API is implemented. The throwable sanitizer retains exception/cause type names; Android copies original stack frames, while the iOS actual does not explicitly preserve original frames. Existing legacy call sites do not automatically acquire operation context.
- **Expected:** Keep arbitrary exception/cause messages private, but report bounded structured context (`screen`, `action`, `operation`, `stage`, `outcome`, `reason_code` and useful bounded dimensions) plus a diagnostic-only operation correlation ID and preceding breadcrumbs.
- **Actual:** Typed call sites retain safe structured context and can emit breadcrumbs, but legacy call sites still produce only generic context plus sanitized type/cause. A stack frame can identify source code, but not consistently the user-visible screen/action/stage/recovery outcome. Correlation IDs are not currently generated consistently.
- **Frequency:** Every exception reported through the current `logException` boundary.
- **Evidence:** `DiagnosticPayloadSanitizer.kt:14-16`; `AnalyticsManager.kt:18-23`; `DebugAnalyticsManager.kt:17-25`; `AndroidFileLogger.kt:24-36`; `DiagnosticPayloadSanitizer.android.kt:22-25`; `DiagnosticPayloadSanitizer.ios.kt:3-9`; `BooksListViewModel.kt` around the failure report (see `error.log` call).
- **Root cause:** Blanket redaction replaced legacy context rather than distinguishing it from trusted typed context; the former API had no breadcrumbs, and screen-level migration is incomplete.
- **Affected files:** Analytics API, common diagnostic sanitizer, Firebase/debug/file providers and platform throwable sanitizers; screen operation call sites and regression tests.
- **Fix reference / commit:** Typed `DiagnosticContext` API/provider sanitizer and Books load/import migration: `41c77953bbdbf15c5ab05008b0533e6ed3a85120`. Dedicated bounded `logBreadcrumb` in Firebase/debug providers and startup operation-stage breadcrumbs: `0e6713e57038fc6f8cab1a6c3684ffc2f4383614`. Private messages and throwable/cause messages remain omitted. This is not a repository-wide migration.
- **Retest:** PARTIAL. `:composeApp:testAndroidHostTest`, `:lib:analytics:implementation:testAndroidHostTest`, and `:androidApp:assembleDebug` succeeded; diagnostic sanitizer and startup fallback tests passed. Samsung APK matched local SHA-256 `069d68a5eee622b9bb24445995596495a5261ab0b3f128e1c41d2faf71ec4e82`; fresh-process local logs showed bounded `started` and `route_selected` breadcrumbs on startup with no fatal. No controlled failure was induced, so failure-context delivery was not observed. Crashlytics delivery remains BLOCKED by unavailable authorized Console access; broader call-site audit stays open.

## QA-BUG-0007 — Setting-change Analytics drops the selected value

- **Severity / impact:** Medium; setting-change events cannot explain which reader preferences users choose, undermining feature-usage analysis and preference defaults.
- **Status:** FIXED and retested on the Samsung using the local debug provider; Firebase ingestion is waived/unverified.
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
- **Fix reference / commit:** Provider-boundary allowlist normalizes values into approved categorical/numeric/color buckets, enforces known setting names, and drops raw `new_value`. Commit `26c8e31432e31c9728e6e823ad5444789d987e93`.
- **Retest:** PASS for local debug behavior on 2026-09-26. Host tests cover enum/boolean/numeric/custom-font/color categories, reject unknown settings/out-of-range values, and passed with zero failures; debug build succeeded. Installed APK matched local SHA-256 `82a2429d001b6538061480bcdb6a18fa5fcc0fa4fff0449c991a1e84cfd267bf`; on-device event was `{setting_name=theme, value_bucket=dark}` with no `new_value`. Evidence: `qa-bug-0007-retest.txt`. A UI screenshot was intentionally not retained because the underlying reader preview showed content unrelated to this analytics assertion. Firebase Analytics ingestion is waived and not claimed.

## QA-BUG-0008 — Startup/Home exposure has no launch Analytics event

- **Severity / impact:** Medium; launch sources, cold/warm route outcomes and initial destination exposure cannot be measured, weakening navigation attribution and launch/recovery analysis.
- **Status:** FIXED and retested on the Samsung. Analytics event evidence is from the local debug provider only, not Firebase ingestion.
- **Screen/test IDs:** Cases 1–3, 13–14; setup 506–507; startup and Home entry.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; `com.retro99.parrot` v0.4.5 (21), installed APK SHA-256 `b1f6d3ed57189df9cafb001a154c490526078687150331e479bd3680c080595e`; source checkout before test docs commit `c0c9dccf` (installed binary remains the QA-BUG-0005 build from `d1e18128`).
- **Preconditions:** App already provisioned and signed in; launch to the existing Home/Books destination.
- **Exact reproduction:** On 2026-09-26, set Samsung locale to `es-ES`, force-stop and launch `com.retro99.parrot`, then inspect Books and switch through Series, Statistics and Settings. PID-scoped local Analytics output contains `tab_switched` for explicit tab changes and `statistics_viewed`, but no launch/startup/Home-exposure event for the app launch. Source inventory finds `TabSwitched` and `StatisticsViewed` event types, but no `screen_view` or app-launch event; `HomeNavigationViewModel` emits `TabSwitched` only in response to a tab intent. Locale was restored to `en-GB` after the run.
- **Expected:** One exposure/launch event per actual visible destination entry, with a stable source/entry point and cold/warm route outcome; not per recomposition. Welcome/startup route events should be recorded when those destinations are reachable.
- **Actual:** Launching directly into Home/Books has no corresponding screen-exposure/launch event. Tab changes and Statistics exposure do emit local events.
- **Frequency:** Every app launch/initial route exposure; exact current event omission is deterministic by source audit and reproduced once on the installed build.
- **Evidence:** `case-013-locale-*.png`; `case-013-locale-run.txt`; source `HomeNavigationViewModel.kt` around `checkOpenLastBookOnLaunch` and tab intent handling; Analytics event catalogue has `TabSwitched` and `StatisticsViewed` but no launch/screen-view event. The PID-scoped log excerpt in the run evidence is sanitized and excludes request/user payloads.
- **Root cause:** Navigation analytics cover explicit tab switching and selected screens but not the startup route/first visible Home destination; no event is tied to actual route visibility.
- **Affected files:** `feature/home/ui/.../HomeNavigationViewModel.kt`, `composeApp/.../RootNavigationViewModel.kt`, `lib/analytics/api/.../BookAnalyticsEvent.kt`, and associated tests.
- **Fix reference / commit:** Added typed `NavigationAnalyticsEvent.AppLaunchRouteResolved` (`app_launch_route_resolved`) at the completed startup auth-route decision, with bounded `screen`, `source_screen`, `entry_point`, and `outcome` parameters. Commit `72c47ff864700f10f522db84ea7b74ff9f62ac17`.
- **Retest:** PASS on 2026-09-26: `:lib:analytics:implementation:testAndroidHostTest` and `:androidApp:assembleDebug` succeeded. Installed APK hash `b20450020aa372fd8ad57d77791dd975ade482cf15e072793f22b7367ed882f4` matched the local build. Samsung relaunch produced exactly one `app_launch_route_resolved` local debug event with `{screen=home, source_screen=splash, entry_point=app_launch, outcome=success}`; no fatal exception was present. See `case-013-launch-event-retest.txt`. Firebase ingestion remains unverified per user waiver.

## QA-BUG-0009 — Startup auth-state exception can leave Splash indefinitely

- **Severity / impact:** High; an unexpected preferences/server-registry failure during startup can prevent the user reaching Welcome or Home, leaving the app stuck at Splash.
- **Status:** Fix implemented; normal startup retested on Samsung and failure/fallback behavior passed in Android host test. Device fault injection and Crashlytics delivery remain BLOCKED; source-level defect is considered addressed but those verification gaps remain.
- **Screen/test IDs:** Startup cases 1–3, 13–14; setup diagnostic failure case 508.
- **Device/build/commit:** Source audit at `72c47ff8`; Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0, package `com.retro99.parrot` v0.4.5 (21). Failure branch was not induced on-device.
- **Preconditions:** `CheckAuthStateUseCase` throws while reading the skipped-login preference or authenticated server registry.
- **Exact reproduction:** Source trace: `RootNavigationViewModel.checkAuthState()` launches `checkAuthStateUseCase()` without a try/catch, then updates `backStack` only after it returns. Initial `RootNavigationState` contains only `Splash`; therefore an exception bypasses the state update and leaves Splash as the only route. Runtime frequency unknown; not fault-injected.
- **Expected:** Preserve coroutine cancellation; for an unexpected auth-state check failure, report one non-fatal with bounded screen/action/operation/stage/reason context, record a fallback route outcome, and leave the user at usable Welcome/login with a retry path rather than a stuck Splash.
- **Actual:** Unhandled exception escapes the coroutine and no fallback destination or operation-context report is produced; Splash can remain indefinitely.
- **Frequency:** Unknown; conditional on startup preference or registry access throwing.
- **Evidence:** `composeApp/src/commonMain/kotlin/com/retro99/parrot/navigation/RootNavigationViewModel.kt:45-57`; `feature/auth/domain/src/commonMain/kotlin/com/retro99/auth/domain/usecase/CheckAuthStateUseCase.kt:20-25`; `RootNavigationState` defaults to Splash. Fault-injection evidence unavailable.
- **Root cause:** Startup state resolution is not exception-safe and has no failure route or boundary-level structured diagnostic.
- **Affected files:** Root navigation ViewModel, startup route Analytics event, diagnostic context API, and startup route resolver tests.
- **Fix reference / commit:** `resolveStartupAuthState` rethrows `CancellationException`, reports other `Exception` failures once with `DiagnosticContext` (`reason_code=auth_state_check_failed`), routes to Welcome, and records `outcome=fallback` / reason in the launch-route event. Commit `ca9e67791342b1bb798b81449cbf7d28981251db`.
- **Retest:** Android host tests PASS (3 cases: normal authenticated route, exception fallback/report-once, cancellation rethrow/no report; zero failures). Samsung normal startup PASS on 2026-09-26: Books/Home became usable and one local `app_launch_route_resolved` success event appeared; no fatal exception. Installed and local APK SHA-256 matched `24af701b53ff5b76b69baba7f54cee0e044f98dff9a083d6e5dea74801155ee9`. Fault injection on the device remains BLOCKED; no Firebase Crashlytics delivery claim. Evidence: `qa-bug-0009-retest.txt`. A Home screenshot was intentionally not retained because book titles/covers were visible and were not needed for the startup assertion.

## QA-BUG-0010 — Welcome exposure and entry outcomes are not instrumented

- **Severity / impact:** Medium; first-run users' Welcome exposure and the choices that lead to login or guest mode cannot be measured, and broken onboarding transitions cannot be distinguished from abandonment.
- **Status:** Confirmed by source audit; runtime has not yet been exercised on a clean install.
- **Screen/test IDs:** Startup/Welcome cases 1, 4–7, 10, 13–14, 521–524; setup 508 and 747.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16/API 36, One UI 8.0; `com.retro99.parrot` 0.4.5 (21), debug; source at `0e6713e57038fc6f8cab1a6c3684ffc2f4383614`, installed APK SHA-256 `069d68a5eee622b9bb24445995596495a5261ab0b3f128e1c41d2faf71ec4e82` before the instrumentation fix.
- **Preconditions:** Reach Welcome after startup resolves to the unauthenticated route; tap Get Started or Browse without account.
- **Exact reproduction:** Source audit: `RootNavigationViewModel` emits `app_launch_route_resolved` for the selected destination, but `LoginNavigation`/`WelcomeScreen` do not emit a Welcome screen-view event. `LoginNavigationViewModel` handles Get Started (`NavigateTo(Login)`) and guest selection (`OnSkipLoginClicked`) without action-attempt or completion Analytics events. No event identifies an actual visible Welcome exposure or distinguishes these entry choices.
- **Expected:** Emit one Welcome exposure event when the destination is actually visible; record Get Started and guest-mode attempt separately from successful route/state commitment; avoid events on recomposition or duplicate taps.
- **Actual:** Startup route resolution is recorded, but Welcome exposure and both user-action outcomes are absent from Analytics.
- **Frequency:** Every Welcome exposure and accepted action.
- **Evidence:** `composeApp/src/commonMain/kotlin/com/retro99/parrot/navigation/RootNavigationViewModel.kt`; `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/navigation/LoginNavigation.kt`; `LoginNavigationViewModel.kt`; `WelcomeScreen.kt`. Catalogue defines first-install, Get Started, guest and repeated-action coverage at the IDs above.
- **Root cause:** Instrumentation was added at the root auth-route decision, but not at the nested login navigation route visibility or Welcome action/state-transition boundaries.
- **Affected files:** Login navigation ViewModel/Compose entry, Auth Analytics event model, analytics provider sanitizer regression tests and QA event dictionary.
- **Fix reference / commit:** Pending.
- **Retest:** NOT RUN. Requires a clean Samsung install, local debug-provider inspection for exactly one Welcome exposure plus accepted action/outcome events, and verification that credentials/private data are absent. Firebase Analytics delivery is waived by the user; local logs will not be described as Firebase delivery.
