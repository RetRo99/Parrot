# Ember design system (Parrot)

Reference: `ds-colors-night.png`, `ds-colors-day.png`, `ds-colors-eink.png`, `ds-type.png`, `ds-shape.png`, `ds-components-night.png`, `ds-components-day.png`, `ds-components-eink.png`, `ds-rules.png`.

## Colors

| Token | Night | Day | E-ink |
|---|---|---|---|
| bg | #16110D | #F7F1E8 | #FFFFFF |
| surface | #211A14 | #FFFFFF | #FFFFFF + 2dp black outline |
| ink | #F2E8DA | #221A13 | #000000 |
| ink2 | #B3A594 | #5E5145 | #000000 |
| line | #33291F | #E6DBCB | #000000 |
| accent | #D98A4E | #A9561F | #000000 |
| accentText | #E59A5F | #9A4D1A | #000000 |
| onAccent | #1C0F05 | #FFFFFF | #FFFFFF |
| chipBorder | #3A2E23 | #E6DBCB | #000000 |
| soft (tinted fill) / softInk | #3A2615 / #E59A5F | #F3D9C3 / #7A3A12 | white + outline / black |
| track | #3A2E23 | #EADFCF | white + outline |
| ok | #9CC58A | #3E6B2E | black + ✓ |
| err / errBg | #F08A7A / #3A1A15 | #A8321F / #FBE9E5 | black, bold / outline |

## Type
- **Fraunces** — screen titles (24sp), hero titles (28–32sp), dialog titles (22sp). Weight 500 night, 600 day, 700 e-ink.
- **Figtree** — all UI: row title 16sp Bold, body 15sp, secondary 13sp, eyebrow/label 11sp Bold caps +1.2 tracking (13sp on e-ink).
- **Literata** — book text and snippets only.
- Sentence case everywhere. Nothing below 13sp on e-ink.

## Shape, spacing, touch
- Radius: cards 16–20dp, fields 16dp, sheets 28dp top, buttons and chips fully rounded.
- Screen padding 20–24dp; cards 12dp apart; 16dp inside cards.
- Touch targets ≥ 48dp; main buttons 52dp; rows 56–68dp.
- No shadows. Night/Day separate with fills, E-ink with 2dp outlines.

## Components
- **Buttons:** Primary (filled `accent`, one per screen) · Secondary (tinted `soft`) · Outline (1.5dp `chipBorder`) · Text link (`accentText`) · Destructive (`err`, text or outline, label ends with "…" and always confirms) · Disabled.
- **Choices:** segmented tabs, chips (selected = filled), switch (whole row toggles), stepper (− value +, replaces sliders on e-ink).
- **Fields:** label above, 52dp; focused 2dp `accent`; checked ✓ line in `ok`; error 2dp `err` border with one sentence under the field.
- **Cards & rows:** icon tile + title + status in words + one action; navigation row with chevron; progress line "42% · Chapter 7 of 14" + thin bar.
- **Messages:** error box (`errBg`, what happened + fix), note box (context, not an error), bottom sheets for options, dialogs only for confirmations.

## Rules
1. One main action per screen; everything else is tinted, outlined or text.
2. Status once, in words — never color alone, never placeholders for unknown data.
3. Destructive actions sit at the bottom (or in a Manage sheet) and always ask first.
4. Errors appear under the thing they belong to, with the fix as a button. No toasts for errors.
5. Say what it does: "Download to read · 4 MB", "Remove from Parrot Cloud…". No jargon, no raw IDs, KB/MB/GB.
6. Parrot Cloud is a library: "Add to / In Parrot Cloud" — never "backup".
7. E-ink: black/white, 2dp outlines, no animation, scrims, sliders or spinners; highlight = underline or black box; update on submit, not per key.
8. Every screen ships in Night, Day and E-ink.
