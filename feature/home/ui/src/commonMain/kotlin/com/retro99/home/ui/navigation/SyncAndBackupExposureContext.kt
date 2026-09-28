package com.retro99.home.ui.navigation

/** Bounded attribution for an actual Sync & Backup destination exposure. */
internal data class SyncAndBackupExposureContext(
    val sourceScreen: String,
    val entryPoint: String,
)

internal fun syncAndBackupExposureContext(
    previousDestination: HomeDestination?,
): SyncAndBackupExposureContext = when (previousDestination) {
    HomeDestination.AppSettings -> SyncAndBackupExposureContext(
        sourceScreen = "app_settings",
        entryPoint = "sync_backup_row",
    )
    null -> SyncAndBackupExposureContext(
        sourceScreen = "home",
        entryPoint = "route_restore",
    )
    else -> SyncAndBackupExposureContext(
        sourceScreen = previousDestination.analyticsScreenName(),
        entryPoint = "in_app_navigation",
    )
}
