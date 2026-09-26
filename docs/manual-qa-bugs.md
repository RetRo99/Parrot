# Parrot QA bug register

Keep every entry, including fixed and duplicate observations. These first findings are confirmed by source inspection; whether the current Firebase build actually delivered the sensitive values is unverified until the blocked runtime payload checks are performed.

## QA-BUG-0001 — Analytics event parameters are forwarded without privacy filtering

- **Severity / impact:** Medium; potential privacy exposure and high-cardinality telemetry, and values can also appear in debug logs.
- **Status:** Fixed in source; Android host tests and Samsung debug-provider retest PASS; Firebase verification BLOCKED; commit hash pending.
- **Screen/test IDs:** Setup 509, 511; applies across all event-producing screens.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; installed package `com.retro99.parrot` v0.4.5 (21), debuggable, installed commit unknown; source under audit `956ec8a443d2236396c490a3056efdf9a1181e32`.
- **Preconditions:** Emit any event type with free-form/high-cardinality parameters.
- **Reproduction:** Source inspection: `AnalyticsManager.logEvent` forwards `event.parameters` unchanged to Firebase; `DebugAnalyticsManager.logEvent` writes the complete parameter map. Event types include `book_uuid`, `profile_name`, `font_name`, `error_message`, and `server_url_hash`.
- **Expected:** Analytics/debug telemetry excludes private or high-cardinality values; stable event names and safe categorical parameters remain usable.
- **Actual:** The event map is passed through unchanged. Firebase receipt of any particular value has not yet been independently observed.
- **Frequency:** Every emission of an affected event, if the parameter is populated.
- **Evidence:** Original pass-through at `AnalyticsManager.kt:29-32` and `DebugAnalyticsManager.kt:31-39` on `956ec8a`; affected events include `AnalyticsEvent.kt:21-29,133-144` and `BookAnalyticsEvent.kt:276-283`. Retest excerpt: [`setup-debug-provider-events.txt`](manual-qa-evidence/2026-09-26/setup-debug-provider-events.txt); host test report: `lib/analytics/implementation/build/reports/tests/testAndroidHostTest/index.html`.
- **Root cause:** No shared parameter redaction/allowlist at either analytics provider boundary.
- **Affected files:** `lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsParameterSanitizer.kt`, `AnalyticsManager.kt`, `DebugAnalyticsManager.kt`, test configuration and `AnalyticsParameterSanitizerTest.kt`.
- **Fix reference / commit:** Added fail-closed provider-boundary filtering; only registered bounded categorical/numeric/boolean dimensions are emitted. Commit hash pending.
- **Retest:** PASS — three Android host unit tests; Android debug/release builds successful; Samsung debug APK installed and hash-matched; cold-start emitted local events with approved fields only. Aggregate iOS `allTests` was BLOCKED by missing `FirebaseCore`; Android host tests pass. Firebase DebugView payload remains BLOCKED.

## QA-BUG-0002 — Exception diagnostics include raw throwable/message context

- **Severity / impact:** Medium; exception text/cause chains may contain private paths, request details or user input in diagnostic reporting.
- **Status:** Confirmed source-level instrumentation defect; fix pending; Crashlytics delivery not verified.
- **Screen/test IDs:** Setup 506, 509, 510; applies to all exception-reporting screens.
- **Device/build/commit:** Samsung `RFCWC0SSVDM` / SM-S921B, Android 16, One UI 8.0; installed package `com.retro99.parrot` v0.4.5 (21), debuggable, installed commit unknown; source under audit `956ec8a443d2236396c490a3056efdf9a1181e32`.
- **Preconditions:** A handled exception is reported with an optional message or a sensitive throwable message/cause.
- **Reproduction:** Source inspection: production `AnalyticsManager.logException` logs `message` to Crashlytics and passes the original throwable to `recordException`; debug provider logs the throwable and message.
- **Expected:** Preserve actionable operation context and useful stack/cause while sanitizing free-form throwable/message content; report once at the impact-aware boundary.
- **Actual:** Raw message and throwable are passed to the reporting/logging APIs. Runtime content and report count have not yet been observed.
- **Frequency:** Every reported handled exception with free-form context.
- **Evidence:** `lib/analytics/implementation/src/commonMain/kotlin/com/retro99/analytics/implementation/AnalyticsManager.kt:18-26`; `DebugAnalyticsManager.kt:17-28`.
- **Root cause:** No diagnostic context/throwable sanitization boundary.
- **Affected files:** Provider implementations and diagnostic sanitization/tests (fix pending).
- **Fix reference / commit:** Pending.
- **Retest:** NOT RUN. Required: sanitizer tests, one controlled failure per boundary, deduplication check and Firebase Crashlytics issue with useful/sanitized stack/context.

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
