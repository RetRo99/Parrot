package com.retro99.home.ui.appsettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.translations.StringRes
import com.retro99.user.api.UserProfile
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import resources.translations.app_settings_profile_name_already_exists
import resources.translations.settings_profile_color_brick
import resources.translations.settings_profile_color_copper
import resources.translations.settings_profile_color_moss
import resources.translations.settings_profile_color_plum
import resources.translations.settings_profile_color_slate
import resources.translations.settings_profile_delete
import resources.translations.settings_profile_delete_only_profile
import resources.translations.settings_profile_edit_color
import resources.translations.settings_profile_edit_done
import resources.translations.settings_profile_edit_name
import resources.translations.settings_profile_edit_title

private val COLOR_SWATCHES = listOf(
    Color(0xFFA9561F),
    Color(0xFF3E5A34),
    Color(0xFF8E3B2E),
    Color(0xFF3D4A5C),
    Color(0xFF6A3A5A),
)

private val EINK_SWATCHES = listOf(
    Color(0xFF000000),
    Color(0xFF444444),
    Color(0xFF6E6E6E),
    Color(0xFF8A8A8A),
    Color(0xFFA6A6A6),
)

private val COLOR_NAMES: List<StringResource> = listOf(
    StringRes.settings_profile_color_copper,
    StringRes.settings_profile_color_moss,
    StringRes.settings_profile_color_brick,
    StringRes.settings_profile_color_slate,
    StringRes.settings_profile_color_plum,
)

/** Fill color for a profile's avatar. The index is stored in `UserProfile.avatarId`. */
@Composable
internal fun profileAvatarColor(colorIndex: Int?): Color {
    val index = (colorIndex ?: 0).coerceIn(0, COLOR_SWATCHES.lastIndex)
    return if (Ember.style.isEink) EINK_SWATCHES[index] else COLOR_SWATCHES[index]
}

/** Text and icon color on a profile avatar. */
internal val ProfileAvatarContentColor = Color.White

/**
 * Edit profile sheet: name, color and delete. Done saves both name and color and closes.
 * The avatar preview follows the draft.
 */
@Composable
internal fun ProfileEditSheet(
    profile: UserProfile,
    canDelete: Boolean,
    showDuplicateNameError: Boolean,
    isBusy: Boolean,
    onSave: (name: String, colorIndex: Int) -> Unit,
    onNameChanged: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var colorIndex by remember(profile.id) {
        mutableIntStateOf((profile.avatarId ?: 0).coerceIn(0, COLOR_SWATCHES.lastIndex))
    }
    val save = {
        if (name.isNotBlank() && !isBusy) onSave(name, colorIndex)
    }

    EmberBottomSheet(
        onDismiss = { if (!isBusy) onDismiss() },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(StringRes.settings_profile_edit_title),
                    style = Ember.type.screenTitle.copy(fontSize = 26.sp),
                    color = colors.ink,
                )
                Box(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clip(CircleShape)
                        .clickable(enabled = name.isNotBlank() && !isBusy, role = Role.Button, onClick = { save() })
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(StringRes.settings_profile_edit_done),
                        style = Ember.type.label.copy(fontSize = 15.sp),
                        color = if (name.isNotBlank()) colors.accentText else colors.ink2,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(profileAvatarColor(colorIndex)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = name.trim().firstOrNull()?.uppercase().orEmpty(),
                        style = Ember.type.screenTitle.copy(fontSize = 32.sp),
                        color = ProfileAvatarContentColor,
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(StringRes.settings_profile_edit_name).uppercase(),
                        style = Ember.type.eyebrow.copy(
                            fontSize = if (style.isEink) 13.sp else 11.sp,
                            letterSpacing = 1.5.sp,
                        ),
                        color = colors.ink2,
                    )
                    val outline = if (showDuplicateNameError) colors.destructive else colors.chipBorder
                    BasicTextField(
                        value = name,
                        onValueChange = { value ->
                            name = value
                            onNameChanged()
                        },
                        singleLine = true,
                        enabled = !isBusy,
                        textStyle = Ember.type.meta.copy(fontSize = 18.sp, color = colors.ink),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.bg)
                                    .border(
                                        if (style.isEink) 2.dp else 1.5.dp,
                                        outline,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                innerTextField()
                            }
                        },
                    )
                    if (showDuplicateNameError) {
                        Text(
                            text = stringResource(StringRes.app_settings_profile_name_already_exists),
                            style = Ember.type.meta,
                            color = colors.destructive,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            Text(
                text = stringResource(StringRes.settings_profile_edit_color).uppercase(),
                style = Ember.type.eyebrow.copy(
                    fontSize = if (style.isEink) 13.sp else 11.sp,
                    letterSpacing = 1.5.sp,
                ),
                color = colors.ink2,
                modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                COLOR_SWATCHES.indices.forEach { index ->
                    ColorSwatch(
                        color = if (style.isEink) EINK_SWATCHES[index] else COLOR_SWATCHES[index],
                        name = stringResource(COLOR_NAMES[index]),
                        selected = index == colorIndex,
                        onClick = { colorIndex = index },
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 18.dp),
                thickness = style.border,
                color = colors.line,
            )

            DeleteRow(canDelete = canDelete && !isBusy, isOnlyProfile = !canDelete, onClick = onDelete)
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color)
            .then(if (selected) Modifier.border(3.dp, colors.ink, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = name,
                modifier = Modifier.size(22.dp),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun DeleteRow(
    canDelete: Boolean,
    isOnlyProfile: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    val contentColor = if (canDelete) colors.destructive else colors.ink2

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = canDelete, role = Role.Button, onClick = onClick)
            .then(if (canDelete) Modifier else Modifier.alpha(0.8f))
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = stringResource(StringRes.settings_profile_delete),
                style = Ember.type.meta.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = contentColor,
            )
            if (isOnlyProfile) {
                Text(
                    text = stringResource(StringRes.settings_profile_delete_only_profile),
                    style = Ember.type.meta,
                    color = colors.ink2,
                )
            }
        }
    }
}
