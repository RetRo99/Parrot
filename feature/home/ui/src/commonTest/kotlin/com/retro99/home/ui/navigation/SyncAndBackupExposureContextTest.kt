package com.retro99.home.ui.navigation

import com.retro99.analytics.api.NavigationAnalyticsEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncAndBackupExposureContextTest {
    @Test
    fun appSettingsExposureUsesSyncBackupRowAttribution() {
        assertEquals(
            SyncAndBackupExposureContext(
                sourceScreen = "app_settings",
                entryPoint = "sync_backup_row",
            ),
            syncAndBackupExposureContext(HomeDestination.AppSettings),
        )
    }

    @Test
    fun restoredAndOtherRouteExposuresUseBoundedAttribution() {
        assertEquals(
            SyncAndBackupExposureContext(
                sourceScreen = "home",
                entryPoint = "route_restore",
            ),
            syncAndBackupExposureContext(null),
        )
        assertEquals(
            SyncAndBackupExposureContext(
                sourceScreen = "book_detail",
                entryPoint = "in_app_navigation",
            ),
            syncAndBackupExposureContext(
                HomeDestination.BookDetail(serverId = "not-logged", bookUuid = "not-logged"),
            ),
        )
    }

    @Test
    fun eventHasOnlyBoundedExposureParameters() {
        val event = NavigationAnalyticsEvent.SyncAndBackupViewed(
            sourceScreen = "app_settings",
            entryPoint = "sync_backup_row",
        )

        assertEquals("sync_and_backup_screen_viewed", event.name)
        assertEquals(
            mapOf(
                "screen" to "sync_and_backup",
                "source_screen" to "app_settings",
                "entry_point" to "sync_backup_row",
            ),
            event.parameters,
        )
    }
}
