package com.retro99.home.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerManagementExposureContextTest {
    @Test
    fun appSettingsExposureUsesServersRowAttribution() {
        assertEquals(
            ServerManagementExposureContext(
                sourceScreen = "app_settings",
                entryPoint = "servers_row",
            ),
            serverManagementExposureContext(HomeDestination.AppSettings),
        )
    }

    @Test
    fun restoredAndOtherRouteExposuresUseBoundedAttribution() {
        assertEquals(
            ServerManagementExposureContext(
                sourceScreen = "home",
                entryPoint = "route_restore",
            ),
            serverManagementExposureContext(null),
        )
        assertEquals(
            ServerManagementExposureContext(
                sourceScreen = "book_detail",
                entryPoint = "in_app_navigation",
            ),
            serverManagementExposureContext(
                HomeDestination.BookDetail(serverId = "private-id", bookUuid = "private-book"),
            ),
        )
    }
}
