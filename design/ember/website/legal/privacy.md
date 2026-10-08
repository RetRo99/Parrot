---
lastUpdated: '2026-10-07'
---
# Privacy policy

_Effective date: 7 October 2026_

What Parrot knows about you, why, and how to remove it. Written to be read.

## The short version

- Parrot has no ads and we do not sell your data.
- The app sends usage statistics and crash reports to help us find and fix problems.
- Books on your own servers travel directly to your device, except for files you choose to back up to Parrot Cloud and reading excerpts you choose to send for recaps.
- If you use Parrot Cloud, we store your account, the books you add and your reading activity so they follow you between devices.
- You can delete your Parrot Cloud account from the app at any time.

## 1. Who we are

Parrot is published under the brand Lunaria, based in Litija, Slovenia (“we”). Postal address: [ADDRESS]. We are the data controller for the personal data described here. Contact: rok@parrotapp.dev. [LEGAL REVIEW: Lunaria is a brand, not a registered business; legal operator identity remains deferred.]

## 2. What stays between you and your own server

- Books you read from your own servers travel directly between your device and that server, except for files you choose to back up and excerpts you choose to send for recaps.
- The address and sign-in details of your own servers are stored on your device and used to connect to that server. [CONFIRM credential encryption and platform backup behavior.] They are not uploaded as Parrot Cloud server credentials.
- Dictionary lookups happen on the device. The words you look up are not sent anywhere. Words you choose to save are synced if you use Parrot Cloud.
- Text read aloud by a downloaded voice is processed on your device.
- Downloaded book files stay on your device.

## 3. Usage statistics and crash reports

This applies to everyone who uses the app, with or without Parrot Cloud. The app sends:

- Usage events to Google Firebase Analytics: feature use, reading and playback durations, search-result counts and operation outcomes, with app/device details and SDK-generated installation identifiers. Custom event parameters are filtered to exclude book titles, text, search queries, server addresses, file paths and account identity. [CONFIRM automatic SDK collection, advertising identifiers and platform-specific release settings.]
- Crash reports to Google Firebase Crashlytics: errors, diagnostic breadcrumbs, stack traces and device/app details. Current sign-in flows clear analytics and crash-report user identity; we do not intentionally attach your cloud account ID. [CONFIRM automatic crash payloads and identifiers in release builds.]
- Older app versions may send start-up and performance data to Kotzilla. Kotzilla has been removed from the source for future builds; this does not disable it in already installed versions or delete previously collected data. [CONFIRM affected versions, rollout, historical payloads and retention.]

Custom usage events and handled-error diagnostics are sanitized before sending. This does not establish what every SDK collects automatically. [CONFIRM release-device payload inspection.] [LAWYER CHECK: prior consent requirements for each monitoring service in the EU and how users can turn collection off. There is no collection switch in the app today; this policy does not substitute for consent.]

## 4. What we store if you use Parrot Cloud

Parrot Cloud is optional. If you create an account, we store:

- Account: your email address, and either a protected (hashed) form of your password or your Google sign-in.
- Library: the book files you add, with their names, sizes, covers, titles, authors and similar details, and your confirmation that you have the right to add them.
- Reading activity: your place in each book, bookmarks, highlights with the quoted text, notes, saved words, reading sessions, which copies of a book you have linked, and the name of the device you read on.
- Some reading settings, so they are the same on your devices.
- Service records: storage usage, sync history, file integrity hashes (SHA-256), upload/download and abuse-action records, and the version and time of your backup rights confirmation. File audit records are designed not to contain book contents, titles, access URLs or access tokens.
- Server logs kept by our hosting provider. Supabase's published Free-plan limits specify one day of API/database logs and one hour of Auth audit logs. These limits do not cover every provider-internal security or administrative record. We have not configured external log drains. [CONFIRM relevant log categories and any additional retention.]

## 5. Recaps

Recaps are off until you turn them on, and are available only to some accounts for now. When they are on, reading text from your last session is sent to our server and selected recent text is then sent to DeepInfra, which uses Mistral Nemo to write a short “last time” summary. This can be a large part of the book. The current AI-processing limit is the most recent 192,000 text units (approximately characters); longer text is trimmed before it reaches the AI service. Book and chapter titles are not included as separate metadata in the AI request, but may appear in the reading text itself.

- The text is marked to expire after 24 hours and is erased shortly after, or as soon as the summary is done.
- Summaries are kept for up to 180 days, or until you delete them.
- AI service: DeepInfra, using `mistralai/Mistral-Nemo-Instruct-2407`. DeepInfra states that standard inference inputs and outputs are held in memory during processing, then deleted, and are not used for training. Its published terms make exceptions for content retained with a customer's written authorization to resolve support issues, non-content operational metadata, and records required by law or for fraud, security or abuse handling. Authorized support content is deleted within thirty days after the issue is resolved. Debugging metadata includes request IDs, costs and sampling parameters; a single retention period for this metadata has not been confirmed. We do not request model training or fine-tuning, use its bulk inference API or use Google/Anthropic model routing for recaps. [CONFIRM processor agreement and international transfer safeguards.]

Earlier versions of the recap backend used OpenCode Go. Switching providers does not erase information previously processed by that service. [CONFIRM historical provider retention and deletion.]

## 6. Why we use this data

Under the GDPR we rely on:

- Contract: to provide Parrot Cloud and sync your library and reading activity.
- Consent: for recaps. You can withdraw it at any time by turning recaps off.
- Legitimate interests: to keep the service secure, prevent abuse, and find and fix faults. [LAWYER CHECK: basis for usage statistics]

We also use support messages to answer requests and handle deletion or copyright complaints, and request information and contact-form counters to deliver and protect the website. [LAWYER CHECK: confirm the appropriate lawful bases for support, rights requests, copyright handling and website security, including any legal-obligation basis.]

## 7. Who else handles it

We do not sell your data. Parrot contains no advertisements. We use these service providers:

- Supabase — accounts, database, file storage and recap processing. Our production database project is in West EU (Ireland). This does not mean all provider services or subprocessors process data exclusively in Ireland.
- Google — Firebase Analytics and Crashlytics, and Google sign-in if you choose it.
- Kotzilla — performance monitoring in older app versions; removal from future builds does not erase historical data.
- DeepInfra — writing recaps with Mistral Nemo, only if you turn them on.
- Cloudflare — website hosting and contact-form processing, including abuse-prevention counters in D1.
- Resend — delivery of messages submitted through the website support form.
- Google Gmail — our support correspondence inbox.
- GitHub — when you download a voice or a dictionary update, the file comes from GitHub, which sees your IP address.

Google Signals is off and there are no linked Google Ads accounts. Analytics settings currently permit ads personalization and granular location/device collection. [CONFIRM actual SDK behavior and review advertising-purpose permissions and consent before claiming no advertising-related processing.]

Some of these providers process data outside the European Economic Area. [CONFIRM processor agreements and applicable transfer safeguards per provider, including standard contractual clauses or another lawful mechanism; do not treat published provider policies alone as confirmation that our arrangements are complete.]

## 8. How long we keep it

- Account, library and reading activity: until you delete them or your account.
- Sync history and recap usage counts: about 90 days.
- Records of file operations: up to 180 days.
- Google Analytics: event-level data is retained for two months and user-level data for fourteen months. New user activity restarts the user-data retention period. These settings do not cover most aggregated standard reports. We have not enabled Firebase BigQuery exports.
- Google Crashlytics: Google states that crash traces, extracted crash data and associated installation identifiers are kept for ninety days before removal from live and backup systems begins.
- Historical Kotzilla data: [CONFIRM retention and deletion; source removal does not erase it].
- Backups: our current Supabase Free plan does not include scheduled project backups or point-in-time recovery, and we do not make manual database backups. This is not a promise that providers keep no internal recovery copies. [CONFIRM Storage recovery and any other relevant recovery windows.]
- Blocked-file hashes: while needed to prevent re-uploading after an abuse decision. [LAWYER CHECK necessity, review schedule and maximum retention.]

## 9. Your rights

You can ask us to show you your data, correct it, delete it, give you a copy, or stop or limit how we use it, and you can withdraw consent at any time. Write to rok@parrotapp.dev; we reply within one month.

You can also complain to the Information Commissioner of the Republic of Slovenia (ip-rs.si) or to the data protection authority where you live.

## 10. Deleting your account

In the app: Settings → Parrot Cloud → Delete Parrot Cloud account… You will be asked to sign in again first. This removes your account, your Parrot Cloud library and your synced reading activity. Books on your own servers and files already downloaded to your devices are not touched.

One exception: records of file operations, kept to prevent abuse, stay for up to 180 days with your account details removed from them. Usage statistics and crash reports already sent are deleted on their own schedule. [CONFIRM]

If you can’t use the app, email rok@parrotapp.dev from the address of your account and we will delete it for you.

## 11. Children

Parrot Cloud is not intended for children under 16. We do not knowingly collect their data. [CONFIRM age-related signup implementation; the app has no age check.]

## 12. Security

Connections to Parrot Cloud are encrypted. [CONFIRM encryption at rest and who has access] If you connect to your own server with an http address, that connection is not encrypted; use https where you can. No system is perfectly secure; if a breach affects you, we will tell you.

## 13. Changes

If we change this policy in a way that matters, we will tell you in the app before the change takes effect. The date at the top shows the current version.

## 14. This website and support

This website is hosted on Cloudflare Pages. It does not ship analytics scripts, advertisements or third-party embedded resources. It has no web account sign-in or payment form. The support form works without JavaScript, and email links can open your email application. This is separate from the mobile app's monitoring SDKs.

Cloudflare processes request information such as IP addresses and technical traffic details to deliver and secure this website. Persistent project logs are not enabled in the Pages project's Observability settings. Cloudflare may still keep separate edge, security, service and administrative records; retention varies by service and purpose. [CONFIRM enabled edge cookies/scripts, provider retention and international transfer arrangements.] If non-essential tracking is added, update this notice and obtain any required consent before enabling it.

For support, deletion requests and copyright complaints, we process your email address and message. When you submit the support form, Cloudflare processes these details and Resend delivers them to our Google Gmail support inbox. The form does not accept attachments. Attachments sent directly by email are processed along with your message. The application's contact database stores abuse-prevention counters, not message content, and our handler does not log message content.

To limit abuse, we store hourly rotating keyed identifiers derived from IP addresses and submission counts in Cloudflare D1, rather than raw IP addresses. Expired counters are removed when later valid requests are processed; this is not a guarantee of immediate deletion at the end of each hour. [CONFIRM D1 recovery-copy retention.]

Our support-correspondence retention policy is twelve months after a routine request is resolved, unless needed longer for an ongoing dispute or legal obligation. Resend's standard Free plan retains email content, metadata, delivery events, logs and metrics for thirty days. [CONFIRM operational Gmail cleanup, inbox access controls and provider transfer safeguards.] Do not send passwords, access tokens or entire books when reporting problems.
