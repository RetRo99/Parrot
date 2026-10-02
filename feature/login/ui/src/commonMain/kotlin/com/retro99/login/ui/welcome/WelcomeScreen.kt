package com.retro99.login.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.ui.compose.Ember
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import resources.translations.welcome_any_device
import resources.translations.welcome_build_debug
import resources.translations.welcome_chapter_three
import resources.translations.welcome_connect_server
import resources.translations.welcome_connect_server_accessibility
import resources.translations.welcome_eink_ready
import resources.translations.welcome_have_account
import resources.translations.welcome_parrot_cloud
import resources.translations.welcome_phone_files
import resources.translations.welcome_phone_files_accessibility
import resources.translations.welcome_sign_in_link
import resources.translations.welcome_subtitle
import resources.translations.welcome_title
import resources.translations.welcome_use_cloud_accessibility
import resources.translations.welcome_cloud_sign_in_accessibility
import resources.translations.welcome_where_books
import resources.translations.welcome_works_offline

@Composable
fun WelcomeScreen(
    isDebug: Boolean,
    onServerClick: () -> Unit,
    onCloudCreateAccountClick: () -> Unit,
    onCloudSignInClick: () -> Unit,
    onPhoneFilesClick: () -> Unit,
    onCompactLayoutAvailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val style = Ember.style

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .semantics { isTraversalGroup = true },
    ) {
        val isCompact = maxHeight < 600.dp
        LaunchedEffect(isCompact) {
            if (isCompact) onCompactLayoutAvailable()
        }

        // Reserve enough room for the title and choices first; let the illustration
        // shrink proportionally on short phones instead of making the page scroll.
        val heroHeight = (maxHeight - 396.dp).coerceIn(230.dp, 450.dp)

        Column(modifier = Modifier.fillMaxSize()) {
            WelcomeHeroPanel(
                height = heroHeight,
                chapterLabel = stringResource(StringRes.welcome_chapter_three),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .padding(top = 26.dp),
                ) {
                    Text(
                        text = stringResource(StringRes.welcome_title),
                        style = Ember.type.screenTitle.copy(
                            fontSize = 44.sp,
                            lineHeight = 52.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.ink,
                        maxLines = 1,
                        modifier = Modifier.semantics { traversalIndex = 0f },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(StringRes.welcome_subtitle),
                        style = Ember.type.meta.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        color = colors.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { traversalIndex = 1f },
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(StringRes.welcome_where_books),
                        style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 18.sp),
                        color = colors.ink2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { traversalIndex = 3f },
                    )

                    WelcomeChoiceButton(
                        label = stringResource(StringRes.welcome_parrot_cloud),
                        accessibilityLabel = stringResource(StringRes.welcome_use_cloud_accessibility),
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = onCloudCreateAccountClick,
                        orderIndex = 4f,
                    )

                    WelcomeChoiceButton(
                        label = stringResource(StringRes.welcome_connect_server),
                        accessibilityLabel = stringResource(StringRes.welcome_connect_server_accessibility),
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Dns,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = onServerClick,
                        orderIndex = 5f,
                    )

                    WelcomeChoiceButton(
                        label = stringResource(StringRes.welcome_phone_files),
                        accessibilityLabel = stringResource(StringRes.welcome_phone_files_accessibility),
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = onPhoneFilesClick,
                        orderIndex = 6f,
                    )

                    WelcomeSignInLink(
                        onCloudSignInClick = onCloudSignInClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { traversalIndex = 7f },
                    )
                }
            }
        }

        if (isDebug) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 16.dp),
                shape = CircleShape,
                color = colors.welcomeChip,
                border = if (style.isEink) androidx.compose.foundation.BorderStroke(2.dp, colors.line) else null,
            ) {
                Text(
                    text = stringResource(StringRes.welcome_build_debug),
                    style = Ember.type.eyebrow.copy(fontSize = 10.sp),
                    color = colors.accentText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun WelcomeChoiceButton(
    label: String,
    accessibilityLabel: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    orderIndex: Float,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val shape = CircleShape

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (isEink) colors.surface else colors.navActive)
            .then(if (isEink) Modifier.border(2.dp, colors.line, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = accessibilityLabel
                traversalIndex = orderIndex
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProviderContentColor(icon = icon, color = if (isEink) colors.ink else colors.accentText)
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (isEink) colors.ink else colors.accentText,
            style = Ember.type.label.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun CompositionLocalProviderContentColor(icon: @Composable () -> Unit, color: Color) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides color,
        content = icon,
    )
}

@Composable
private fun WelcomeSignInLink(
    onCloudSignInClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val cloudSignInAccessibility = stringResource(StringRes.welcome_cloud_sign_in_accessibility)
    val cloudSignInOrder = 7f
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(StringRes.welcome_have_account),
            modifier = Modifier.alignByBaseline(),
            style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = colors.ink2,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(StringRes.welcome_sign_in_link),
            modifier = Modifier
                .alignByBaseline()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onCloudSignInClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = cloudSignInAccessibility
                    traversalIndex = cloudSignInOrder
                }
                .padding(horizontal = 4.dp, vertical = 12.dp),
            style = Ember.type.label.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = colors.accentText,
            maxLines = 1,
            softWrap = false,
        )
    }
}
