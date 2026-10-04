# Reader layout verification

Android emulator (API 37), 1080 × 2400, native Readium text selections in
*Pride and Prejudice*. Day and E-ink are selected through Settings → Theme.
The final debug APK was also installed on Rok's Xiaomi via ADB.

These are earlier emulator captures. Refreshing the final palette/joining-gap cleanup
was blocked by emulator startup and database-schema errors. The two E-ink bar captures
still need refreshing; no database logic or app data was changed to work around this.

| Case | Day | E-ink |
| --- | --- | --- |
| Selection near page top | [Screenshot](day-selection-top.png) | [Screenshot](eink-selection-top.png) |
| Selection near page bottom | [Screenshot](day-selection-bottom.png) | [Screenshot](eink-selection-bottom.png) |
| Mid-page selection, panel open | [Screenshot](day-selection-mid-panel.png) | [Screenshot](eink-selection-mid-panel.png) |
| Bookmarked, panel open | [Screenshot](day-bookmarked-panel-open.png) | Refresh pending |
| Bookmarked, panel closed | [Screenshot](day-bookmarked-panel-closed.png) | Refresh pending |

Checks:
- Top selections: toolbar below, with 32dp clearance for native handles.
- Bottom selections: toolbar above, 12dp from the first selected line.
- Panel/keyboard height constrains the toolbar's usable area; full-page fallback pins to its foot.
- E-ink selection remains white text on black while the toolbar is open.
- Bookmarked and removal bars share the panel-aware message slot, 8dp above its edge.
- The ribbon uses a 22 × 38dp glyph, 26dp from the right; tapping opens bookmark details.
- Page highlight colours do not depend on the chrome theme; translucent night dots are composited over the page colour.

Build: `:androidApp:assembleDebug` successful.
Tests: `:feature:reader:ui:testAndroidHostTest` — 188 passed, including seven toolbar-placement cases.
iOS was not device-tested.

## Merge verification

After integrating current `main`, the Android debug build succeeds and 432 tests pass
across reader UI, saved data/domain/UI, home UI, database implementation, and Parrot
Cloud server modules. The saved-items local migration is now `36.sqm` (schema 37),
preserving main's recap migration 34 and reading-speed repair 35.

The Xiaomi still has the approved pre-merge debug build. That experimental branch
used migration 34 for saved items (schema 35), so its DB history differs from main's.
Before installing the merged build there, inspect and reconcile that development DB
without deleting its books or saved items; do not assume the normal main-to-main
upgrade path covers this experimental schema. No phone data was reset during merging.
