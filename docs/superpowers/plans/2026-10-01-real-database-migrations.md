# Real Database Migrations — Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Updating the app keeps the user's local data. The database only resets when it's older
than a fixed starting version, or after a downgrade. A migration that fails resets the database
instead of crashing the app on every launch.

**Architecture:**
- **Today:** `PlatformDatabaseModule.android.kt` and `.ios.kt` delete the user's database whenever
  the stored schema version differs from `AppDatabase.Schema.version`, so `.sqm` migrations never
  run on a device.
- **After this change:** a small shared policy decides between opening the database (the
  SQLDelight driver then runs the `.sqm` migrations itself, based on SQLite's `user_version`) and
  recreating it.
- **Baseline:** version **28**, the schema after project 1 (commit `1df302ab`). Older databases
  are reset one last time. From 28 on, every change ships as a tested `.sqm` migration.
- **Guard:** SQLDelight's `verifyMigrations` checks the migrations against a schema snapshot of
  version 28.

**Tech Stack:** Kotlin Multiplatform, SQLDelight 2.0.2 (`AndroidSqliteDriver`,
`NativeSqliteDriver`), Koin.

**Why now:** projects 2 and 3 add `28.sqm` and `29.sqm`. With this in place, those migrations
actually run on devices, and users stop losing unsynced positions, favorites, bookmarks, imported
books and links on each update.

## Global Constraints

- Same as project 1's: work on `main`, ask before every commit, never push, Kotlin style, the
  repo's test style, honest reporting, and no device runs unless the user asks.
- **Baseline = 28, always.** Generate the snapshot from the schema at commit `1df302ab`, even if
  project 2's `28.sqm` has already landed (Task 2 explains how).
- **Don't delete or renumber the old `.sqm` files (1–27).** SQLDelight takes the schema version
  from the highest migration file number. They never run again, because everything below 28 is
  reset.
- **Keep the reset on downgrade.** SQLDelight can't migrate down.

---

## Task 1: Schema policy and a safe open

**Files:**
- Create: `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/DatabaseSchemaPolicy.kt`
- Create: `lib/database/implementation/src/commonMain/kotlin/com/retro99/database/implementation/SafeDatabaseOpener.kt`
- Modify: `lib/database/implementation/src/androidMain/.../di/PlatformDatabaseModule.android.kt`
- Modify: `lib/database/implementation/src/iosMain/.../di/PlatformDatabaseModule.ios.kt`
- Test: `lib/database/implementation/src/androidHostTest/.../DatabaseSchemaPolicyTest.kt` and
  `SafeDatabaseOpenerTest.kt`

**Interfaces:**

```kotlin
/** First schema version that is migrated instead of reset. The schema at commit 1df302ab. */
internal const val MIN_MIGRATABLE_SCHEMA_VERSION = 28L

internal enum class DatabaseOpenAction { Open, Recreate }

/**
 * [storedVersion] is the version recorded in preferences for this user (0 when never recorded).
 * Open lets the driver run migrations; Recreate deletes the file first.
 */
internal fun decideDatabaseOpenAction(
    storedVersion: Long,
    currentVersion: Long,
    minMigratableVersion: Long = MIN_MIGRATABLE_SCHEMA_VERSION,
): DatabaseOpenAction = when {
    storedVersion == 0L -> DatabaseOpenAction.Recreate // fresh install or unknown old file
    storedVersion < minMigratableVersion -> DatabaseOpenAction.Recreate
    storedVersion > currentVersion -> DatabaseOpenAction.Recreate // downgrade
    else -> DatabaseOpenAction.Open
}

/** Platform operations the opener needs. */
internal interface DatabaseFileOps {
    fun createDriver(): SqlDriver
    fun deleteDatabaseFile(): Boolean
}

internal class SafeDatabaseOpener(
    private val preferences: Preferences,
    private val analytics: Analytics,
) {
    /**
     * Applies the policy, opens the driver and forces the open (so migrations run now).
     * If opening or migrating throws, it deletes the file, logs the exception, and creates a fresh
     * database. Records the current version only after a successful open.
     */
    fun open(userId: String, fileOps: DatabaseFileOps): SqlDriver
}
```

**Behaviour of `open`:**
1. Read the stored version from `PreferencesKey.UserScoped(userId, "DatabaseSchemaVersion")`,
   which is the same key as today.
2. If the policy says `Recreate`, call `deleteDatabaseFile()`.
3. Call `createDriver()`, then force the open with a trivial statement, for example
   `driver.execute(null, "PRAGMA user_version", 0)` or a `SELECT 1` query. On Android this
   triggers `onUpgrade` and runs the migrations. Do the equivalent on native.
4. If step 3 throws:
   - close the driver,
   - call `analytics.logException(e, "Database migration failed; recreating")` without file
     paths,
   - call `deleteDatabaseFile()`,
   - create a new driver and force it open. If that also throws, rethrow.
5. Save `AppDatabase.Schema.version` to the preference.

**Platforms:** both `createDriver(userId)` implementations become thin. They build a
`DatabaseFileOps` (the Android one uses `context.deleteDatabase(name)`, the iOS one keeps today's
`deleteDatabaseFile`) and call `SafeDatabaseOpener.open`. Inject `Analytics` on Android too (iOS
already has it). `deleteUserDatabase` stays as it is.

- [ ] **Step 1: Write the failing tests.**
  - `DatabaseSchemaPolicyTest` is a table test:
    - stored 0 gives Recreate;
    - 27 gives Recreate;
    - 28 with current 28 gives Open;
    - 28 with current 30 gives Open;
    - 31 with current 30 gives Recreate.
  - `SafeDatabaseOpenerTest`, using `JdbcSqliteDriver` on a temporary file (not `IN_MEMORY`),
    plus a fake preferences and a recording analytics:
    1. Stored 0: the file is deleted, opened, and the version stored.
    2. **Upgrade keeps data:**
       - Create the database at the baseline: execute `src/androidHostTest/resources/v28_schema.sql`
         and set `PRAGMA user_version = 28`.
       - Insert a `library_books` row, and store 28 in preferences.
       - Open it with a driver built for the current schema.
       - Expect the row to still exist, the version preference to be current, and nothing
         deleted.
    3. **A failing migration recreates the database:**
       - The fake `createDriver()` throws on the first forced open.
       - Expect the file to be deleted, one analytics exception, and the second driver opened.
    4. Downgrade: stored 99 with current 28 deletes the file.
- [ ] **Step 2:** Run `./gradlew :lib:database:implementation:testAndroidHostTest` and confirm the
  tests fail.
- [ ] **Step 3:** Implement the policy and opener, and slim down both platform factories.
- [ ] **Step 4:** Run the tests and confirm they pass. Then run
  `./gradlew :lib:database:implementation:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug`.

## Task 2: The baseline snapshot and automatic migration checks

**Files:**
- Modify: `lib/database/implementation/build.gradle.kts`, inside
  `sqldelight { databases { create("AppDatabase") { … } } }`:

```kotlin
schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
verifyMigrations.set(true)
```

  Also delete the stale `version = 26` line. SQLDelight doesn't use it; it derives the version
  from the `.sqm` files.
- Create: `lib/database/implementation/src/commonMain/sqldelight/databases/28.db`, the schema
  snapshot at commit `1df302ab`.
- Create: `lib/database/implementation/src/androidHostTest/resources/v28_schema.sql`, the same
  schema as SQL. Task 1's tests use it.
- Create: `lib/database/implementation/src/androidHostTest/.../MigrationChainTest.kt`

**Steps:**

- [ ] **Step 1: Generate the snapshot from commit `1df302ab`.** That keeps the baseline correct
  even if project 2 has already added `28.sqm`:

```bash
git worktree add ../storyteller-baseline 1df302ab
cd ../storyteller-baseline
# Add the two lines above to lib/database/implementation/build.gradle.kts in this worktree only.
./gradlew :lib:database:implementation:tasks --all | grep -i "schema\|migration"
./gradlew :lib:database:implementation:generateCommonMainAppDatabaseSchema
# Use the exact generate task name printed above if it differs.
cp lib/database/implementation/src/commonMain/sqldelight/databases/28.db \
   ../StoryTellerKMP/lib/database/implementation/src/commonMain/sqldelight/databases/28.db
sqlite3 lib/database/implementation/src/commonMain/sqldelight/databases/28.db .schema \
   > ../StoryTellerKMP/lib/database/implementation/src/androidHostTest/resources/v28_schema.sql
cd ../StoryTellerKMP && git worktree remove ../storyteller-baseline --force
```

  The generated file must be named `28.db`. If it isn't, the worktree isn't at `1df302ab`; stop
  and check.

- [ ] **Step 2: Wire the verification into the normal checks.**
  - Run the verify task. Its name is something like
    `:lib:database:implementation:verifyCommonMainAppDatabaseMigration`; use the exact name from
    the task list.
  - **With no `28.sqm` yet:** it passes trivially.
  - **If project 2's `28.sqm` exists:** it must pass. If it fails, that migration is wrong. Report
    it, and don't change the snapshot to make it pass.
  - Make sure the verify task runs as part of `check`. If it doesn't, add
    `tasks.named("check") { dependsOn("<verify task>") }`.

- [ ] **Step 3: Write `MigrationChainTest` as a fallback** that doesn't depend on the Gradle task.
  It loads `v28_schema.sql` into a `JdbcSqliteDriver`, sets `user_version = 28`, calls
  `AppDatabase.Schema.migrate(driver, 28, AppDatabase.Schema.version)`, and asserts the result
  matches `AppDatabase.Schema.create` on a fresh driver. Compare tables, columns, types,
  nullability, primary keys and indexes, reusing `schemaOf` from `OneBookIdMigrationTest`.

- [ ] **Step 4: Document the rule for future changes.** Add a KDoc on `MIN_MIGRATABLE_SCHEMA_VERSION`:
  every `.sq` change needs a new `N.sqm`, plus a test that `MigrationChainTest` and the verify
  task pass. Never edit `28.db`.

- [ ] **Step 5: Update the other plans.** Remove the "Database wipe on upgrade" notes from
  `2026-10-01-linked-books-across-servers.md` and `2026-10-01-progress-across-linked-copies.md`,
  and replace them with: "migrations run on devices; `verifyMigrations` and `MigrationChainTest`
  must pass".

- [ ] **Step 6:** Run `./gradlew :lib:database:implementation:testAndroidHostTest`, the verify
  task, and `:androidApp:assembleDebug`. Report the output, then ask before committing.

---

## Notes

- **Old installs:** everyone below version 28 is reset once on update. That's every current
  install, because project 1 already changed the schema. The user accepted that.
- **Orphaned files, out of scope:** a reset leaves files in `filesDir/library` and
  `Documents/library`, which are shared by all profiles. Cleaning them up safely means checking
  every profile's `device_files`. Do it as a separate small task if storage use becomes a concern.
- **Order with project 2: project 2 has already landed.** As of 2026-10-01, project 2's `28.sqm`
  (book links, commits `b47dbef8`–`2ae926cf`) is on `main`, so the current schema version is 29.
  - Generate the baseline snapshot from `1df302ab` exactly as Task 2 says. Don't generate it from
    `HEAD`.
  - The verify task and `MigrationChainTest` must then pass for `28.sqm`. If they fail, report it
    as a bug in project 2's migration; never "fix" it by changing `28.db`.
