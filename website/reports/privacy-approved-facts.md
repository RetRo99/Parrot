# Privacy changes ready for owner approval

Owner subsequently approved factual updates, now applied to the privacy source.
The passages below are historical proposals, not a final policy or legal
sign-off. The current source includes more recent DeepInfra exceptions and
owner-confirmed Resend plan/Cloudflare logging facts. Draft notices remain.

## Privacy §7: Supabase location

“Supabase — accounts, database, file storage and recap processing. Our production
database project is in West EU (Ireland). This does not mean all provider services
or subprocessors process data exclusively in Ireland.”

## Privacy §8: replace unresolved Google retention text

“Google Analytics: event-level data is retained for two months and user-level data
for fourteen months. New user activity restarts the user-data retention period.
These settings do not cover most aggregated standard reports. Google states that
Crashlytics keeps crash traces, extracted crash data and associated installation
identifiers for ninety days before beginning removal from live and backup systems.
We have not enabled Firebase BigQuery exports.”

Keep Kotzilla retention unresolved for old builds until its historical processing
is addressed; removal from new source is not deletion of old data.

## Privacy §8: backups and platform logs

“Our current Supabase Free plan does not include scheduled project backups or
point-in-time recovery, and we do not make manual database backups. Supabase's
published Free-plan limits specify one day of API/database log retention and one
hour of Auth audit logs. These limits are not a promise about every provider-
internal security record or recovery copy. We have not configured external log
drains.”

Do not merge this with application-table retention: sync/audit/recap cleanup has
separate policies and still requires verification of deployed jobs.

## Privacy §14: website and contact

“This website is hosted on Cloudflare Pages. It does not ship analytics scripts,
advertisements or third-party embedded resources. The support form works without
JavaScript, and email links can open your email application. The mobile app's
monitoring services are separate from this website.

When you submit the support form, Cloudflare processes your email address and
message, and Resend delivers them to our Google Gmail support inbox. The form
does not accept attachments. To limit abuse, we store hourly rotating keyed
identifiers derived from IP addresses and submission counts, rather than raw IP
addresses, in a Cloudflare D1 database. Expired counters are removed when later
valid requests are processed.

We delete routine support correspondence twelve months after your request is
resolved, unless needed longer for an ongoing dispute or legal obligation.
Resend's standard Free, Pro and Scale plans retain email content, metadata,
delivery events, logs and metrics for thirty days. Email attachments sent directly
to our inbox are processed along with your message. Please do not send passwords,
access tokens or entire books.”

Keep confirmations for Cloudflare logs, provider access/transfer safeguards,
actual Resend plan and the operational Gmail deletion process. No automated
Gmail cleanup has been implemented.

## Approval blockers that cannot be removed by these replacements

- Publisher identity and required business disclosures (deferred by owner).
- Analytics consent/legal basis, actual release payloads and advertising identifiers.
- Historical Kotzilla data/old versions; new app rollout.
- Commercial recap-provider authorization and privacy guarantees.
- Final legal dates, age/notice commitments, consumer/copyright legal review.
- Deployed retention-job execution and end-to-end account deletion checks.

Evidence and settings are in `legal-resolution.md`; no claims of complete
compliance or launch readiness are made.
