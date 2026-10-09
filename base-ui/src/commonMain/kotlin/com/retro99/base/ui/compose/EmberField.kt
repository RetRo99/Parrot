package com.retro99.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ember text field: label above, 52dp high, 16dp radius. Focus draws a 2dp `accent`
 * border; an error draws a 2dp `err` border with one sentence under the field; E-ink
 * keeps a 2dp `line` border.
 */
@Composable
fun EmberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helperText: String? = null,
    isError: Boolean = false,
    errorText: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    focusRequester: FocusRequester? = null,
    labelStyle: TextStyle = Ember.type.meta.copy(fontSize = 13.sp),
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val borderWidth = when {
        isError || focused || Ember.style.isEink -> 2.dp
        else -> 1.dp
    }
    val borderColor = when {
        isError -> Ember.colors.destructive
        focused -> Ember.colors.accent
        else -> Ember.colors.line
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = labelStyle,
            color = Ember.colors.ink2,
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = Ember.type.meta.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = Ember.colors.ink,
            ),
            cursorBrush = SolidColor(Ember.colors.accent),
            keyboardOptions = keyboardOptions,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(shape)
                        .background(Ember.colors.surface)
                        .border(borderWidth, borderColor, shape)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
            },
        )
        val supporting = when {
            isError && errorText != null -> errorText to Ember.colors.destructive
            helperText != null -> helperText to Ember.colors.ink2
            else -> null
        }
        if (supporting != null) {
            Text(
                text = supporting.first,
                style = Ember.type.meta.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = supporting.second,
            )
        }
    }
}
