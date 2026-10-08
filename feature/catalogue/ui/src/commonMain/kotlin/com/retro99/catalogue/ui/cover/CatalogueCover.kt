package com.retro99.catalogue.ui.cover

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.retro99.base.ui.compose.EmberCover
import com.retro99.server.api.CatalogueImageModel

/**
 * The one place a catalogue picture is drawn. It takes the picture together with the catalogue
 * it belongs to, never a bare address, so the image loader asks that catalogue's own session
 * for it (plan §10.7). Without a picture, or until it has loaded, the frame shows [title]'s
 * initials.
 */
@Composable
fun CatalogueCover(
    image: CatalogueImageModel?,
    title: String,
    modifier: Modifier = Modifier,
) {
    // The loader builds its own cache key from profile, catalogue and access generation.
    EmberCover(data = image, cacheKey = null, contentDescription = null, modifier = modifier, fallbackLabel = title)
}
