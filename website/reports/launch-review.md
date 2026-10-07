# Launch review (report only)

No app/backend changes or deployment were made. Legal source Markdown is unchanged.

## Proposed privacy corrections for owner approval

- §3 Analytics: “The app sends Firebase Analytics events about feature use, reading/listening duration, navigation, settings changes, library operations, sync and Cloud account operations. Custom event parameters are restricted to approved categories, counts, durations and outcomes, rather than book text, titles, search text, server addresses or credentials. Firebase also collects SDK device, app and installation information.” See `analytics-inventory.json` for 191 code-defined custom event names; declarations do not prove every event is emitted. Advertising identifiers require verification on the final release: existing merged debug manifests include advertising-ID permissions, and no release configuration was verified to disable that collection.
- §3 Crashlytics: “Crashlytics receives crash and error reports, device/app information and diagnostic breadcrumbs. Handled errors reported through Parrot’s analytics wrapper have exception messages removed and their context restricted to approved fields. Parrot does not currently attach your Cloud account ID.” Login clears user identity; no production non-null `setUserId` call was found. SDK installation identifiers still exist.
- §3 Kotzilla: “Kotzilla receives technical monitoring data, including app and SDK versions, device/OS information, session identifiers, dependency resolution and lifecycle events, performance measurements and crash diagnostics.” The cached Android SDK exposes device manufacturer/name/OS/version in its session schema and forwards uncaught exception stack traces. Vendor documentation describes dependency graphs, resolution timing, component lifecycles and Android UI states. Verify the exact release SDK/configuration and a captured payload before declaring this exhaustive.
- Replace the absolute “These do not include the text of your books” with: “Parrot does not intentionally include book text in custom usage events. Handled-error reports are sanitized, but automatic crash reporting can include exception messages; we cannot guarantee that every crash report excludes book text or other user content.” The Android local crash handler forwards the original throwable to the previous SDK handler. Kotzilla’s cached crash handler calls `Throwable.printStackTrace`; the app wrapper cannot sanitize that path. Do not claim guaranteed exclusion without release testing or a separate app fix.
- §10: “Deleting your account removes its Cloud data and removes your account attribution from retained file-operation audit records. Previously submitted analytics and crash reports follow the providers’ retention and deletion rules.” Audit cleanup uses a 180-day threshold; it is scheduled cleanup, not proof of an exact wall-clock deadline. Retention for provider logs/backups remains unresolved.
- §12: “Connections to Parrot Cloud use HTTPS.” Encryption at rest, dashboard/admin access and provider safeguards cannot be settled from repository code. Do not remove those confirmations yet.

The wrapper sanitizers are in `lib/analytics/implementation/.../AnalyticsParameterSanitizer.kt` and `DiagnosticPayloadSanitizer.kt`; identity clearing is in `feature/login/ui/.../LoginAnalyticsIdentity.kt`; fatal forwarding is in `lib/analytics/implementation/src/androidMain/.../CrashFileLogging.android.kt`.

## Dashboard checks the owner must perform

| Item | Where to look / evidence needed |
| --- | --- |
| Supabase region | Select the production project → Project Settings → General/project details; record the actual AWS region. If not shown, use the authenticated Management API project details or Supabase support. Edge Functions can execute outside the database region; check regional invocation configuration separately. |
| Supabase logs | Project → Logs/Logs Explorer; identify API gateway, Auth, Postgres, Storage and Edge Function logs. Check organization billing/plan and the applicable log-retention limits. Inspect function log statements and any configured drains; ask support for retention not exposed in the dashboard. Record metadata/content and IP exposure, not just duration. |
| Backups | Project → Database → Backups → Scheduled and Point-in-time; record plan, enabled PITR and actual recovery window. Inventory manual/off-site dumps separately. Database backups do not back up Storage object bytes; verify object-storage recovery/deletion separately. |
| Analytics retention | Firebase project → Analytics → linked Google Analytics property → Admin → Data collection and modification → Data retention. Record selected event/user retention, reset-on-activity, Google Signals/ads links and deletion settings. Check advertising-ID collection in the final release separately. |
| Crashlytics retention | Firebase → Crashlytics, plus Google’s Firebase privacy/retention documentation. Do not infer this from the Analytics retention dropdown. Google documents a 90-day period for Crashlytics data; verify current product terms and any exports/integrations. |
| Kotzilla retention | Kotzilla console → production app/workspace → settings and plan. Request the exact telemetry/crash retention and deletion/export rules from support if no retention control is visible; do not invent a dashboard field or assume the Analytics period applies. |
| Transfers/access | For each provider, retain the applicable DPA, subprocessors, processing regions and transfer mechanism. Supabase region alone does not establish all processing is in the EEA. Audit dashboard members/service-role access. Legal review must validate safeguards. |

Leave every `[DECIDE]` and `[LAWYER CHECK]` for the owner/legal reviewer.

## Recap provider replacement

The gateway currently uses OpenCode Go’s OpenAI-compatible chat endpoint. Replacing the URL/key/model is only the first step: confirm commercial authorization, sign a DPA, obtain endpoint/model-specific no-training and zero-retention terms, disable incompatible persistence, and validate subprocessors/regions. Then adapt request/response handling if needed, test the two-million-character input limit against the model context window, truncation/chunking, multilingual quality, rate limits, cost, retries, timeouts and cancellation. Preserve text-expiry cleanup and deletion semantics, deploy secrets securely, and update the legal/provider disclosure before enabling accounts.

Candidates, not approved guarantees:

- OpenAI commercial API: no training by default unless opted in; Zero Data Retention requires approval and applies only to eligible endpoints/features. Default abuse-monitoring retention is not ZDR.
- Anthropic commercial API: no training by default; contractual ZDR arrangements are available with endpoint/feature exceptions. Default API retention is not a blanket ZDR guarantee.
- GroqCloud: documents no inference-content retention by default with reliability/abuse and opt-in persistence exceptions; obtain contractual ZDR and verify the selected model license and commercial agreement.

Never substitute a consumer subscription for an authorized commercial API. Estimate: 2–5 engineering days for one compatible provider plus testing; procurement/legal approval and any chunking redesign are additional.

Sources: [OpenAI data controls](https://platform.openai.com/docs/guides/your-data), [Anthropic commercial training policy](https://privacy.anthropic.com/en/articles/7996868-is-my-data-used-for-model-training), [Groq data controls](https://console.groq.com/docs/your-data), [Kotzilla SDK data](https://doc.kotzilla.io/docs/discover/sdkData/), [Kotzilla processing](https://doc.kotzilla.io/docs/discover/data/), [Firebase privacy](https://firebase.google.com/support/privacy), [Supabase backups](https://supabase.com/docs/guides/platform/backups).

## App work after launch (not implemented)

- Cloud account sign-up consent: `cloud_account_tos_checkbox`; make Terms and Privacy separately accessible links to `/terms` and `/privacy`. Retain consent semantics and update translated strings as needed.
- Cloud upload-rights confirmation: `cloud_backup_attestation_terms`; replace “Book Backup Terms” with owner-approved “Parrot Cloud book storage terms” and link `/terms#your-books-and-notes`. Internal backup identifiers need not be renamed merely to fix visible copy; audit other visible labels.
- Recap settings/privacy details: `recap_privacy_details`, `recap_privacy_details_title`, `recap_privacy_text_lead` and related `recap_privacy_*` strings; connect `/privacy#recaps` and reconcile “Deleted within 24 hours” with expiry followed by cleanup.
- Settings/account help: account-deletion destination `/support#delete-account`.
- Google Play/App Store when listings exist: privacy `/privacy`, support `/support`, web account deletion `/support#delete-account` where supported. No listing URLs or badges are available now.

## Optional telemetry controls estimate

- Settings switch: about 3–5 engineering days plus release QA. Persist the preference, enforce it before SDK startup and at each provider boundary, call Firebase collection controls on both platforms, handle Kotzilla initialization/stop semantics, and test fresh install/restart/offline/races. Decide separately whether crash reporting follows the switch. Disabling collection does not erase historical reports.
- Ask-first prompt: another 2–4 days plus legal/localization review. Default collection off until an explicit choice, allow refusal without loss of reading functionality, provide later withdrawal and versioned consent state, and ensure SDK initialization cannot transmit before consent.
- Extra time if Kotzilla cannot safely stop/restart or independently gate collection. Estimates are not legal advice; legal review decides which controls/basis are required.

## Before publication

Owner supplies domain, contact email, legal identity/address and legal effective dates; approves all corrections and resolves bracketed markers. Production builds intentionally fail until then. Reader and connected-library Day/Night captures are still needed (see README). Hosting is prepared for Cloudflare Pages but not deployed.
