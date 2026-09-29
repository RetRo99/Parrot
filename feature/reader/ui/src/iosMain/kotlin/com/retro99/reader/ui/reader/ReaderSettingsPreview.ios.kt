package com.retro99.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.settings_reader_preview_sample

/** iOS shows a plain text sample; a Readium-rendered preview is Android-only for now. */
@Composable
internal actual fun ReaderSettingsPreviewPage(
    settings: ReaderSettingsDomainModel,
    showReadAloudHighlight: Boolean,
    modifier: Modifier,
) {
    val colors = Ember.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(StringRes.settings_reader_preview_sample),
            style = TextStyle(fontSize = (16 * settings.fontSize).sp),
            color = colors.ink,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
