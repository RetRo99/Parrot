# Parrot website — build prompt (v2, after your audit)

Your audit is accepted. The legal drafts and the page copy in this zip
are rewritten to match it. Replace the earlier files with these.

Plan approved: Astro in `website/`, hosted on Cloudflare Pages.

## Decisions

- Analytics stay in the app as they are. The privacy policy now has a
  section "Usage statistics and crash reports" naming Firebase Analytics,
  Crashlytics and Kotzilla. No privacy claim on the site may contradict it.
- Parrot Cloud is described as early access: by invitation, free for now.
  No prices, plans, subscriptions or cancelling anywhere on the site.
- Recaps are not advertised on the site. They stay in the privacy policy
  and terms because the feature exists for some accounts.
- No store listing is live. The hero shows a non-clickable "Coming soon to
  Google Play" pill and a mailto link "Ask for early access". No store
  badges until a listing exists; when one does, swap the pill for the
  official badge. iPhone is mentioned only as "in the works".
- Kobweb / Wasm: not used.

## Still open: I will send these, leave clear placeholders until then
Domain, support email, legal name and address.

## Fill the [CONFIRM] items you can from code or config
The drafts still contain [CONFIRM], [DECIDE] and [LAWYER CHECK] markers.
- For each [CONFIRM] you can settle from the repository (exact analytics
  events and identifiers, what Crashlytics attaches, what Kotzilla
  receives, whether book text can ever reach any of them), send me the
  corrected sentence. Don't edit the Markdown yourself.
- For the ones that need the dashboards (Supabase region, platform log
  and backup retention, Firebase and Kotzilla retention), list exactly
  where I look each one up.
- Leave [LAWYER CHECK] and [DECIDE] alone.
- The site must not be deployed to the public domain while any bracketed
  marker remains. Add a build check that fails on "[CONFIRM", "[DECIDE",
  "[LAWYER CHECK", "[LEGAL NAME", "[ADDRESS", "[CONTACT EMAIL",
  "[AI PROVIDER" in the output, with a flag to allow it for preview
  builds only.

## The site

- Static, in `website/`. No backend, no login.
- URLs, stable because the app and stores will link to them:
  `/` · `/privacy` · `/terms` · `/support` · `/support#delete-account`
  Section anchors the app will use: `/privacy#recaps`,
  `/terms#your-books-and-notes`.
- No cookies, analytics, trackers or third-party requests on the site
  itself, so no cookie banner. Verify with a network check.
- Fonts self-hosted with their licences.
- Light and dark follow the system setting. No theme switch, no E-ink.
- Ember tokens in one CSS file, values identical to `EmberTokens.kt`.
  Add a check that fails if they differ.
- No JavaScript shipped. One column under 800px.
- Accessible: headings in order, one h1 per page, 4.5:1 contrast,
  visible focus, underlined links in body text.
- Legal pages are generated from `design/ember/website/legal/*.md`.
  Keep the wording and the draft notice. "Last updated" comes from a
  date I set in the file's front matter, not from the build date.

## Pages
Follow the screenshots in `design/ember/website/screens/` for layout and
wording. Use real captures from `design/screens/` for the reading
positions block (Day and Night). For the reader and the server list, use
my mock-ups rebuilt in HTML until approved captures exist; tell me which
captures you need.

## Separate from the site: things your audit raised (report only, no code)
1. Recaps use OpenCode Go, which the repo flags as personal /
   non-commercial. What would it take to switch provider, and which
   providers offer a no-training, zero-retention commercial API?
2. The sign-up consent names documents but doesn't link them, and calls
   one "Book Backup Terms". We never say "backup" for Parrot Cloud. List
   the strings and screens to change once the site is live.
3. There is no way to turn off usage statistics. Estimate the work for a
   Settings switch and for an ask-first prompt, in case the legal review
   requires one.

## After launch
Update the links you listed: sign-up consent, upload-rights
confirmation, recap privacy details, and both store listings (privacy,
support, account deletion).
