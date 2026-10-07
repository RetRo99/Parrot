# Parrot website

The support page now includes a no-JavaScript contact form backed by a Cloudflare
Pages Function and Resend. See `reports/contact-form-setup.md` for the required
secrets, D1 rate-limit binding, privacy review and live-delivery launch checks.
Astro preview cannot run the handler; local tests mock delivery without sending email.

Static Astro documents. No browser JavaScript, cookies, telemetry or remote assets.

## Local preview

Use Node 22.12+ (Node 24 LTS recommended).

```sh
cd website
npm ci
npm run build:preview
npm run preview
```

`npm run dev` is convenient while editing, but Astro's development server injects
development JavaScript. Use the **built preview** for privacy/performance checks.

## Production release gate

`npm run build` is the only production build command. It intentionally fails
until all bracketed placeholders are resolved. It checks rendered output as well
as launch configuration; preview builds may bypass the draft gate, never the
no-JavaScript/no-remote-assets checks. Do not deploy `build:preview` to the public
domain. A failed build may leave `dist/` present; that is not approved output.

- Legal wording lives only in `../design/ember/website/legal/privacy.md` and
  `terms.md`. Do not replace it with copied page text or rewrite it automatically.
- The owner sets a quoted date in each document's front matter:

  ```yaml
  ---
  lastUpdated: '2026-10-07'
  ---
  ```

  The example is syntax, not an approved date. Missing dates show a clear preview
  placeholder and block production. Keep the draft notice until the owner approves
  changing the source documents.
- Replace launch details in `src/config.ts` once supplied.
- Resolve support's audiobook-format confirmation with the owner.
- `scripts/check-tokens.mjs` compares every Day/Night colour property with
  `EmberTokens.kt`. Use token variables rather than adding colours in `site.css`.
- Fonts and licences are served locally from `public/fonts/`.

## Tests

```sh
npm test
npx playwright install chromium
npm run test:browser
```

Browser checks serve the built output, use JavaScript-disabled contexts, test
390px/1280px and Day/Night, run accessibility checks separately, and reject any
off-origin request or browser cookie/storage. Screenshots go to `test-results/`.

## Cloudflare Pages (not deployed)

Connect this repository only when ready. Set project root to `website`, build
command to `npm ci && npm run build`, output directory to `dist`, and Node version
to `24`. Use that same guarded command for any publicly accessible deployment.
Until approved legal text and contact details exist, keep previews local; do not
attach the public domain. When ready, add the owner-supplied custom domain in
Pages → Custom domains; Cloudflare provisions HTTPS.

Stable routes: `/`, `/privacy`, `/terms`, `/support`, `/support#delete-account`.
Stable app anchors: `/privacy#recaps`, `/terms#your-books-and-notes`.

## Illustrations and store availability

The positions block reproduces labels and values from the actual Day/Night
`design/screens/positions-normal-*.png` captures in HTML, so text stays 15–18px
at every breakpoint instead of shrinking with a bitmap. It is not interactive.
The reader and connected-server list are static HTML illustrations, per the brief.
Approved, anonymized Day/Night captures still needed:

1. Reader with synced narration, highlighted sentence and visible player.
2. Server/library list with Storyteller, Audiobookshelf and Parrot Cloud connected.

There are no live store listings. The Google Play pill is non-clickable and the
early-access actions are mailto links. Add an official locally served badge only
after the corresponding listing is live. No iPhone download is advertised.
