# Website UI review — rounds 1 and 2

2026-10-08. UI-only: the authoritative privacy/terms Markdown is unchanged.
Existing page copy is preserved; section numbers/contents links are presentation.
Inline form errors reuse the handler's existing validation messages. The hidden
spam-trap label was removed explicitly because it leaked into page-text tools.

## Changes

- Shared `LegalPage.astro` layout for all five document pages, with common
  eyebrow/title/lead, body width, spacing and contents placement.
- Shared CSS counters number headings and contents consistently, with source
  numbering stripped by the legal renderer to prevent duplicated numbers.
- Contents exist only for four or more numbered sections and display only from
  1100px. Copyright and legal notice have no contents list.
- Simplified SVG dark mark stays sharp at 36px/28px; decorative logo alt is empty.
- Shared accent/underline/focus styling for visible email addresses, including
  Pages Function errors. CTA labels retain their filled-button presentation.
- Cloudflare `email_off` markup protects mailto links from obfuscation. All seven
  pages were verified live after round 1 with scripts disabled: no email-protection
  URLs or injected scripts. Round 2 keeps this protection; no account-wide
  obfuscation setting change is necessary if delivered HTML continues to pass.
- Form has 52px inputs, 14px radii, native script-free validation, inline error
  styling and 48px button targets. Honeypot is hidden, aria-hidden and inert with
  tabindex -1; no readable spam-trap label remains in HTML.
- Footer uses a six-link grid with two columns on phones and three otherwise.
- Server mock-ups use bounded grid tracks; on phones the Connected state stacks
  below the server details. Regression checks cover the padded panel's inner
  bounds, not just page-level horizontal overflow.
- Referrer policy is `same-origin`, preserving the POST Origin check while
  withholding referrers from external sites. A live round-1 email test was
  accepted by Resend; Gmail inbox receipt was not independently confirmed.

## Validation

- 22 unit/browser-origin tests pass.
- `npm run build:preview` passes, retaining warnings for unresolved legal facts.
- `node scripts/ui-review.mjs` passes all seven routes at 390, 768 and 1280px:
  no overflow/footer orphans, numbering/contents, uniform email styles, hidden
  trap, focus rings, script-free form submission to a local intercepted endpoint.
- Axe accessibility checks pass for all seven routes at 390 and 1280px.
- 14 full-page dark captures:
  `~/Downloads/Parrot Website Screenshots/UI-only review/Round 2/`.

## Automatic deployment

At the owner's request to rebuild the published site on push, Cloudflare Pages
`parrot` now uses `npm ci && npm run build:preview` on production branch `main`,
root `website`, output `dist`. This deliberately allows the already-approved
publication with unresolved legal markers; it does not certify legal readiness.
The stricter `npm run build` safety check remains in source. Restore it once the
remaining facts and legal decisions are resolved. Supabase deployment is separate.
