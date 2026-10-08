package com.retro99.catalogue.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.EmberTopBar
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.catalogue_downloads_title

/*
 * Downloads is not built yet: its existing route shows a title and a way back.
 * Its real screen will keep the same name and parameters.
 */

/** Downloads from catalogues. */
@Composable
fun CatalogueDownloadsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    CataloguePlaceholder(stringResource(StringRes.catalogue_downloads_title), onBack, modifier)

@Composable
private fun CataloguePlaceholder(title: String, onBack: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        EmberTopBar(title = title, onBack = onBack)
    }
}
