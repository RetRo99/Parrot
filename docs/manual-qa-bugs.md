# Parrot QA bug register

Keep every entry, including fixed and duplicate observations. These first findings are confirmed by source inspection; whether the current Firebase build actually delivered the sensitive values is unverified until the blocked runtime payload checks are performed.

## QA-BUG-0001 — Analytics event parameters are forwarded without privacy filtering

- **Severity / impact:** Medium; potential privacy exposure and high-cardinality telemetry, and values can also appear in debug logs.
- **Status:** Fixed in source; Android host tests and Samsung debug-provider retest PASS; Firebase Analytics ingestion waived by user (not claimed).
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
- **Status:** Fixed in source; six Android host tests and Samsung debug local retest PASS; Crashlytics delivery blocked.
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

- **Severity / impact:** Medium; confirmed code path can link server usernames/URLs through deterministic, low-entropy identifiers shared with Analytics and Crashlytics. Actual Firebase delivery/payload has not been inspected.
- **Status:** FIXED in source; startup identity clearing passed on the Samsung debug provider. Successful-login identity clearing and Firebase payload inspection remain unverified.
- **Screen/test IDs:** Setup 509, 511; login cases 22–36 and 534.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; current package `com.retro99.parrot` v0.4.5 (21), debug. Fix commit `31ed740d3cbe8ffaf58ae49ff7706b90bcd1d22c`; latest installed instrumentation build is from `f8de389005c90e74d61577b568967d540eb1a501`.
- **Preconditions:** Successful credentials or OAuth login using a controlled test account/server.
- **Reproduction:** Source inspection confirmed `LoginViewModel` assigned username and URL hashes through `setUserId`; the code path is confirmed, Firebase acceptance/delivery is not.
- **Expected:** Use a documented non-identifying analytics identity compatible with the single-device measurement policy; do not derive a stable reporting identifier from username or server URL.
- **Actual:** Java `String.hashCode()` is a deterministic, unkeyed, low-entropy derivative and is sent unchanged as the Firebase user ID. Whether this is accepted by the configured project is not verified.
- **Frequency:** On each successful login; identifier remains stable for equal username/URL values.
- **Evidence:** `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/login/LoginViewModel.kt`; `lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsManager.kt`; Samsung startup log evidence at `docs/manual-qa-evidence/2026-09-26/login-preflight-build-check.txt`.
- **Root cause:** Hashing was treated as anonymization and reused as provider identity without a reviewed privacy policy.
- **Affected files:** Login ViewModel and analytics user-identity contract/providers.
- **Fix reference / commit:** Credentials/OAuth success clears provider identity through the approved `clearUserIdentity()` contract; root startup also clears stale identity. Commit `31ed740d3cbe8ffaf58ae49ff7706b90bcd1d22c`.
- **Retest:** PARTIAL — common identity contract unit test passed; full Android debug assembly passed; Samsung cold-start log showed `Set User ID: null (cleared)` after installation of the fix. Successful credentials/OAuth path is pending case 2/22; Firebase Analytics ingestion is waived and Crashlytics delivery remains separately blocked.

## QA-BUG-0004 — Failed login is reported to Crashlytics at repository and UI boundaries

- **Severity / impact:** Medium; expected credential/network failures can create duplicate Crashlytics non-fatals and exception noise for ordinary recovery.
- **Status:** FIX IMPLEMENTED in two commits; Samsung failure-path outcome/report count and Firebase Crashlytics delivery remain unverified.
- **Screen/test IDs:** Setup 510; login cases 23–24, 525, 534.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21); remediation commits `d97de1b8e2a7b5f84d203bfac14bf18f639b512f` and `51846059fc47d0ed9f14406c47a38698f353f253`.
- **Preconditions:** Login operation returns an `AppError` and reaches the ViewModel failure callback.
- **Reproduction:** Source inspection: `LoginDataRepository.login` logs each auth failure in `onFailure`; `LoginViewModel` then logs the same failure with `error.log(analytics, ...)`. The OAuth path has the same repository/UI pattern.
- **Expected:** Ordinary wrong credentials/network conditions produce a bounded outcome event and recovery breadcrumb, not Crashlytics non-fatals; any unexpected user-impacting failure is submitted once at the boundary with useful context.
- **Actual:** Two `logException` calls are reachable for the same failed login; the current provider records each call as a separate Crashlytics exception.
- **Frequency:** Each failed credentials/OAuth operation that reaches both layers.
- **Evidence:** `feature/login/data/src/commonMain/kotlin/com/retro99/login/data/LoginDataRepository.kt:35-41,70-80`; `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/login/LoginViewModel.kt:121-128,159-165`.
- **Root cause:** Both data and presentation boundaries independently report a failure that is propagated to the caller.
- **Affected files:** Login data repository, Login ViewModel and possibly auth error classification (fix pending).
- **Fix reference / commit:** Removed repository/authenticator exception submissions and centralized unexpected reporting at the Login recovery boundary; expected auth/transport outcomes are retained as typed outcome telemetry. Commits `d97de1b8e2a7b5f84d203bfac14bf18f639b512f` and `51846059fc47d0ed9f14406c47a38698f353f253`.
- **Retest:** Automated failure-policy tests, Login iOS simulator tests, both authenticator iOS simulator tests and Android debug assembly PASS. Samsung wrong-credential/transport runtime paths remain NOT RUN pending case 23/24; Firebase Crashlytics delivery remains BLOCKED. Do not claim runtime deduplication yet.

## QA-BUG-0012 — Login exposure and operation telemetry lack safe route/outcome context

- **Severity / impact:** Medium; login usage, method-specific outcomes, cancellation and retries cannot be reconstructed, and unexpected failures have no bounded Login operation breadcrumb context.
- **Status:** Instrumentation implemented and committed; Samsung Login action/event checks remain NOT RUN.
- **Screen/test IDs:** Login cases 15–36 and 521–534; setup 508–510, 534; overlaps QA-BUG-0006 for diagnostic context and QA-BUG-0003 for private identifiers.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16/API 36, One UI 8.0; `com.retro99.parrot` v0.4.5 (21), debug; instrumentation commit `b62deb359afef44a59b4423069e1de2693cbdc84`, installed build source through `f8de389005c90e74d61577b568967d540eb1a501`.
- **Preconditions:** Reach the Login destination from Welcome or Add Server; perform a credentials or OAuth attempt.
- **Exact reproduction:** `LoginNavigationViewModel.onDestinationVisible` emits only the success completion when Login follows Welcome; it does not emit a Login screen-view event. `LoginViewModel` emits `login_attempted` with only `server_url_hash`, which is dropped by the fail-closed sanitizer; `login_succeeded`/`login_failed` lack server type and auth method and there is no cancel/retry dimension. Its exception report uses the legacy free-form context overload, and no start/stage/terminal Login breadcrumbs are emitted.
- **Expected:** One bounded Login exposure per visible destination, source/entry attribution, server type and auth method on accepted attempts and terminal outcomes, explicit cancellation/retry distinction, and bounded breadcrumbs around authentication and local credential persistence. Never include URL, username, password, token or full callback URI.
- **Actual:** These source gaps are addressed. Local event delivery/counts on Samsung, success/failure/cancel/retry sequences and Firebase ingestion are not yet verified.
- **Frequency:** Every Login exposure/attempt/outcome.
- **Evidence:** `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/navigation/LoginNavigationViewModel.kt`; `LoginNavigation.kt`; `login/LoginViewModel.kt`; `lib/analytics/api/.../BookAnalyticsEvent.kt` AuthAnalyticsEvent; `AnalyticsParameterSanitizer.kt` approved key set.
- **Root cause:** Auth telemetry predates the provider privacy allowlist and was not migrated to bounded dimensions or actual route visibility; Login operation boundaries have not adopted the typed diagnostic context API.
- **Affected files:** Auth Analytics event hierarchy/sanitizer tests, login navigation/UI ViewModels, login repository and diagnostics tests.
- **Fix reference / commit:** Added bounded Login exposure, attempt/success/failure/cancellation/abandonment events, diagnostic-only correlation IDs, identity clearing on success, failure boundary diagnostics and sanitizer tests. Commit `b62deb359afef44a59b4423069e1de2693cbdc84`.
- **Retest:** PASS for forced analytics Android host tests, Login iOS simulator tests and Android debug assembly. Device Login event/breadcrumb checks remain NOT RUN and are scheduled with cases 2 onward; Firebase Analytics ingestion is waived, not claimed. Crashlytics delivery remains separately blocked.

## QA-BUG-0013 — Local server/credential persistence exception escapes the login flow

- **Severity / impact:** High; after successful remote authentication, a local registry/database failure can escape the coroutine, crash the app or strand the user in a perpetual loading state without a recovery message.
- **Status:** FIX IMPLEMENTED and regression-tested; controlled Samsung persistence-failure verification is BLOCKED because no safe local registry/preferences fault-injection fixture is available.
- **Screen/test IDs:** Login success cases 22, 25, 31–32, 525–534; new persistence-failure case 753; diagnostic setup cases 506, 508, 510.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16/API 36, One UI 8.0; `com.retro99.parrot` v0.4.5 (21), debug; fix commit `dc005fa5f61d431fd2ac56dd739771233dc4d4c8`, latest build through `f8de389005c90e74d61577b568967d540eb1a501`.
- **Preconditions:** Authenticator returns valid credentials; `ServerRegistry.addServer` or `saveCredentials` throws during local persistence.
- **Exact reproduction:** Historical source trace: `LoginDataRepository.login` and `loginWithOAuth` composed `addServer`/`saveCredentials` with a non-catching `flatMap`, and `LoginViewModel` allowed thrown exceptions to escape its launched coroutine. The fix now catches unexpected persistence errors at the repository boundary and also guards the UI operation boundary.
- **Expected:** Preserve coroutine cancellation; convert unexpected persistence exceptions to a recoverable result, keep the user on Login, terminate loading, report once with bounded `screen=login`, operation/stage/reason context, and permit retry. Do not route Home until persistence completes.
- **Actual:** Source fix returns a bounded `DatabaseError`, compensates a created server when credential storage fails, restores in-memory maps after failed preferences writes, and routes unexpected throws through Login's recoverable failure UI. Device fault injection remains blocked.
- **Frequency:** Conditional on local server-registration/credential persistence failure after remote auth succeeds.
- **Evidence:** Historical source path in `LoginDataRepository.kt`; regression tests in `feature/login/data/src/commonTest/.../LoginPersistenceTest.kt`, `feature/login/ui/src/commonTest/.../LoginOperationTest.kt` and `lib/server/implementation/src/commonTest/.../PersistStateMutationTest.kt`.
- **Root cause:** Remote auth success and local preference-backed registration/credential writes had no compensating persistence boundary or Login recovery conversion.
- **Affected files:** LoginDataRepository/use case/ViewModel and login repository/UI tests; typed diagnostics.
- **Fix reference / commit:** `persistLoginCredentials` converts persistence failures into a bounded recoverable result, compensates server registration, preserves cancellation, and marks rollback failure distinctly; registry server/credential snapshots are restored when preference writes fail; UI operation throws no longer strand loading. Commit `dc005fa5f61d431fd2ac56dd739771233dc4d4c8`.
- **Retest:** PASS — Login data/UI and Server Registry iOS simulator tests plus Android debug assembly all succeeded, covering thrown credential-write failure, compensation, rollback-failed classification, in-memory rollback, success and cancellation rethrow. Samsung case 753 functional/diagnostic failure injection is BLOCKED by the unavailable safe fault fixture; normal Login success will be checked in case 2/22 but is not a substitute. Crashlytics delivery remains unverified.

## QA-BUG-0014 — Rapid Login intents may start parallel authentication attempts

- **Severity / impact:** Medium; an input race may create duplicate server registrations, credential writes or external OAuth launches and multiply attempt/outcome events.
- **Status:** SUSPECTED; atomic single-flight remediation committed and unit-tested; no Samsung repeated-tap reproduction/retest yet.
- **Screen/test IDs:** Login cases 36, 526–527; setup 510 and 534.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16/API 36, One UI 8.0; `com.retro99.parrot` v0.4.5 (21), debug; remediation commit `f8de389005c90e74d61577b568967d540eb1a501`.
- **Preconditions:** Valid login form; a delayed server response; deliver multiple Sign In/IME or OAuth intents before recomposition disables the button.
- **Exact reproduction:** Source audit: the UI disables buttons based on `isLoading`, but `LoginViewModel.handleSignInClicked` and `handleOAuthSignInClicked` do not check current `isLoading` before launching a new coroutine. `BaseViewModel.onIntent` dispatches directly without serialization. Whether real Samsung input can win this timing window remains unverified.
- **Expected:** Atomically accept at most one in-flight credentials or OAuth attempt; one terminal result and no duplicate server/account registration.
- **Actual:** Source now uses an atomic `MutableStateFlow.compareAndSet` gate shared by credentials and OAuth; device timing behavior remains unverified.
- **Frequency:** Unknown; expected to require rapid input on a delayed flow.
- **Evidence:** `LoginViewModel.kt:73-79,93-110,134-150`; `BaseViewModel.kt:16-22`; catalogue cases 36 and 526.
- **Root cause:** UI disabled-state is the only duplicate-submit control; ViewModel intent handlers do not enforce idempotent single-flight semantics.
- **Affected files:** Login ViewModel and its regression tests.
- **Fix reference / commit:** Added shared atomic submission gate, released on recoverable failures, plus a retry-after-failure regression test. Commit `f8de389005c90e74d61577b568967d540eb1a501`.
- **Retest:** PASS for forced Login iOS simulator tests and Android debug assembly; Samsung delayed-response rapid submit remains NOT RUN pending case 526 and a controlled slow endpoint. Because the original race was only suspected, keep runtime disposition open until that check.

## QA-BUG-0015 — Login credential-persistence failure breadcrumb is mislabeled as authentication

- **Severity / impact:** Low; the user receives the intended recoverable Login error, but a handled local persistence failure is attributed to remote authentication in the preceding breadcrumb, making failure-path diagnosis less reliable.
- **Status:** CONFIRMED by source audit; no persistence failure has been injected on-device. Fix not yet applied.
- **Screen/test IDs:** Login cases 22, 25, 34, 36, 534 and 753; diagnostic setup 506, 508 and 510.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16/API 36, One UI 8.0; package `com.retro99.parrot` v0.4.5 (21), debug; source at `0c94e85e3f116fb68dfc4f7da323a8e6aab3cba3`, installed APK SHA-256 `ca6a342c1aafa9c1677d9db149f9c1e1fac46deff27bd54b3d8a4077e88a3249`.
- **Preconditions:** Authentication succeeds remotely, then `ServerRegistry.addServer` or credential persistence returns the recoverable `AppError.DatabaseError` created by `persistLoginCredentials`.
- **Exact reproduction:** Source trace: `persistLoginCredentials` maps local registration/credential-write exceptions to `AppError.DatabaseError`; `LoginViewModel.failLoginAttempt` then emits a terminal diagnostic breadcrumb with `stage="authentication"` and `reasonCode="database_failure"` for every non-cancellation failure. The same operation's Crashlytics exception context in `LoginFailureDiagnostics` correctly uses `stage="credentials_persistence"`, so breadcrumb and issue context disagree.
- **Expected:** Emit the failure breadcrumb with `stage="credentials_persistence"` and a bounded persistence reason (`local_database_failure` or `server_registration_rollback_failed`), aligned with the single exception report. Keep ordinary auth rejection/network/cancellation classifications unchanged.
- **Actual:** The failed operation is represented as an authentication-stage/database_failure breadcrumb, while the exception context identifies credential persistence.
- **Frequency:** Every caught persistence error while storing a successful credentials/OAuth login.
- **Evidence:** Source at `feature/login/ui/src/commonMain/kotlin/com/retro99/login/ui/login/LoginViewModel.kt` (`failLoginAttempt`) and `LoginFailureDiagnostics.kt`; report-time device fault fixture is unavailable and has not been fabricated.
- **Root cause:** Terminal breadcrumb context uses the generic authentication stage and Analytics error bucket instead of operation-stage/reason mapping for `DatabaseError`.
- **Affected files:** Login UI ViewModel and Login failure diagnostics tests.
- **Fix reference / commit:** Pending.
- **Retest:** NOT RUN. Add focused mapping tests, force Login/UI tests and Android assembly, then exercise a controlled Samsung case-753 failure if a safe fixture becomes available. No fix may be claimed on-device without that retest.

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
- **Status:** IMPLEMENTED; Welcome exposure event has passed a Samsung first-install check. Get Started and guest-mode attempt/completion outcomes still require on-device retest under cases 4–5.
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
- **Fix reference / commit:** Bounded Welcome view/action events plus route-visible and guest-preference-persisted breadcrumbs; commit `eead19774c367f93675e5d775cc7ad7a5f038e4b`.
- **Retest:** PARTIAL PASS on Samsung first install, case 1, 2026-09-26. APK hash matched `059d4d881adb604310d8301c8de47cd979616d3f9738d0dcfdd58d90ced7620b`; exactly one local `welcome_screen_viewed` event and matching Welcome-visible breadcrumb followed the startup route. Host sanitizer tests and Android build passed. Case 4 Get Started and case 5 guest action events are not yet tested. Firebase Analytics delivery is waived/not claimed. Evidence: `case-001-first-install-run.txt`, `case-001-first-install-welcome.png`.

## QA-BUG-0011 — Debug Welcome build badge is absent in the debug APK

- **Severity / impact:** Low; QA/debug users cannot visually distinguish the debug build from a release build at the Welcome screen, reducing the chance of identifying the wrong variant during testing.
- **Status:** FIXED and retested on the Samsung debug variant. The separate release-badge comparison in case 7 remains NOT RUN.
- **Screen/test IDs:** Welcome case 7 (debug variant); observed incidentally while capturing case 1.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16/API 36, One UI 8.0; `com.retro99.parrot` 0.4.5 (21), debug APK at commit `eead19774c367f93675e5d775cc7ad7a5f038e4b`, SHA-256 `059d4d881adb604310d8301c8de47cd979616d3f9738d0dcfdd58d90ced7620b`; package metadata reports `DEBUGGABLE`.
- **Preconditions:** Fresh install the debug APK and launch to Welcome.
- **Exact reproduction:** On 2026-09-26, uninstall only Parrot, install the hash-recorded debug APK, and launch. Inspect the top-right Welcome area. The installed package has `DEBUGGABLE` in `dumpsys package`, but no DEBUG badge appears in the captured Welcome screen.
- **Expected:** Welcome shows the DEBUG badge in a debuggable build; release comparison is still pending.
- **Actual:** No visible DEBUG badge on the debug Welcome screen.
- **Frequency:** 1/1 observed fresh-install runs; repeatability to be checked after diagnosis.
- **Evidence:** `case-001-first-install-welcome.png`; package/build and screen observations: `case-001-first-install-run.txt`.
- **Root cause:** The ViewModel consumed the named Boolean instead of reading the registered `BuildConfig` directly. The named Boolean path resulted in `isDebug=false` in the Welcome state despite the debug package flag; injecting `BuildConfig` and reading its property fixes the state on device. The underlying Koin named-binding mismatch was not independently reproduced outside this ViewModel.
- **Affected files:** `feature/login/ui/.../LoginNavigationViewModel.kt`, `LoginNavigation.kt`, `WelcomeScreen.kt`; platform BuildConfig/Koin binding if the runtime flag is false.
- **Fix reference / commit:** `LoginNavigationViewModel` now injects `BuildConfig` directly and initializes `LoginNavigationState.isDebug` from `buildConfig.isDebug`; added a common test for debug/release state mapping. Commit `b720ca69b9b76454af98b620adc5b1bcb7ac75f7`.
- **Retest:** PASS for the debug variant on 2026-09-26. `:feature:login:ui:iosSimulatorArm64Test` passed 1 state test; analytics Android host tests passed 12/12; Android UI module compiled and debug APK assembled. Samsung APK matched local hash `b6dd4a4cee8df560e3c5ed8097f3a14407c58703f777d29edb5d8322be167092`; text selector found Get Started and screenshot visibly shows DEBUG. Local Welcome exposure event/breadcrumb remained present and no fatal was observed. Evidence: `qa-bug-0011-debug-badge-retest.txt` and `.png`. Release variant portion of case 7 remains NOT RUN; no Firebase Analytics delivery is claimed.
