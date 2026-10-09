package com.retro99.catalogue.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember

/**
 * The page-level message: icon tile, title, one sentence, then whatever follows (buttons).
 * Only the title is a heading, so a screen reader jumps to it and reads the rest in order.
 */
@Composable
internal fun CatalogueMessage(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, below: @Composable ColumnScope.() -> Unit = {}) {
    val eink = Ember.style.isEink
    val tile = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(tile).background(if (eink) Ember.colors.surface else Ember.colors.navActive)
                .then(if (eink) Modifier.border(2.dp, Ember.colors.line, tile) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, modifier = Modifier.size(30.dp), tint = if (eink) Ember.colors.ink else Ember.colors.accentText) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = Ember.type.screenTitle.copy(fontSize = 22.sp, lineHeight = 28.sp), color = Ember.colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(12.dp))
        Text(body, style = Ember.type.meta.copy(fontSize = 17.sp, lineHeight = 25.sp), color = Ember.colors.ink2, textAlign = TextAlign.Center)
        below()
    }
}
