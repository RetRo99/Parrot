# B5 — Real database migrations: implementation report

## Outcome

Supported upgrades preserve the per-profile database. Unknown versions, versions below 28 and
downgrades still reset. Driver construction/open/migration failures close any returned driver,
log one exception with a fixed context message, and retry once with a fresh database. The stored
schema version advances only after a successful forced open. Failed recovery propagates its
exception without advancing the preference.

Both platform factories use the shared opener. `deleteUserDatabase` is unchanged. No `.sqm`
files were edited, deleted or renumbered. No commands contacted remote Supabase or real servers.

## Isolation and commits

- Fetched `origin/main`, which was `d5c2122910f7709dbb78a9a73dba1f80d5d8de57`.
- Created branch `fix/real-database-migrations` in
  `/Users/rokretar/StudioProjects/StoryTellerKMP-real-database-migrations` from that revision.
- Copied `local.properties`; it is ignored and not committed.
- Generated the baseline in a detached throwaway worktree at `1df302ab`, then removed it.
- The main checkout's initial and final `git status --short` matched: its settings, tools,
  scripts, docs and image were not changed, committed or reverted.
- No merge or push to `main`.

Commits before the final Task 2 commit:

- `7e7018d8` — immutable v28 snapshot and its exact SQL dump (Task 2 Step 1, done first).
- `b5e14728` — shared policy/opener, platform factories and tests (Task 1).
- `d7a42d0d` — query API forced-open regression and correction (Task 1 follow-up).
- Final Task 2 commit: `test(database): verify the complete migration chain from schema 28`.

Snapshot SHA-256:
`912db05d216296628672a70cc9ee9476c95ada69110d07060fdcfb71c2f37fef`.

## Tests added

All use `kotlin.test`, hand-written fakes where needed, and Given/When/Then sections.

- `DatabaseSchemaPolicyTest`: table of unknown, pre-baseline, equal, supported upgrade and
  downgrade decisions.
- `SafeDatabaseOpenerTest` (six tests): fresh/unknown reset and operation ordering; file-backed
  28 → 32 upgrade retaining an unsynced library book and advancing SQLite/preferences; failed
  forced-open closure and one-shot recovery; construction-failure recovery; failed recovery
  closes both drivers and does not record the version; downgrade reset.
  Its fake also rejects row-returning SQL passed to `execute`, matching Android's API constraint.
- `MigrationChainTest`: full 28 → 32 chain against a freshly created current schema, comparing
  tables, columns, types, nullability, PK positions, defaults, index names, uniqueness, origins,
  partial flags, index columns, sort directions and collations. Reuses the extracted `schemaOf`
  from `OneBookIdMigrationTest`, extended to cover indexes.

Final XML totals: **62 tests, 0 failures, 0 errors, 0 skipped** (eight new test methods).
Migrations **28, 29, 30 and 31 all pass**. No bug in those migrations was found.

## Plan corrections

1. Followed the user's branch/worktree/commit/push instructions instead of the plan's stale
   instructions to work on main and ask before commits. The user's later instruction was not
   to push, so all commits remain local. The referenced superpowers
   execution skills are not available in this harness; implementation was done directly.
2. Current schema is 32, not the plan's 29. The baseline remains exactly 28 at `1df302ab`.
3. `v28_schema.sql` already existed, but was a hand-written fixture. Replaced it with the exact
   `sqlite3 .schema` output from the generated baseline, as requested. That dump contains
   `CREATE TABLE sqlite_sequence`, which SQLite refuses to execute directly. Fixture loaders
   skip only this automatically created internal table. Neither `28.db` nor the dump was edited
   to accommodate migrations. Four existing migration tests needed this loader adjustment.
4. SQLDelight 2.0.2's database-level `verifyMigrations = true` also enables compiler validation of
   the entire historical migration history from an empty schema. The incomplete pre-baseline
   history fails there (e.g. 7 alters `books` without a historical creation statement; 10, 19,
   26 and 27 also reference missing historical tables). This is **not** a 28–31 migration failure.
   Kept the database setting true and disabled it only on `generateCommonMainAppDatabaseInterface`.
   The real `verifyCommonMainAppDatabaseMigration` task remains enabled and validates from `28.db`.
   Configuring the interface override with `configureEach` was too early: SQLDelight overwrote
   it during task registration. A named task configuration after evaluation fixes that ordering.
5. The plugin already wires its verify task into `check`; no extra dependency was needed.
   Added `.db` to verification task input patterns because the plugin discovers snapshots but
   otherwise omits them from its cache input patterns.
6. The plan's `execute("PRAGMA user_version")` example is unsafe on Android: SQLDelight calls
   `executeUpdateDelete`. Use `executeQuery("SELECT 1")` and consume a row instead. Strengthened
   the fake first, observed all six opener tests fail, then implemented the correction.
7. Initial builds hit disk exhaustion, followed by sticky plugin-resolution failures in the
   existing daemon. Fresh single-use daemons (`--no-daemon`) resolved the latter; subsequent
   Android and iOS builds passed without deleting shared caches or changing settings.
8. After all required checks passed, a final line-wrap-only change to the test fixture filter
   prompted another host-test run. It failed before compilation because Gradle reported removed
   shared instrumentation-cache artifacts. A retry failed deserializing instrumentation analysis.
   Did not modify shared caches to repair this. The last successful full test run covers the same
   code except that whitespace-only wrap.

## Setup command results

All implementation commands ran in the new worktree, except the initial fetch/worktree creation
and commands explicitly targeting the throwaway baseline. Gradle output was redirected to local
logs and read before reporting. Routine read/search tools are not build commands.

| Command | Result |
| --- | --- |
| `git status --short` (main checkout) | Recorded the user's existing uncommitted work. |
| `git fetch origin main` | Success; latest main was `d5c21229`. |
| `git worktree add -b fix/real-database-migrations ../StoryTellerKMP-real-database-migrations origin/main` | Success. |
| `cp local.properties ../StoryTellerKMP-real-database-migrations/local.properties` | Success. |
| `git -C ../StoryTellerKMP-real-database-migrations rev-parse HEAD` | `d5c2122910f7709dbb78a9a73dba1f80d5d8de57`. |
| `git worktree add --detach ../storyteller-baseline 1df302ab` | Success. |
| `cp local.properties ../storyteller-baseline/local.properties` | Success. |
| `./gradlew :lib:database:implementation:tasks --all \| grep -i schema` (baseline) | Found `generateCommonMainAppDatabaseSchema`. |
| `./gradlew :lib:database:implementation:generateCommonMainAppDatabaseSchema` (baseline) | Success, 4s; generated exactly `28.db`. |
| `mkdir -p lib/database/implementation/src/commonMain/sqldelight/databases lib/database/implementation/src/androidHostTest/resources` | Success. |
| `cp ../storyteller-baseline/lib/database/implementation/src/commonMain/sqldelight/databases/28.db lib/database/implementation/src/commonMain/sqldelight/databases/28.db` | Success. |
| `sqlite3 lib/database/implementation/src/commonMain/sqldelight/databases/28.db .schema > lib/database/implementation/src/androidHostTest/resources/v28_schema.sql` | Success; unmodified dump. |
| `git worktree remove ../storyteller-baseline --force` | Success; only the throwaway baseline removed. |

## Every Gradle verification attempt

For compactness, `D` below expands to `:lib:database:implementation:`. Commands are otherwise
shown exactly, excluding only stdout/stderr redirection. Repeated commands list every attempt.

| Command | Results in execution order |
| --- | --- |
| `./gradlew DtestAndroidHostTest` | (1) Expected compile failure: new policy/opener APIs absent, 12s. (2) With API stubs, expected runtime red: 61 tests, 13 failures (seven new tests plus six reserved-table fixture failures), 11s. (3) After implementation, 61 tests, one test expectation incorrectly included the caller's close event, 18s. (4) Corrected expectation: success, 61 tests, 1m43s. (5) Task 2 red attempt blocked by sticky Foojay resolution, 3s. (6) Final default-daemon retry blocked by the same resolution failure, 1s. |
| `./gradlew DcompileKotlinIosSimulatorArm64 :androidApp:assembleDebug` | (1) Timed out after 120s during configuration. (2) Retry failed in 1m18s: disk exhaustion loading build-cache entries; daemon disconnected. |
| `./gradlew :androidApp:assembleDebug` | Failed during settings: Foojay plugin resolution, 772ms. |
| `./gradlew :androidApp:assembleDebug --offline` | Same plugin-resolution failure, 757ms. |
| `./gradlew DcompileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64` | Same plugin-resolution failure, 722ms. |
| `./gradlew Dtasks --all` | Same plugin-resolution failure, 1s. |
| `./gradlew Dtasks --all --info` | Same plugin-resolution failure, 601ms; read diagnostic output. |
| `./gradlew Dtasks --all --refresh-dependencies` | Same plugin-resolution failure, 674ms. |
| `./gradlew Dtasks --all --debug` | Same plugin-resolution failure; inspected resolver diagnostics. |
| `./gradlew DtestAndroidHostTest --no-daemon` | (1) Expected Task 2 compile red: shared `schemaOf` not exposed yet, 8s. (2) Android query-API regression red: 62 tests, six opener failures, 28s. (3) After query-API fix: success, 62 tests, 20s. (4) Final test-only line wrapping: blocked before compilation by removed instrumentation-cache artifacts, 38s. (5) Retry: blocked deserializing shared instrumentation analysis, 8s. |
| `./gradlew Dtasks --all --no-daemon` | Success, 22s; found exact verify task `verifyCommonMainAppDatabaseMigration`. |
| `./gradlew DtestAndroidHostTest DverifyCommonMainAppDatabaseMigration --no-daemon` | (1) Snapshot verifier ran, but interface compiler failed on incomplete pre-28 history, 23s. (2) Early override was overwritten; same compiler failure, 21s. (3) Named override: success, 62 tests and verification, 33s. (4) With snapshot cache inputs: success, verification executed, host tests up-to-date, 39s. |
| `./gradlew Dcheck --dry-run --no-daemon` | (1) Success, 21s. (2) Final configuration: success, 28s. Both task graphs contain `verifyCommonMainAppDatabaseMigration` before `check`; SKIPPED is expected for dry-run. |
| `./gradlew :androidApp:assembleDebug --no-daemon` | (1) Success, 1m4s, 612 tasks; APK packaged. (2) Final configuration: success, 37s, 612 tasks. |
| `./gradlew DcompileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64 --no-daemon` | (1) Success, 39s, 221 tasks; database and composeApp compiled. (2) Final configuration: success, 27s, 221 tasks. |
| `./gradlew DverifyCommonMainAppDatabaseMigration --no-daemon --rerun-tasks` | Success, 24s; one task explicitly executed, not cached. |
| `./gradlew DtestAndroidHostTest --no-daemon --rerun-tasks` | Success, 1m4s; all 52 tasks executed, 62 tests with zero failures/errors/skips. |

Read all final command output. Existing warnings concern expect/actual beta classes, existing
nullable class-loader calls, Kotzilla tracking, and native libraries packaged without stripping.
All required checks passed before the final whitespace-only wrap. The late host-test reruns are
blocked by shared Gradle cache damage, not by a test or source compilation failure.

Other diagnostics: `df -h .` and narrowly scoped `du -sh` confirmed initial disk pressure;
`curl -I -L` of the public Foojay plugin POM returned HTTP 200; inspected cached SQLDelight 2.0.2
source JARs with `find`/`unzip -p` to verify task wiring and Android execution behavior. No shared
cache was deleted. `git diff --check` passed. A Python XML summary confirmed the test totals.

## Re-run locally

```bash
./gradlew :lib:database:implementation:testAndroidHostTest --no-daemon
./gradlew :lib:database:implementation:verifyCommonMainAppDatabaseMigration --no-daemon
./gradlew :lib:database:implementation:check --dry-run --no-daemon
./gradlew :androidApp:assembleDebug --no-daemon
./gradlew :lib:database:implementation:compileKotlinIosSimulatorArm64 :composeApp:compileKotlinIosSimulatorArm64 --no-daemon
```
