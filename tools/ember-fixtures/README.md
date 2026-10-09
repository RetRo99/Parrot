# Ember fixture profile

This debug-only APK uses `com.retro99.parrot.fixtures`, not Parrot's application ID.
It has no network permission, production Application, database, account or sync workers.
All books, series, links and progress live in memory. Production composables and the
production series aggregation function render the profile; no screenshots are fabricated.

Build: `./gradlew :tools:ember-fixtures:assembleDebug`

Capture: `python3 tools/ember-fixtures/capture.py --serial emulator-5554`
The script refuses non-emulator targets unless `--allow-device` is explicitly supplied after
the device owner authorizes testing. It always installs only the separate fixture APK.

Launch on an emulator:
```
adb -s emulator-5554 shell am start -n com.retro99.parrot.fixtures/.FixtureActivity --es theme day
adb -s emulator-5554 shell am start -n com.retro99.parrot.fixtures/.FixtureActivity --es theme eink --es series "The Salt Roads"
```
Force-stop the fixture app before changing launch extras. Optional `--ez offline true`
and `--ez empty true` exercise presentation states. Series fixtures include two sources,
same-named catalogues, linked/unlinked books, fractional and unnumbered memberships.
Network failure/retry and repository behavior require domain tests, not this UI harness.
Positions fixtures use the production `PositionsScreenContent` and an entirely in-memory group:
```
adb -s emulator-5554 shell am start -n com.retro99.parrot.fixtures/.FixtureActivity --es theme day --es positions normal
python3 tools/ember-fixtures/positions_capture.py --serial emulator-5554
```
Scenarios: `normal`, `pair`, `full`, `error`, `loading`, `unlinked`, `apply`, `apply-all`,
`updating`, `success`, `partial`, `none`. `full` includes two same-kind servers and every
special row; `apply-all` includes the collapse-warning target. The other sheet matches
the three-tile reference. Error retry, radio selection, sheet dismissal, target toggles,
apply counts and notices are interactive. Captures go to `design/screens/positions-*.png`.
Real targets require `--allow-device`; this installs only the separate fixture package.
Linking fixtures remain a separate workstream.

Additional positions scenarios: `apply-start`, `apply-no-match`, `apply-not-supported`,
`details`, `no-selection`. Interactive checks:
`python3 tools/ember-fixtures/positions_smoke.py --serial emulator-5554`.

`python3 tools/ember-fixtures/smoke.py --serial emulator-5554` verifies submit-only E-ink
search, caller Back, and search/scroll restoration after killing only the fixture process.
It uses the optional `--ez longlist true` fixture scenario, not production data.

## Book catalogue (OPDS) fixtures

One fixture per design board (`design/ember/catalogues/screens/opds-<view>-<theme>.png`),
drawn by the production catalogue composables from in-memory state. The list lives in
`src/main/kotlin/com/retro99/parrot/fixtures/CatalogueFixtures.kt`. To add a board, add one
line there:
```
fixture(view = "browsePlain", expect = "a text only this board shows") { /* the composable */ }
```
`view` is the board name without `opds-`. `expect` must be on screen for the capture to pass.
Keep both as literals on the `fixture(` line: the capture script reads them from the file.

Build, then capture every fixture in Day and E-ink:
```
./gradlew :tools:ember-fixtures:assembleDebug
python3 tools/ember-fixtures/catalogue_capture.py --serial emulator-5554
```
Captures go to `design/screens/catalogue-<view>-<theme>.png`, each with its UI hierarchy as
`.xml`. Options: `--views browsePlain detail` for some fixtures only, `--themes day eink night`,
`--list` to print the fixtures without a device. Real targets require `--allow-device`.
Look at one fixture by hand:
```
adb -s emulator-5554 shell am start -n com.retro99.parrot.fixtures/.FixtureActivity --es theme eink --es catalogue descriptionText
```
`descriptionText` is not a board: it draws a sanitized description and proves the harness.
