package com.retro99.catalogue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.catalogue_browse
import resources.translations.catalogue_downloads_title
import resources.translations.catalogue_get_books
import resources.translations.catalogue_type_name

/*
 * Phase 4 foundations: the four catalogue routes exist and resolve to these screens, which
 * show a title and a way back and nothing else. Nothing in the app navigates to them yet.
 * Each is replaced by its real screen, keeping its name and parameters.
 */

/** "Get books": the user's catalogues and the presets. */
@Composable
fun CatalogueSourcesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    CataloguePlaceholder(stringResource(StringRes.catalogue_get_books), onBack, modifier)

/** One page of the catalogue [sourceId]; [targetRef] null is its first page. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CatalogueBrowseScreen(sourceId: String, targetRef: String?, onBack: () -> Unit, modifier: Modifier = Modifier) =
    CataloguePlaceholder(stringResource(StringRes.catalogue_browse), onBack, modifier)

/** A book's page in the catalogue [sourceId]. */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CataloguePublicationScreen(sourceId: String, publicationRef: String, onBack: () -> Unit, modifier: Modifier = Modifier) =
    CataloguePlaceholder(stringResource(StringRes.catalogue_type_name), onBack, modifier)

/** Downloads from catalogues. */
@Composable
fun CatalogueDownloadsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    CataloguePlaceholder(stringResource(StringRes.catalogue_downloads_title), onBack, modifier)

@Composable
private fun CataloguePlaceholder(title: String, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize().background(Ember.colors.bg)) {
        EmberTopBar(title = title, onBack = onBack)
    }
}
