package com.retro99.catalogue.ui.cover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember
import com.retro99.server.api.CatalogueImageModel

/** Shared catalogue-cover boundary. Callers must retain the owning catalogue with the URL. */
@Composable
fun CatalogueCover(
    image: CatalogueImageModel,
    modifier: Modifier = Modifier,
) {
    // Catalogue pages do not show covers yet. Keep the model-typed entry point in place so the
    // next screen cannot accidentally send a raw URL to a generic image loader.
    @Suppress("UNUSED_VARIABLE")
    val catalogueImage = image
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Ember.colors.surface)
            .border(if (Ember.style.isEink) 2.dp else 1.dp, Ember.colors.line, RoundedCornerShape(12.dp)),
    )
}
