package com.retro99.books.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.book_detail_load_error
import resources.translations.book_detail_load_reason
import resources.translations.book_detail_retry
import resources.translations.book_detail_servers

@Composable
internal fun BookDetailError(onRetry: () -> Unit, onServers: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.Storage, contentDescription = null, tint = Ember.colors.accentText)
        Text(stringResource(StringRes.book_detail_load_error), style = Ember.type.screenTitle,
            color = Ember.colors.ink, textAlign = TextAlign.Center)
        Text(stringResource(StringRes.book_detail_load_reason), style = Ember.type.meta,
            color = Ember.colors.ink2, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailButton(stringResource(StringRes.book_detail_retry), onRetry, primary = true)
            DetailButton(stringResource(StringRes.book_detail_servers), onServers)
        }
    }
}
