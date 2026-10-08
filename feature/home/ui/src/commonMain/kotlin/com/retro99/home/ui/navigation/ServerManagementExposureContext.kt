package com.retro99.home.ui.navigation

/** Bounded route context attached to an actual Server Management destination exposure. */
internal data class ServerManagementExposureContext(
    val sourceScreen: String,
    val entryPoint: String,
)

internal fun serverManagementExposureContext(
    previousDestination: HomeDestination?,
): ServerManagementExposureContext = when (previousDestination) {
    HomeDestination.AppSettings -> ServerManagementExposureContext(
        sourceScreen = "app_settings",
        entryPoint = "servers_row",
    )
    null -> ServerManagementExposureContext(
        sourceScreen = "home",
        entryPoint = "route_restore",
    )
    else -> ServerManagementExposureContext(
        sourceScreen = previousDestination.analyticsScreenName(),
        entryPoint = "in_app_navigation",
    )
}

internal fun HomeDestination.analyticsScreenName(): String = when (this) {
    HomeDestination.BooksList -> "books"
    HomeDestination.SeriesList -> "series"
    is HomeDestination.BookDetail -> "book_detail"
    is HomeDestination.LinkPicker -> "link_picker"
    is HomeDestination.Positions -> "reading_positions"
    HomeDestination.LinkReview -> "link_review"
    is HomeDestination.NotesHighlights -> "notes_highlights"
    is HomeDestination.SeriesDetail -> "series_detail"
    is HomeDestination.Reader -> "reader"
    HomeDestination.Settings -> "reader_settings"
    HomeDestination.AppSettings -> "app_settings"
    HomeDestination.ServerManagement -> "server_management"
    HomeDestination.SyncAndBackup -> "sync_and_backup"
    HomeDestination.Diagnostics -> "diagnostics"
    HomeDestination.Statistics -> "statistics"
    HomeDestination.CatalogueSources -> "catalogue_sources"
    is HomeDestination.CatalogueBrowse -> "catalogue_browse"
    is HomeDestination.CataloguePublication -> "catalogue_publication"
    HomeDestination.CatalogueDownloads -> "catalogue_downloads"
    is HomeDestination.CatalogueSettings -> "catalogue_settings"
}
