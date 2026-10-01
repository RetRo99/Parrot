package com.retro99.database.implementation

/** First migratable schema: the schema at commit 1df302ab. Older installs reset once. */
internal const val MIN_MIGRATABLE_SCHEMA_VERSION = 28L

internal enum class DatabaseOpenAction {
    Open,
    Recreate,
}

internal fun decideDatabaseOpenAction(
    storedVersion: Long,
    currentVersion: Long,
    minMigratableVersion: Long = MIN_MIGRATABLE_SCHEMA_VERSION,
): DatabaseOpenAction = when {
    storedVersion == 0L -> DatabaseOpenAction.Recreate
    storedVersion < minMigratableVersion -> DatabaseOpenAction.Recreate
    storedVersion > currentVersion -> DatabaseOpenAction.Recreate
    else -> DatabaseOpenAction.Open
}
