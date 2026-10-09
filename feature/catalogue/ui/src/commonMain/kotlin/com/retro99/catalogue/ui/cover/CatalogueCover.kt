package com.retro99.catalogue.ui.cover

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberCover
import com.retro99.server.api.CatalogueImageModel

/**
 * The one place a catalogue picture is drawn. It takes the picture together with the catalogue
 * it belongs to, never a bare address, so the image loader asks that catalogue's own session
 * for it (plan §10.7). Without a picture, or until it has loaded, the frame shows [title]'s
 * generated title cover. Failed pictures use exactly the same fallback.
 */
@Composable
fun CatalogueCover(
    image: CatalogueImageModel?,
    title: String,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    // The loader builds its own cache key from profile, catalogue and access generation.
    EmberCover(data = image, cacheKey = null, contentDescription = null, modifier = modifier, fallback = {
        val palette = Ember.colors.catalogueCovers
        BoxWithConstraints(
            Modifier.fillMaxSize().background(palette[generatedCoverColourIndex(title, palette.size)]),
            contentAlignment = Alignment.BottomStart,
        ) {
            val size = (maxWidth.value * .12f).coerceIn(6f, 16f)
            if (showTitle) Text(
                title,
                style = Ember.type.screenTitle.copy(fontSize = size.sp, lineHeight = (size * 1.15f).sp, fontWeight = FontWeight.Bold),
                color = Ember.colors.catalogueCoverInk,
                modifier = Modifier.padding(maxWidth * .09f),
            )
        }
    })
}

/** Stable across process restarts and platforms; never depends on a picture's loading state. */
internal fun generatedCoverColourIndex(title: String, count: Int): Int = (title.hashCode().toUInt() % count.toUInt()).toInt()
