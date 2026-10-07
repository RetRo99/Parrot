# Parrot website legal launch checklist

Audit date: 2026-10-07. Drafting assistance, not legal advice or a compliance certification. Source inspection establishes intended behavior, not production deployment, SDK payloads or provider contracts.

## Website deliverables

- `/privacy` and `/terms`: drafts in `design/ember/website/legal/`.
- `/support`: help and contact.
- `/delete-account`: accessible email request without installing or signing into the app; deletion scope and retention exceptions.
- `/copyright`: report intake and review instructions, not a claim of DMCA safe-harbor eligibility.
- `/legal-notice`: operator identity and applicable business disclosures.
- No cookie banner added: current site has no client scripts or remote embedded resources. Confirm hosting behavior before concluding no consent is required.

## Owner decisions — publication blockers

- [ ] Supply domain, legal operator name, postal address, country and working support/privacy/abuse contact(s). Fill `website/src/config.ts` and legal Markdown consistently. Slovenia is currently assumed in existing drafts; confirm rather than infer residence from the repository.
- [ ] Confirm launch countries, business status, registration/VAT disclosures if applicable, minimum age and whether children are a target audience.
- [ ] Identify website and email hosts, Supabase region, Google/Kotzilla terms, applicable processor agreements, international transfer safeguards and authorized access.
- [ ] Record actual retention for hosting/email logs, Firebase Analytics, Crashlytics, Kotzilla and infrastructure backups. Do not replace placeholders with guessed periods.
- [ ] Confirm current quotas, free early-access terms, notice periods and export capability. Older `docs/parrot-cloud-legal-text-draft.md` promises 5 GB, seven-day active deletion and 30-day backup expiry: these are draft positions, not verified facts. Reconcile before reusing it.
- [ ] Approve the recap provider and its commercial-use, training, retention and transfer terms. Keep public recaps unavailable until this is resolved.
- [ ] Obtain qualified legal review of privacy bases, telemetry consent, consumer terms, copyright/hosting duties, appeals and US agent registration if seeking US safe-harbor protection. Do not treat the older legal draft's “resolved” conclusions as counsel sign-off.

## Implementation and operations gates

- [ ] Inspect Android and iOS release network payloads for all monitoring SDKs, including automatic events, installation/ad identifiers and native crash reports.
- [ ] Decide lawful telemetry collection per jurisdiction. If consent is required, implement default-off collection, refusal and withdrawal before SDK collection starts. Policy text is not consent. No collection toggle was found in this audit.
- [ ] Verify deletion end-to-end in the deployed environment: fresh sign-in, Storage removal, audit identifier redaction, Auth deletion/cascades, recap removal and failed-request retry.
- [ ] Verify retention jobs actually run. Migration defaults alone do not prove timely deletion; confirm 24-hour recap excerpt expiry, 180-day recap result expiry, 90-day sync retention and 180-day audit cleanup.
- [ ] Define provider deletion requests, backup expiry and account-independent monitoring deletion limitations; disclose accurate exceptions.
- [ ] Test the deletion mailbox externally, account verification, acknowledgement and completion. Do not ask for passwords. Confirm the one-month privacy response commitment is operationally supported.
- [ ] Establish copyright-report triage, user notifications, appeals and repeat-infringer handling with a real monitored contact.
- [ ] Verify export/download options before promising users an exit period and library recovery.
- [ ] Confirm credential storage, OS device backups, dictionary/voice downloads and any platform voice network processing before making blanket encryption or on-device-only claims.
- [ ] Remove all bracketed placeholders only after verifying their facts. Set effective dates and policy versions at approval; implement material-change notifications before promising them.

## Store disclosure worksheet — verify in each console

Not ready-to-submit labels: exact categories, linkage, tracking and collection definitions differ between stores and depend on release SDK settings.

| Processing | Candidate disclosure | Evidence / open check |
| --- | --- | --- |
| Cloud authentication | Email, user/account identifiers | Supabase Auth; password/Google sign-in configuration |
| Library/annotations/recaps | User content, files, reading history/activity | Cloud schema and recap functions; optional does not automatically mean undisclosed |
| Usage events | Product interaction, usage and device/installation identifiers | Custom parameters sanitized; inspect automatic SDK events and ad-ID configuration |
| Crash/performance monitoring | Crash data, diagnostics, performance data, SDK identifiers | Firebase Crashlytics and Kotzilla; inspect release payloads |
| Support and abuse contact | Email, messages, attachments | Identify email provider and retention |

- [ ] Complete Google Play Data Safety, including third-party SDK collection and deletion answers. Supply the public `/delete-account` URL.
- [ ] Complete Apple App Privacy labels and privacy-policy URL; distinguish data linked to a person/account from SDK installation identifiers. Do not infer “tracking” simply from use of analytics, or “not tracking” without inspecting configuration.
- [ ] Validate in-app account deletion against Apple's requirements, and current Apple privacy-manifest/required-reason API obligations for included SDKs.
- [ ] Confirm store copyright/UGC and AI-content requirements for this private-library design. A website report page alone does not prove all in-app requirements are satisfied.

## Evidence inspected

- `lib/analytics/implementation/.../AnalyticsManager.kt`: sanitized custom event/diagnostic boundary; supports user IDs but no production non-null setter call found by search.
- `docs/product-analytics.md`: custom usage events and installation-level metrics; not an inventory of automatic SDK collection.
- `composeApp/src/commonMain/kotlin/com/retro99/parrot/di/KoinInit.kt`: Kotzilla analytics integration.
- `supabase/SECURITY_ROLLOUT.md`: default 200 MiB and allowlist gates; confirm deployed configuration.
- `supabase/functions/delete-cloud-account/README.md`: authenticated deletion and retry design.
- `supabase/migrations/20261005000000_durable_recaps.sql`: excerpt and result expiry defaults.
- `supabase/migrations/20261003000000_parrot_cloud_security_hardening.sql`: retention function defaults.
- `website/scripts/check-output.mjs`: production blocks unresolved placeholders, JavaScript and remote embedded resources. Preserve this gate.

## Official references and drafting resources

- Google account deletion: https://support.google.com/googleplay/android-developer/answer/13327111 (checked during this audit; explicitly permits an email request pathway).
- Google Data Safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Apple account deletion: https://developer.apple.com/support/offering-account-deletion-in-your-app/
- Apple App Privacy: https://developer.apple.com/app-store/app-privacy-details/
- GDPR, including Articles 6, 12–22: https://eur-lex.europa.eu/eli/reg/2016/679/oj
- Optional drafting skill reviewed: https://github.com/phuryn/pm-skills/blob/main/pm-toolkit/skills/privacy-policy/SKILL.md. Useful checklist, not authoritative law; its blanket claims that GDPR requires explicit consent are not correct for all processing.

## Verification commands

From `website/`: `npm test`, then `npm run build:preview`. `npm run build` must continue to fail while factual/legal placeholders remain. Preview output must not be deployed publicly.
