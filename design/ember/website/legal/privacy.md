# Privacy policy

_Draft · not yet in force_

> **DRAFT for legal review.** Items in [SQUARE BRACKETS] must be filled in or confirmed before publishing.

What Parrot knows about you, why, and how to remove it. Written to be read.

## The short version

- Parrot has no ads and we do not sell your data.
- The app sends usage statistics and crash reports to help us find and fix problems.
- Books on your own servers travel directly to your device, except for files you choose to back up to Parrot Cloud and reading excerpts you choose to send for recaps.
- If you use Parrot Cloud, we store your account, the books you add and your reading activity so they follow you between devices.
- You can delete your Parrot Cloud account from the app at any time.

## 1. Who we are

Parrot is made by [LEGAL NAME], [ADDRESS], Slovenia (“we”). We are the data controller for the personal data described here. Contact: [CONTACT EMAIL].

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
- App start-up and performance data to Kotzilla. [CONFIRM what is sent]

Custom usage events and handled-error diagnostics are sanitized before sending. This does not establish what every SDK collects automatically. [CONFIRM release-device payload inspection.] [LAWYER CHECK: prior consent requirements for each monitoring service in the EU and how users can turn collection off. There is no collection switch in the app today; this policy does not substitute for consent.]

## 4. What we store if you use Parrot Cloud

Parrot Cloud is optional. If you create an account, we store:

- Account: your email address, and either a protected (hashed) form of your password or your Google sign-in.
- Library: the book files you add, with their names, sizes, covers, titles, authors and similar details, and your confirmation that you have the right to add them.
- Reading activity: your place in each book, bookmarks, highlights with the quoted text, notes, saved words, reading sessions, which copies of a book you have linked, and the name of the device you read on.
- Some reading settings, so they are the same on your devices.
- Service records: storage usage, sync history, file integrity hashes (SHA-256), upload/download and abuse-action records, and the version and time of your backup rights confirmation. File audit records are designed not to contain book contents, titles, access URLs or access tokens.
- Server logs kept by our hosting provider. [CONFIRM what and how long]

## 5. Recaps

Recaps are off until you turn them on, and are available only to some accounts for now. When they are on, the text you read in your last session is sent to our server and then to an AI service, which writes a short “last time” summary. This can be a large part of the book. Book and chapter titles are not sent to the AI service.

- The text is marked to expire after 24 hours and is erased shortly after, or as soon as the summary is done.
- Summaries are kept for up to 180 days, or until you delete them.
- AI service: [AI PROVIDER — currently OpenCode Go; its terms for commercial use, retention and training must be confirmed or the provider replaced before launch]

## 6. Why we use this data

Under the GDPR we rely on:

- Contract: to provide Parrot Cloud and sync your library and reading activity.
- Consent: for recaps. You can withdraw it at any time by turning recaps off.
- Legitimate interests: to keep the service secure, prevent abuse, and find and fix faults. [LAWYER CHECK: basis for usage statistics]

## 7. Who else handles it

We do not sell your data and we do not share it for advertising. We use these service providers:

- Supabase — accounts, database, file storage and recap processing. Region: [CONFIRM].
- Google — Firebase Analytics and Crashlytics, and Google sign-in if you choose it.
- Kotzilla — app performance monitoring.
- [AI PROVIDER] — writing recaps, only if you turn them on.
- GitHub — when you download a voice or a dictionary update, the file comes from GitHub, which sees your IP address.

Some of these providers process data outside the European Economic Area. Where they do, we rely on the European Commission’s standard contractual clauses or another lawful safeguard. [CONFIRM per provider]

## 8. How long we keep it

- Account, library and reading activity: until you delete them or your account.
- Sync history and recap usage counts: about 90 days.
- Records of file operations: up to 180 days.
- Usage statistics and crash reports: [CONFIRM retention set in Firebase and Kotzilla].
- Backups: [CONFIRM].
- Blocked-file hashes: while needed to prevent re-uploading after an abuse decision. [LAWYER CHECK necessity, review schedule and maximum retention.]

## 9. Your rights

You can ask us to show you your data, correct it, delete it, give you a copy, or stop or limit how we use it, and you can withdraw consent at any time. Write to [CONTACT EMAIL]; we reply within one month.

You can also complain to the Information Commissioner of the Republic of Slovenia (ip-rs.si) or to the data protection authority where you live.

## 10. Deleting your account

In the app: Settings → Parrot Cloud → Delete Parrot Cloud account… You will be asked to sign in again first. This removes your account, your Parrot Cloud library and your synced reading activity. Books on your own servers and files already downloaded to your devices are not touched.

One exception: records of file operations, kept to prevent abuse, stay for up to 180 days with your account details removed from them. Usage statistics and crash reports already sent are deleted on their own schedule. [CONFIRM]

If you can’t use the app, email [CONTACT EMAIL] from the address of your account and we will delete it for you.

## 11. Children

Parrot Cloud is not intended for children under 16. We do not knowingly collect their data. [DECIDE age; the app has no age check]

## 12. Security

Connections to Parrot Cloud are encrypted. [CONFIRM encryption at rest and who has access] If you connect to your own server with an http address, that connection is not encrypted; use https where you can. No system is perfectly secure; if a breach affects you, we will tell you.

## 13. Changes

If we change this policy in a way that matters, we will tell you in the app before the change takes effect. The date at the top shows the current version.

## 14. This website and support

The current website does not include analytics scripts, advertisements or third-party embedded resources. It has no web account sign-in or payment form. Contact links open your email application. This is separate from the mobile app's monitoring SDKs.

The website host may process your IP address, browser details, requested page and request time to deliver and secure the site. [CONFIRM host, request logs, retention, cookies and international transfers before deployment.] If non-essential tracking is added, update this notice and obtain any required consent before enabling it.

For support, deletion requests and copyright complaints, we process your email address, message and any attachments you send. [CONFIRM email provider, retention and access controls.] Do not send passwords, access tokens or entire books when reporting problems.
