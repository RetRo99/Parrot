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
Positions and linking fixtures will be added in their respective workstreams.

`python3 tools/ember-fixtures/smoke.py --serial emulator-5554` verifies submit-only E-ink
search, caller Back, and search/scroll restoration after killing only the fixture process.
It uses the optional `--ez longlist true` fixture scenario, not production data.
