package com.retro99.books.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberChevron
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_saved_title

@Composable
internal fun BookDetailSavedRow(subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Ember.colors.surface)
            .border(Ember.style.detailBorder, Ember.colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(
            if (Ember.style.isEink) Ember.colors.ink.copy(alpha = .06f) else Ember.colors.navActive,
            RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Bookmark, null, tint = Ember.colors.accentText,
                modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(StringRes.book_detail_saved_title),
                style = Ember.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                color = Ember.colors.ink)
            Text(subtitle, style = Ember.type.meta.copy(fontSize = 13.sp), color = Ember.colors.ink2)
        }
        EmberChevron()
    }
}
