package com.retro99.books.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import resources.translations.books_dock_close_search
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.books_dock_add
import resources.translations.books_dock_add_description
import resources.translations.books_dock_search

private val DockHeight = 56.dp
private val DockShape = RoundedCornerShape(18.dp)
private val EinkBorder = 2.dp
private val ActiveBorder = 1.5.dp
private val ActiveEinkBorder = 2.5.dp

/**
 * Floating search field and Add button pinned above the bottom navigation on the Library tab.
 * While [isSearchActive] the field is outlined in the accent color, Add becomes a close button and
 * the dock rides above the keyboard (or 20dp above the screen edge when it is hidden).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryDock(
    searchFieldState: TextFieldState,
    isSearchActive: Boolean,
    focusRequester: FocusRequester,
    onSearchFocused: () -> Unit,
    onSearchSubmitted: () -> Unit,
    onCloseSearch: () -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val insetsModifier = if (isSearchActive) {
        Modifier
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(bottom = if (WindowInsets.isImeVisible) 8.dp else 20.dp)
    } else {
        Modifier.padding(bottom = 16.dp)
    }

    Row(
        modifier = modifier
            .then(insetsModifier)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(
            state = searchFieldState,
            isActive = isSearchActive,
            focusRequester = focusRequester,
            onFocused = onSearchFocused,
            onSubmitted = onSearchSubmitted,
            modifier = Modifier.weight(1f),
        )
        if (isSearchActive) {
            CloseSearchButton(onClick = onCloseSearch)
        } else {
            AddButton(onClick = onAddClick)
        }
    }
}

@Composable
private fun SearchField(
    state: TextFieldState,
    isActive: Boolean,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onSubmitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val label = stringResource(StringRes.books_dock_search)
    val outline = when {
        isActive && style.isEink -> ActiveEinkBorder to colors.chipBorder
        isActive -> ActiveBorder to colors.accent
        style.isEink -> EinkBorder to colors.chipBorder
        else -> style.border to colors.chipBorder
    }
    val textStyle = Ember.type.meta.copy(fontSize = 16.sp, color = colors.ink)

    Row(
        modifier = modifier
            .dockShadow()
            .height(DockHeight)
            .clip(DockShape)
            .background(colors.surface)
            .border(outline.first, outline.second, DockShape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (isActive) colors.ink else colors.ink2,
        )
        Spacer(modifier = Modifier.width(12.dp))
        BasicTextField(
            state = state,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        state.edit { placeCursorAtEnd() }
                        onFocused()
                    }
                }
                .semantics { contentDescription = label },
            textStyle = textStyle,
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            onKeyboardAction = { onSubmitted() },
            decorator = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty()) {
                        Text(
                            text = label,
                            style = textStyle.copy(color = colors.ink2),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun CloseSearchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    val description = stringResource(StringRes.books_dock_close_search)
    val borderWidth = if (style.isEink) EinkBorder else style.border

    Box(
        modifier = modifier
            .dockShadow()
            .size(DockHeight)
            .clip(DockShape)
            .background(colors.surface)
            .border(borderWidth, colors.chipBorder, DockShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.ink,
        )
    }
}

@Composable
private fun AddButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val description = stringResource(StringRes.books_dock_add_description)

    Row(
        modifier = modifier
            .dockShadow()
            .height(DockHeight)
            .clip(DockShape)
            .background(colors.accent)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = colors.onAccent,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(StringRes.books_dock_add),
            style = Ember.type.meta.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = colors.onAccent,
            maxLines = 1,
        )
    }
}

/** Soft drop shadow in Night (50% black) and Day (22% black); none on e-ink. */
@Composable
private fun Modifier.dockShadow(): Modifier {
    if (Ember.style.isEink) return this
    val isNight = Ember.colors.bg.luminance() < 0.5f
    val shadowColor = Color.Black.copy(alpha = if (isNight) 0.5f else 0.22f)
    return shadow(
        elevation = 12.dp,
        shape = DockShape,
        ambientColor = shadowColor,
        spotColor = shadowColor,
    )
}
