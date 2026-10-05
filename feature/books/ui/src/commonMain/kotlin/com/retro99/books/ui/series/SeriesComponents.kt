package com.retro99.books.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.*

/** Draft and submitted queries are saved independently: E-ink redraws on submit only. */
@Composable
fun SeriesSearch(placeholder: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    var draft by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    val eink = Ember.style.isEink
    LaunchedEffect(draft, submitted, eink) { onQuery(if (eink) submitted else draft) }
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = Ember.type.meta.copy(fontSize = 15.sp, color = Ember.colors.ink),
        cursorBrush = SolidColor(Ember.colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { submitted = draft }),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
                .background(Ember.colors.surface).border(Ember.style.border, Ember.colors.line, shape)
                .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Search, null, tint = Ember.colors.ink2)
                Box(Modifier.weight(1f)) {
                    if (draft.isEmpty()) Text(placeholder, style = Ember.type.meta, color = Ember.colors.ink2)
                    field()
                }
                if (eink) TextButton(onClick = { submitted = draft }) {
                    Text("Search", color = Ember.colors.accentText)
                }
            }
        },
    )
}

@Composable
fun SeriesCover(coverUrl: String?, title: String, key: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.size(44.dp, 60.dp).clip(shape).background(if (Ember.style.isEink) Ember.colors.surface else Ember.colors.navActive)
        .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, shape) else Modifier),
        contentAlignment = Alignment.Center) {
        if (coverUrl == null) {
            Text(title.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() },
                style = Ember.type.screenTitle.copy(fontSize = 15.sp), color = if (Ember.style.isEink) Ember.colors.ink else Ember.colors.navActiveContent)
        } else {
            EmberCover(coverUrl, key, null, Modifier.matchParentSize())
        }
    }
}

@Composable
fun SeriesSourceError(source: String, onRetry: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Ember.colors.errorContainer)
        .then(if (Ember.style.isEink) Modifier.border(2.dp, Ember.colors.line, shape) else Modifier)
        .padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Can’t reach $source.", style = Ember.type.meta.copy(fontWeight = FontWeight.Bold), color = Ember.colors.destructive)
                Text("Series from that server may be missing.", style = Ember.type.meta, color = Ember.colors.destructive)
            }
            TextButton(onClick = onRetry) { Text("Try again", color = Ember.colors.accentText) }
        }
    }
}
