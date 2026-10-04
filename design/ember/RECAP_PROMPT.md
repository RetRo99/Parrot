# Ember — Recap ("Last time") (prompt for Claude desktop)

Reference screenshots in this folder: `recap-sheet-day.png`, `recap-sheet-night.png`, `recap-sheet-eink.png`, `recap-reader-day.png`, `recap-sheetBusy-day.png`, `recap-session-night.png`, `recap-session-eink.png`, `recap-sessionNone-day.png`, `recap-states-day.png`, `recap-states-eink.png`, `recap-settings-night.png`, `recap-settings-eink.png`, `recap-consent-day.png`.

Based on `design/RECAP_REPORT.md`. Book title, text and recap in the screenshots are placeholders.

---

## Prompt

Redesign the reading recap following `design/ember/RECAP_PROMPT.md`, the `recap-*.png` screenshots and `design/RECAP_REPORT.md`. The purpose is unchanged: a short reminder of the **last reading session** so it's easy to get back into the story. Keep the durable job semantics, the consent/privacy envelope, spoiler-safe position gating and all server limits from the report's constraints. Ember tokens only (`design/ember/DESIGN_SYSTEM.md`).

**Step 1 — plan first.** Propose the changes to capture, the recap prompt, `ReaderRecapBannerHost`, `SessionRecapSection`, `CloudRecapsToggle`, state/intents and strings. Tell me which of the report's 11 bugs you'll fix here and which you'd defer. Flag anything below that conflicts with a constraint in the report. Wait for my OK.

**Step 2 — implement.**

### A. What gets summarised (do first)
1. **Only text the reader actually read.** Stop capturing the whole visible page after a 5 s dwell. A page counts when the reader **turns past it** (or, in scroll mode, scrolls it fully out of view); TTS keeps counting fully-heard sentences. The last page on screen when the session ends is not included. The excerpt must never extend past `furthestPosition`.
2. **Bound what's sent.** Cap the client excerpt (propose a number; the plan-era assumption was ~8k chars). For a long session send the opening part briefly and the **end of the session in full** — the end is what the reader needs.
3. **Prompt change.** Keep the 5 rules, language handling and the `NOT_ENOUGH` sentinel. Output becomes two parts: `summary` (1–2 sentences, past tense, third person) and `stoppedAt` (one sentence describing the last scene read, rendered as "You stopped as …" / localized). Still ≤ 600 chars total. If the model returns one block, show it as a single paragraph.
4. Fix the 30 s client timeout vs the documented 150 s, and the expiry mislabelled as `CONSENT_WITHDRAWN`.

### B. Reader — no more overlay on the text
- **Remove the top chip/card** (`ReaderRecapBannerHost` as an overlay). Nothing recap-related sits on top of book text while reading.
- **Bottom sheet "Last time"** (`recap-sheet-*.png`): shown when the book opens **and** a ready recap exists **and** the existing position gate passes (within `end … end+0.02`) **and** the "when to show" setting allows it (default: more than 1 hour since that session ended).
  - Handle; ✦ + "Last time" (Fraunces 24sp); close ✕ (44dp).
  - Meta line 13sp `ink2`: "Yesterday, 22:16 · 22 min · Chapter 8, The Crossing" (relative date, active reading time, chapter where the session ended).
  - Summary 16sp; then the `stoppedAt` sentence, 16sp SemiBold with a 3dp `accent` bar on the left.
  - **Continue reading** — the one filled button (52dp). Same as ✕, swipe down, back, or tapping outside.
  - Footer 13sp: "Written by AI · may miss details" and a text link "Recap settings".
  - Closing the sheet marks that recap as seen: it is not shown automatically again (keep the existing dismissed-FIFO for this). No expanded/collapsed state any more.
- **Any time** (`recap-reader-day.png`): when the reader's top bar is shown and a recap passes the position gate, show a tinted pill **✦ Last time** in the bar (left of the bookmark). It opens the same sheet. If the recap is still being written, the sheet shows a note box "**Writing your recap…** This usually takes under half a minute. You can start reading — it will be here under 'Last time'." (`recap-sheetBusy-day.png`). Never auto-open the sheet for busy/failed states, and show no pill when there's nothing to show.
- While TTS/narration is playing, don't auto-open the sheet; the pill is enough. Audio-only mode and the audiobook player stay without recaps, as today.

### C. Statistics → Reading session (`recap-session-*.png`, `recap-states-*.png`)
- Under the title: book title, then "Yesterday, 22:16 – 22:39 · Chapter 8".
- Three tiles: **Time read**, **Pages read**, **Ended at %**. **Remove reading speed from this sheet** until the estimator is fixed (see D).
- Section "✦ Recap": summary + stopped-at line + "Written by AI · may miss details". **Remove "Cloud recap · hy3"** — no engine or model names in the UI (keep them in Diagnostics).
- **No disabled button.** Each state is a sentence, with a button only when the user can do something:
  - Writing: "Writing your recap… Usually under half a minute." (static text, no spinner on e-ink)
  - None yet: "No recap for this session yet." + **Write a recap** (tinted)
  - Too short (`NOT_ENOUGH`): "Not enough read to recap. Recaps need a few pages of reading."
  - Failed: "Couldn't write this recap. Something went wrong on our side." + **Try again**
  - Offline: "You're offline. The recap will be written when you're back online."
  - Daily limit: "That's all the recaps for today. New recaps are available again tomorrow."
  - Signed out: "Sign in to Parrot Cloud to get recaps." + **Sign in** (fixes the bug where signed-out users were told to turn recaps on)
  - Turned off: "Recaps are turned off." + **Turn on recaps** (opens the consent dialog)
  - Expired (text older than 24 h): "This session is too old to recap. The text is only kept for 24 hours."
- Users not on the allowlist see no recap section, pill, sheet or settings row at all.

### D. Reading speed bug (separate commit)
Don't count forward jumps or TTS auto page turns as pages read; don't persist a clamped value into settings; when the estimate is outside a plausible range, show nothing rather than 1000 wpm. Repair already-pinned values on upgrade.

### E. Settings (`recap-settings-*.png`, `recap-consent-day.png`)
- The settings row becomes short: **Recaps** · "A short reminder of your last session" · state "On/Off" · chevron. It opens a **Recaps** screen:
  1. Card with switch "Recap my reading sessions" / "A short 'last time' when you come back to a book".
  2. Card "Show it when I open a book": segmented **After a break** (default, > 1 hour) · **Every time** · **Never** (pill in the reader only), with the helper line from the screenshot.
  3. Card "What happens to your text" with three short points — **Deleted within 24 hours.** / **Recaps stay 180 days** in your account and sync to your signed-in devices. / **Can't be recalled.** Text already sent to the AI provider can't be taken back. — and a link "Read the full privacy details" that opens the current full legal text unchanged.
  4. Footnote: "Turning recaps off deletes your recaps from Parrot Cloud and from your devices."
- **Consent dialog** the first time the switch is turned on (and from "Turn on recaps"): "Turn on recaps?" · "To write a recap, the text you read in a session is sent to Parrot Cloud and to an AI provider." · the same three points · **Not now** / **Turn on**. Every fact in today's settings text must still be stated here or one tap away; if the report's consent constraints require specific wording, keep that wording and tell me.
- Update the stale "14 days" in `docs/RECAPS.md` to 180 days.

### E-ink
No scrim behind sheets/dialogs, 2dp top border on sheets, 2dp outlined cards; the stopped-at bar is black; no animation or spinner — "Writing your recap…" is static and refreshes when done; segmented selection is a black fill with white text.

### Accessibility
The sheet is announced as "Last time. Recap of your reading session from yesterday"; the stopped-at sentence is read after the summary; ✦ Last time pill is labelled "Recap of your last session"; state messages are live regions.

**Step 3 — verify.** Build Android (and iOS if set up). Test: a paged session and a scroll session (recap must not mention text on the last unread page), a TTS session, a long session, a Slovenian book, coming back after 5 minutes vs 2 hours, jumping elsewhere in the book (no sheet), dismiss then reopen, every state in C (signed out, off, offline, limit, failed, too short, expired), turning recaps off (deletion), e-ink. Summarize changed files, which report bugs are fixed, and anything left out.
