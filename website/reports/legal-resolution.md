# Legal placeholder resolution

Owner identity/launch decisions: publisher name Lunaria, location Litija,
Slovenia, no business yet; Cloud minimum age 16 and closure notice 60 days
approved. Requested effective date is today, 2026-10-07; source front matter now
records that last-updated date and states the intended effective date. Draft
notices remain while the legal operator's actual identity and complete postal
address are unresolved. Lunaria is not presumed a registered company or the
owner's legal name, and Litija alone is not presumed a complete postal address.

Correction: separate app-integration permission is not treated as an established
requirement or launch blocker. The owner considers ordinary embedded API use
intended; no specific prohibition of Parrot's integration has been established.
The earlier confirmation-request template is optional on that point, not evidence
that approval is required. DPA and transfer safeguards remain separate checks.

## DeepInfra migration update

Rollout update 2026-10-07: CLI confirmed `DEEPINFRA_API_KEY` exists (only its
digest is exposed). Temporarily set `RECAP_ENABLED=false`, set the Nemo model,
successfully deployed both `generate-recap` and `recap-worker` to project
`wtvwvhehsqxpexshicsr`, then restored `RECAP_ENABLED=true`. No database migration
was needed. All 85 local Edge Function tests passed with mocked provider calls.
Real delivery, recap quality, billing and runtime latency remain untested. The
old Go secret was not removed or revoked. Earlier local-only status below is
historical and superseded by this rollout record.

Live synthetic smoke test subsequently passed against this project using
`supabase/tests/hosted_recaps_smoke_test.py`: Auth, required consent, concurrent
duplicate submission, conflicting-input rejection, automatic worker wakeup,
provider completion with bounded nonempty summary, exactly one quota charge,
input-text scrubbing, recap deletion and consent withdrawal. Temporary account
and its cloud data were deleted. No real user book was submitted. This validates
the end-to-end short English request, not semantic quality, Slovenian output,
long-context behavior or actual provider billing totals.

Owner authorized switching recaps to the cheapest listed DeepInfra text model.
Local provider code now uses `mistralai/Mistral-Nemo-Instruct-2407` through the
fixed `https://api.deepinfra.com/v1/openai/chat/completions` endpoint and requires
`DEEPINFRA_API_KEY`. No deployment or real provider call has occurred. Old Go
model settings fail closed; there is no fallback to Go.

The model page lists Apache 2.0, 131,072-token context and zero content retention:
https://deepinfra.com/mistralai/Mistral-Nemo-Instruct-2407/api . Standard pricing
checked 2026-10-07 is $0.019 input / $0.03 output per million tokens. DeepInfra's
privacy documentation distinguishes inference content from debugging metadata:
https://docs.deepinfra.com/account/data-privacy . DPA, transfer arrangements,
metadata retention and release-quality testing remain unresolved.

Chunks are now 24,000 UTF-16 units, conservatively at most 72,000 UTF-8 bytes
before prompt overhead. The latest-session limit is reduced from two million to
192,000 units to preserve the bounded map-call budget. Actual deployed behavior
is unchanged until rollout. Earlier Go facts below describe the previous source
and possibly still-live deployment, not the newly selected provider.

Proposed disclosure after verified rollout: “When you request a recap, we send
the selected recent reading text to DeepInfra, using Mistral Nemo, to generate
the summary. DeepInfra states that standard inference inputs and outputs are
held in memory during processing, then deleted, and are not used for training.
It retains separate debugging metadata.” Do not replace unresolved transfer
and metadata-retention details with a blanket claim of no data retention.

Prepared 2026-10-07. Not legal approval. Keep the publication gate enabled.
Owner approved factual source updates: privacy and terms now identify DeepInfra,
qualified retention claims and exceptions, Supabase location/log limits, Google
retention/settings, historical Kotzilla processing, website/support providers and
the confirmed support email. Identity and legal-review markers remain. Earlier
proposed text below is historical evidence, not necessarily the current wording.

Website validation after these edits: all 20 unit tests pass; all eight pages
build successfully in draft-preview mode; `git diff --check` passes. No website
deployment, commit or push occurred. DeepInfra's current official terms also
require account-specific app-integration/DPA/transfer confirmation; an unsent
owner email template is in `deepinfra-confirmation-request.md`.

## Facts now established

- Domain: `parrotapp.dev`.
- Supabase production project: owner-shared General settings show **West EU
  (Ireland)** and the **Free** plan. Project access shows two organization members:
  one Owner and one Administrator. This confirms dashboard access roles, not an
  exhaustive inventory of service keys, database roles or provider access.
- Supabase Database → Backups, as shared by the owner, states **Free Plan does
  not include project backups**. No paid scheduled-backup window is enabled in
  the shown screen. This does not establish absence of manual exports, PITR,
  Storage recovery features or provider-internal recovery copies.
- Owner reports no manual database exports/backups. Settings → Log Drains shows
  an upgrade requirement on this Free project, with no configured external drain
  shown. Built-in Supabase logs still exist; documented plan retention is below.
  Do not equate no log drains with no logging.
- Supabase's official pricing comparison, checked 2026-10-07 at
  https://supabase.com/pricing, lists Free-plan **API/database log retention of
  one day** and **Auth audit logs of one hour**. These are documented plan limits,
  not verified project payloads or blanket retention for every provider record.
  Edge Function logs and provider-internal security/recovery records remain
  separate checks. Free-plan PITR is also listed as not included.
- Owner-shared Google Analytics Data retention settings show **event data: two
  months**, **user data: fourteen months**. Owner confirms **Reset on new user
  activity is enabled**, so new activity can restart user-data retention.
  These controls do not cover
  most aggregated standard reporting or Crashlytics retention.
- Owner-shared Google Analytics Data collection screenshot shows a **Turn on**
  button for Google Signals: Google Signals is currently off. This does not
  establish whether SDK advertising identifiers or ads personalization are off.
- The next owner-shared screenshot confirms **granular location/device collection
  enabled in all 307 regions** and **ads personalization allowed in all 307
  regions**. This is configuration permission, not proof that data has been
  exported to advertising accounts. The collection-acknowledgement section shows
  an “I acknowledge” button; do not assert necessary user rights/consent based
  solely on these dashboard settings. No settings have been changed by the agent.
- Owner chooses to keep granular location/device collection enabled and ads
  personalization allowed. Final disclosures must reflect that choice; Google
  Signals remains off. Linked advertising accounts/data sharing and SDK consent
  settings still require verification. Dashboard permission alone does not prove
  actual advertising export or establish a lawful basis/consent.
- Owner-shared **Google Ads links** screen shows zero links in every status and
  “No links yet.” No Google Ads account link is configured in this property.
  This does not establish the state of other product links or account-level
  Google data-sharing settings.
- Owner-shared Account details screenshot confirms checked sharing options for
  **Modeling contributions & business insights**, **Technical support**, and
  **Recommendations for your business**. The Google products & services option
  was outside that screenshot; the follow-up below resolves its state. That page
  initially showed unaccepted Data Processing Terms; the owner's subsequent
  completion report below supersedes this initial state. Processor terms do not
  substitute for end-user consent.
- Follow-up Account details screenshot confirms **Google products & services
  sharing is unchecked/off**. Country of Business is set to **Poland** in this
  account. This is a dashboard setting, not evidence of the publisher's actual
  country; reconcile with the legal drafts' Slovenia assumption before approval.
- Owner confirms actual publishing country is **Slovenia**. The Analytics
  account initially had Poland selected. Shared DPA administration text initially
  contained no populated entries; subsequent owner confirmation follows below.
- Following the country/contact/terms checklist, owner reports completion:
  Analytics country corrected to Slovenia, DPA primary-contact details supplied,
  and applicable Data Processing Terms accepted. This is owner-reported, not
  independently dashboard-verified. It does not establish end-user consent,
  transfer-law compliance or completion of other providers' agreements.
- Owner reports **Firebase BigQuery integration/export is not enabled**. No
  retention period for a BigQuery export should be invented or disclosed as
  active. Other exports, integrations and provider retention remain separate.
- Contact/recipient inbox: `rok@parrotapp.dev` (owner supplied).
- Website host selected and configured: Cloudflare Pages; public deployment has
  not succeeded yet.
- Contact transport: Resend; owner dashboard shows domain verified and selected
  sending region Ireland (`eu-west-1`). This does not prove all processing stays
  in that region.
- Email destination: Google Gmail. `rok@parrotapp.dev` is the sending identity,
  not a configured receiving inbox.
- D1 binding configured by owner: `CONTACT_DB` → `parrot-contact`. Owner followed
  EU-jurisdiction setup; obtain dashboard confirmation before claiming residency.
- Handler stores hourly HMAC-derived IP keys, counts and expiry only. Five attempts
  per IP/hour; stale rows are deleted on subsequent valid requests, not necessarily
  at the expiry instant. Provider backups/logs are separate.

## App monitoring follow-up

Kotzilla SDK dependencies, Gradle instrumentation plugin/configuration and Koin
analytics startup have been removed from current source at the owner's request.
Android debug Kotlin compilation and release main-manifest processing passed
(`./gradlew :androidApp:compileDebugKotlin :androidApp:processReleaseMainManifest`).
iOS simulator Kotlin compilation also passed
(`:composeApp:compileKotlinIosSimulatorArm64`). Android release dependency insight
found no Kotzilla dependency in `releaseRuntimeClasspath`. Full iOS framework
linking/app execution and runtime payload testing have not been performed.
Older distributed builds are unchanged; historical
Kotzilla data is not erased by this source change. Keep existing-build disclosures
until rollout and provider deletion/retention have been addressed. Bundled
`composeApp/kotzilla.json` is left untouched as an unused configuration artifact;
the owner should separately revoke the old vendor SDK key if retiring the service.

Source search found no release-wide Firebase consent defaults or explicit
advertising-ID collection disable flags in Android XML/Kotlin or iOS plist/Kotlin.
Android debug explicitly deactivates Analytics; do not extrapolate that to release.
No setting was added or disabled as part of Kotzilla removal. Release merged
manifest inspection and device payload tests remain required before any blanket
“no advertising identifiers” or “no user content in crashes” claim.

The processed Android release manifest contains `AD_ID`,
`ACCESS_ADSERVICES_ATTRIBUTION` and `ACCESS_ADSERVICES_AD_ID` permissions and no
Kotzilla entries were found. Permissions establish capability, not actual
identifier transmission. Firebase advertising-ID/consent claims remain open.

## Contact email provider retention

Cloudflare's official privacy policy, checked 2026-10-07, describes end-user IPs,
traffic-routing and system-configuration data, distinguishes customer logs and
transiting content processed on customers' behalf from its own network data,
and states purpose/legal-obligation-based retention rather than one universal
numeric duration. It describes global transfers and DPF/SCC safeguards. Those
published mechanisms are not proof that all account/product-specific checks are
complete. Do not reuse its 1.1.1.1 DNS-resolver retention as Pages request-log
retention. Source: https://www.cloudflare.com/privacypolicy/

Owner reports persistent logs are **not enabled** in the Pages project's
Observability settings. This does not imply Cloudflare keeps no edge/security,
service or administrative records. Live deployed behavior remains untested.

Proposed website-host sentence: “Cloudflare processes request information such as
IP addresses and technical traffic details to deliver and secure this website.
Cloudflare's retention varies by service and processing purpose; our application
does not persist contact-message content in its database or log it.” Verify actual
Pages/Workers log configuration, D1 recovery window and enabled edge-cookie/script
features before resolving the remaining deployment confirmations.

Resend's official quotas/limits documentation, checked 2026-10-07, states email
data (content, metadata, delivery events, logs and metrics) is retained **30 days
on Free, Pro and Scale plans**. Owner confirms the Resend plan is **Free**;
the documented standard retention therefore applies absent custom arrangements.
Source: https://resend.com/docs/knowledge-base/account-quotas-and-limits

Proposed disclosure: “Resend retains email content, metadata, delivery events,
logs and metrics for 30 days under its standard plan retention. Our Gmail support
correspondence is deleted 12 months after resolution, unless needed longer for
an ongoing dispute or legal obligation.” No webhook archive has been implemented
by this contact handler. Cloudflare retention and provider-internal deletion
exceptions remain separate.

## Proposed Google disclosure

Google's Firebase privacy documentation, checked 2026-10-07, states Crashlytics
keeps crash stack traces, extracted minidump data and associated installation
identifiers for **90 days before starting removal from live and backup systems**.
Do not promise all copies are erased exactly on day 90. Raw NDK minidumps are
temporary during processing. Crashlytics can process data globally and collects
exception messages, installation/session identifiers and device/app details.
Source: https://firebase.google.com/support/privacy

Proposed replacement for the Google retention portion of privacy §8:

“Our Google Analytics property is configured to retain event-level data for two
months and user-level data for fourteen months. New user activity restarts the
user-data retention period. These settings do not apply to most aggregated
standard reports. Google states that Crashlytics retains crash traces, extracted
crash data and associated installation identifiers for ninety days before starting
their removal from live and backup systems. We have not enabled Firebase BigQuery
exports.”

Proposed addition to privacy §3:

“Our Analytics settings allow city-level location and granular device collection
and permit ads personalization. Google Signals is off and no Google Ads account
is linked. Account-level sharing is enabled for aggregated/de-identified modeling
contributions, technical support and business recommendations; Google products
and services sharing is off.”

These are observed settings, not a claim that every permitted data flow actually
occurs. SDK advertising identifiers, release payloads and lawful consent still
require verification; do not delete those confirmation markers on this basis.

## Other proposed privacy replacements

Replace §2 credential bullet with:

“Your server address and sign-in details are stored on your device and used to
connect to that server. Android credentials use encrypted preferences and the
SecureSettings preference file is excluded from configured Android cloud backups
and device transfer. iOS credentials use the system Keychain. They are not uploaded
as Parrot Cloud server credentials.”

Evidence: `EncryptedPreferenceFactory.kt`, `IosSettingsFactory.kt`, Android
`full_backup_content.xml` and `data_extraction_rules.xml`. Do not claim all app
data is excluded from backups or that iOS Keychain items can never be backed up.

Replace §14 first paragraph with:

“The website does not ship analytics scripts, advertisements or third-party
embedded resources. It has no web account sign-in or payment form. Its support
form works without JavaScript; you can also contact us through an email link.
This is separate from the mobile app’s monitoring SDKs.”

Replace §14 support paragraph with:

“When you use the support form, we process your email address and message to
answer your request. Cloudflare hosts the form handler, Resend delivers the email,
and Google Gmail stores it in our support inbox. To limit abuse, we also store
hourly rotating, keyed identifiers derived from IP addresses with submission
counts; expired records are removed when subsequent valid requests are processed.
The form does not accept attachments. If you contact us by email, we also process
any attachments you choose to send. [CONFIRM support-message and provider-log
retention, authorized access and international-transfer safeguards.] Do not send
passwords, access tokens or entire books.”

For §14 hosting paragraph, replace the unknown host with Cloudflare, but retain
checks for enabled edge features, actual access logging, retention, cookie behavior
and transfer safeguards. Local browser checks do not establish production behavior.

Use `rok@parrotapp.dev` for `[CONTACT EMAIL]` consistently after owner approval.
Keep public sending disabled until legal review and live configuration checks.

## What cannot be resolved from code

Owner-approved support inbox policy: delete routine support conversations from
Gmail 12 months after the request is resolved. Retain records longer only where
needed for an ongoing dispute or a legal obligation. This is an operational
commitment, not an automatic Gmail configuration; a periodic deletion process
still needs to be established. Resend/Cloudflare logs and backups have separate
retention rules. Publisher identity and business status remain deferred.

Proposed policy sentence:

“We delete routine support correspondence 12 months after your request is resolved,
unless we need to retain it longer for an ongoing dispute or a legal obligation.”

Owner must supply/approve:

1. Legal operator name and public postal address (country confirmed as Slovenia); confirm whether operating
   as an individual or registered business and any required register/VAT details.
2. Launch countries, minimum age and early-access termination notice period.
3. Support inbox retention/access policy and actual account security controls.
4. Final policy approval dates; keep draft notices until approved.

Dashboard evidence still needed:

- Supabase production region, project plan/log retention, backups/PITR, access list,
  deployed quotas and actual retention-job execution.
- Google Analytics retention/property links and final release identifier settings;
  Crashlytics product retention and any exports.
- Kotzilla exact release payloads, retention/deletion and processor terms.
- Cloudflare/Resend/Gmail applicable retention, transfer and processing terms.
- Commercial recap-provider approval or an authorized replacement. Do not assume
  disabling public advertising of recaps disables the backend feature.

Legal reviewer must decide consent/lawful bases, international safeguards,
consumer liability terms, copyright notice/appeal duties and hash-retention policy.
No checklist or generated wording is a substitute for those decisions.

See `launch-review.md` for dashboard locations and technical findings, and
`contact-form-setup.md` for contact processing and deployment checks.
