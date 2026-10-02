package com.retro99.login.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retro99.base.server.ServerType
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.base.ui.compose.Ember
import com.retro99.base.ui.compose.EmberBottomSheet
import com.retro99.base.ui.compose.EmberSectionLabel
import com.retro99.translations.StringRes
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.login_address_checking
import resources.translations.login_address_found
import resources.translations.login_address_found_switched
import resources.translations.login_address_help_link
import resources.translations.login_address_helper
import resources.translations.login_address_label
import resources.translations.login_address_not_supported
import resources.translations.login_address_placeholder
import resources.translations.login_address_unreachable
import resources.translations.login_browser_sign_in_button
import resources.translations.login_browser_sign_in_caption
import resources.translations.login_connect_subtitle
import resources.translations.login_connect_title
import resources.translations.login_error_invalid_credentials
import resources.translations.login_error_invalid_url
import resources.translations.login_error_required_password
import resources.translations.login_error_required_url
import resources.translations.login_error_required_username
import resources.translations.login_help_got_it
import resources.translations.login_help_note
import resources.translations.login_help_step_copy
import resources.translations.login_help_step_open
import resources.translations.login_help_step_paste
import resources.translations.login_help_title
import resources.translations.login_hide_password
import resources.translations.login_oauth_waiting_message
import resources.translations.login_or_divider
import resources.translations.login_password_label
import resources.translations.login_server_unavailable
import resources.translations.login_show_password
import resources.translations.login_sign_in_button
import resources.translations.login_signing_in
import resources.translations.login_type_audiobookshelf_description
import resources.translations.login_type_storyteller_description
import resources.translations.login_username_label
import resources.translations.servers_not_encrypted

private val ScreenPadding = 22.dp
private val FieldHeight = 54.dp
private val FieldShape = RoundedCornerShape(14.dp)
private val CardShape = RoundedCornerShape(16.dp)
private const val EXAMPLE_DOMAIN = "books.example.com"
private const val EXAMPLE_LOCAL = "192.168.1.20:8001"
private const val HTTPS_PREFIX = "https://"

@Composable
fun LoginScreen(
    onSignInSuccess: () -> Unit,
    onSignInAttemptStarted: (String, String, String) -> Unit = { _, _, _ -> },
    onSignInFailure: (String, String, String) -> Unit = { _, _, _ -> },
    onBackClick: () -> Unit,
    existingServerId: String? = null,
    isRetryOrigin: Boolean = false,
    draft: LoginDraft = remember { LoginDraft() },
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = koinViewModel {
        parametersOf(
            onSignInSuccess,
            onSignInAttemptStarted,
            onSignInFailure,
            onBackClick,
            existingServerId,
            isRetryOrigin,
            draft,
        )
    },
) {
    BaseScreen(
        modifier = modifier.imePadding(),
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        LoginScreenContent(
            urlState = viewModel.urlState,
            usernameState = viewModel.usernameState,
            passwordState = viewModel.passwordState,
            viewState = viewState,
            isExistingServerLogin = existingServerId != null,
            intentDispatcher = intentDispatcher,
            onBackClick = onBackClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun LoginScreenContent(
    urlState: TextFieldState,
    usernameState: TextFieldState,
    passwordState: TextFieldState,
    viewState: LoginViewState,
    isExistingServerLogin: Boolean,
    intentDispatcher: IntentDispatcher<LoginIntent>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }
    var showAddressHelp by remember { mutableStateOf(false) }
    val addressFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    LaunchedEffect(viewState.focusRequest?.id) {
        when (viewState.focusRequest?.field) {
            LoginField.Address -> addressFocus.requestFocus()
            LoginField.Username -> usernameFocus.requestFocus()
            LoginField.Password -> passwordFocus.requestFocus()
            null -> Unit
        }
    }

    val addressStatus = addressStatus(viewState)
    val usernameError = viewState.usernameError?.let {
        stringResource(StringRes.login_error_required_username)
    }
    val passwordError = when {
        viewState.credentialsRejected -> stringResource(StringRes.login_error_invalid_credentials)
        viewState.passwordError != null -> stringResource(StringRes.login_error_required_password)
        else -> null
    }
    val formError = when {
        viewState.serverConfigurationUnavailable -> stringResource(StringRes.login_server_unavailable)
        else -> viewState.loginError
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = ScreenPadding),
        ) {
            // Room for the Back button, which is drawn on top of the scrolling form.
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = stringResource(StringRes.login_connect_title),
                style = Ember.type.screenTitle.copy(fontSize = 30.sp, lineHeight = 36.sp),
                color = colors.ink,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(StringRes.login_connect_subtitle),
                style = Ember.type.meta.copy(fontSize = 15.sp),
                color = colors.ink2,
            )

            Spacer(modifier = Modifier.height(20.dp))

            ServerTypeCards(
                selected = viewState.selectedServerType,
                enabled = !isExistingServerLogin && !viewState.isLoading,
                onSelect = { serverType ->
                    intentDispatcher(LoginIntent.OnServerTypePickerOpened)
                    intentDispatcher(LoginIntent.OnServerTypeSelected(serverType))
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EmberSectionLabel(text = stringResource(StringRes.login_address_label))
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            intentDispatcher(LoginIntent.OnUrlHelpOpenRequested)
                            showAddressHelp = true
                        }
                        .padding(start = 12.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = stringResource(StringRes.login_address_help_link),
                        style = Ember.type.label.copy(
                            fontSize = if (Ember.style.isEink) 14.sp else 13.sp,
                        ),
                        color = colors.accentText,
                    )
                }
            }
            LoginTextField(
                state = urlState,
                placeholder = stringResource(StringRes.login_address_placeholder),
                isError = addressStatus.isError,
                errorMessage = addressStatus.message.takeIf { addressStatus.isError },
                enabled = !isExistingServerLogin,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                onKeyboardAction = { focusManager.moveFocus(FocusDirection.Down) },
                focusRequester = addressFocus,
                onFocusLost = { intentDispatcher(LoginIntent.OnUrlFocusLost) },
            )
            FieldMessage(
                text = addressStatus.message,
                tone = addressStatus.tone,
            )

            Spacer(modifier = Modifier.height(14.dp))
            EmberSectionLabel(text = stringResource(StringRes.login_username_label))
            Spacer(modifier = Modifier.height(8.dp))
            LoginTextField(
                state = usernameState,
                isError = usernameError != null,
                errorMessage = usernameError,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                onKeyboardAction = { focusManager.moveFocus(FocusDirection.Down) },
                focusRequester = usernameFocus,
                contentType = ContentType.Username,
            )
            FieldMessage(text = usernameError, tone = MessageTone.Error)

            Spacer(modifier = Modifier.height(14.dp))
            EmberSectionLabel(text = stringResource(StringRes.login_password_label))
            Spacer(modifier = Modifier.height(8.dp))
            LoginTextField(
                state = passwordState,
                isError = passwordError != null,
                errorMessage = passwordError,
                isSecure = true,
                passwordVisible = passwordVisible,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                onKeyboardAction = {
                    focusManager.clearFocus()
                    if (!viewState.isLoading) {
                        intentDispatcher(LoginIntent.OnSignInClicked)
                    }
                },
                focusRequester = passwordFocus,
                contentType = ContentType.Password,
                trailing = {
                    PasswordVisibilityToggle(
                        isVisible = passwordVisible,
                        onToggle = {
                            val newVisibility = !passwordVisible
                            passwordVisible = newVisibility
                            intentDispatcher(LoginIntent.OnPasswordVisibilityChanged(newVisibility))
                        },
                    )
                },
            )
            FieldMessage(text = passwordError, tone = MessageTone.Error)
            FieldMessage(text = formError, tone = MessageTone.Error)

            Spacer(modifier = Modifier.height(24.dp))

            EmberPillButton(
                text = stringResource(
                    if (viewState.isLoading && !viewState.isOAuthInProgress) {
                        StringRes.login_signing_in
                    } else {
                        StringRes.login_sign_in_button
                    },
                ),
                onClick = { intentDispatcher(LoginIntent.OnSignInClicked) },
                enabled = viewState.isSignInEnabled,
                inProgress = viewState.isLoading && !viewState.isOAuthInProgress,
            )

            if (viewState.isOAuthVisible) {
                BrowserSignIn(
                    isEnabled = viewState.isOAuthSignInEnabled,
                    isInProgress = viewState.isOAuthInProgress,
                    onClick = { intentDispatcher(LoginIntent.OnOAuthSignInClicked) },
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Keep Back above the full-screen scrollable form for touch and accessibility hit-testing.
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 12.dp, top = 4.dp)
                .size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(StringRes.general_back),
                tint = colors.ink,
            )
        }
    }

    if (showAddressHelp) {
        AddressHelpSheet(
            serverName = viewState.selectedServerType.displayName,
            onDismiss = {
                intentDispatcher(
                    LoginIntent.OnUrlHelpDismissed(UrlHelpDismissalReason.DismissRequest),
                )
                showAddressHelp = false
            },
            onGotIt = {
                intentDispatcher(LoginIntent.OnUrlHelpDismissed(UrlHelpDismissalReason.GotIt))
                showAddressHelp = false
            },
        )
        LaunchedEffect(Unit) { intentDispatcher(LoginIntent.OnUrlHelpOpened) }
    }
}

private enum class MessageTone { Neutral, Success, Warning, Error }

private data class AddressStatus(
    val message: String?,
    val tone: MessageTone,
    val isError: Boolean,
)

@Composable
private fun addressStatus(viewState: LoginViewState): AddressStatus {
    val invalidUrl = stringResource(StringRes.login_error_invalid_url)
    val requiredUrl = stringResource(StringRes.login_error_required_url)
    val check = viewState.addressCheck
    val helper = stringResource(StringRes.login_address_helper)
    val checking = stringResource(StringRes.login_address_checking)
    val notSupported = stringResource(StringRes.login_address_not_supported)
    return when {
        viewState.urlError == LoginFieldError.InvalidUrl ->
            AddressStatus(invalidUrl, MessageTone.Error, isError = true)
        viewState.urlError == LoginFieldError.Required ->
            AddressStatus(requiredUrl, MessageTone.Error, isError = true)
        viewState.unreachableOnSignIn != null -> AddressStatus(
            stringResource(StringRes.login_address_unreachable, viewState.unreachableOnSignIn),
            MessageTone.Error,
            isError = true,
        )
        check is AddressCheck.Unreachable -> AddressStatus(
            stringResource(StringRes.login_address_unreachable, check.host),
            MessageTone.Warning,
            isError = false,
        )
        check == AddressCheck.NotSupported ->
            AddressStatus(notSupported, MessageTone.Warning, isError = false)
        check is AddressCheck.Found -> {
            val found = if (check.switched) {
                stringResource(StringRes.login_address_found_switched, check.serverType.displayName)
            } else {
                stringResource(
                    StringRes.login_address_found,
                    check.serverType.displayName,
                    check.host,
                )
            }
            if (check.isInsecure) {
                AddressStatus(
                    "$found · ${stringResource(StringRes.servers_not_encrypted)}",
                    MessageTone.Warning,
                    isError = false,
                )
            } else {
                AddressStatus(found, MessageTone.Success, isError = false)
            }
        }
        check == AddressCheck.Checking -> AddressStatus(checking, MessageTone.Neutral, isError = false)
        else -> AddressStatus(helper, MessageTone.Neutral, isError = false)
    }
}

@Composable
private fun FieldMessage(
    text: String?,
    tone: MessageTone,
) {
    if (text == null) return
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val color = when {
        isEink -> colors.ink
        tone == MessageTone.Error -> colors.error
        tone == MessageTone.Success -> colors.success
        tone == MessageTone.Warning -> colors.accentText
        else -> colors.ink2
    }
    val weight = if (tone == MessageTone.Neutral && !isEink) FontWeight.Normal else FontWeight.Bold
    Text(
        text = text,
        style = Ember.type.meta.copy(fontSize = 14.sp, fontWeight = weight),
        color = color,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ServerTypeCards(
    selected: ServerType,
    enabled: Boolean,
    onSelect: (ServerType) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServerTypeCard(
            serverType = ServerType.Storyteller,
            letter = "S",
            description = stringResource(StringRes.login_type_storyteller_description),
            selected = selected == ServerType.Storyteller,
            enabled = enabled,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
        ServerTypeCard(
            serverType = ServerType.Audiobookshelf,
            letter = "A",
            description = stringResource(StringRes.login_type_audiobookshelf_description),
            selected = selected == ServerType.Audiobookshelf,
            enabled = enabled,
            onSelect = onSelect,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ServerTypeCard(
    serverType: ServerType,
    letter: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (ServerType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val borderWidth = when {
        selected -> if (isEink) 3.dp else 2.dp
        else -> if (isEink) 2.dp else 1.5.dp
    }
    val borderColor = when {
        selected -> colors.accent
        else -> colors.chipBorder
    }
    val fill = if (selected && !isEink) colors.navActive else colors.surface
    val tileFill = when {
        isEink -> if (selected) colors.ink else colors.surface
        else -> colors.accent.copy(alpha = if (selected) 0.22f else 0.14f)
    }
    val tileContent = when {
        isEink -> if (selected) colors.onAccent else colors.ink
        else -> colors.accentText
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 92.dp)
            .clip(CardShape)
            .background(fill)
            .border(borderWidth, borderColor, CardShape)
            .selectable(
                selected = selected,
                enabled = enabled || selected,
                role = Role.RadioButton,
                onClick = { if (enabled && !selected) onSelect(serverType) },
            )
            .padding(14.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tileFill),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter,
                    style = Ember.type.cardTitle.copy(fontSize = 18.sp),
                    color = tileContent,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = serverType.displayName,
                style = Ember.type.label.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                color = colors.ink,
            )
            Text(
                text = description,
                style = Ember.type.meta.copy(fontSize = if (isEink) 13.sp else 12.sp),
                color = colors.ink2,
                minLines = 2,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun LoginTextField(
    state: TextFieldState,
    isError: Boolean,
    errorMessage: String?,
    keyboardOptions: KeyboardOptions,
    onKeyboardAction: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isSecure: Boolean = false,
    passwordVisible: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onFocusLost: (() -> Unit)? = null,
    contentType: ContentType? = null,
) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    var focused by remember { mutableStateOf(false) }
    val borderWidth: Dp = when {
        isEink -> if (isError || focused) 3.dp else 2.dp
        isError || focused -> 2.dp
        else -> 1.5.dp
    }
    val borderColor = when {
        isEink -> colors.line
        isError -> colors.error
        focused -> colors.accent
        else -> colors.chipBorder
    }
    val textStyle = Ember.type.meta.copy(
        fontSize = 16.sp,
        color = if (enabled) colors.ink else colors.ink2,
    )
    val decorator = TextFieldDecorator { innerTextField ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FieldHeight)
                .clip(FieldShape)
                .background(colors.surface)
                .border(borderWidth, borderColor, FieldShape)
                .padding(start = 18.dp, end = if (trailing != null) 4.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (placeholder != null && state.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = colors.ink2.copy(alpha = if (isEink) 1f else 0.6f),
                    )
                }
                innerTextField()
            }
            trailing?.invoke()
        }
    }
    val fieldModifier = modifier
        .fillMaxWidth()
        .focusRequester(focusRequester)
        .onFocusChanged { focusState ->
            if (focused && !focusState.isFocused) onFocusLost?.invoke()
            focused = focusState.isFocused
        }
        .semantics {
            if (contentType != null) this.contentType = contentType
            if (isError && errorMessage != null) error(errorMessage)
        }

    if (isSecure) {
        BasicSecureTextField(
            state = state,
            modifier = fieldModifier,
            enabled = enabled,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = { onKeyboardAction() },
            textObfuscationMode = if (passwordVisible) {
                TextObfuscationMode.Visible
            } else {
                TextObfuscationMode.Hidden
            },
            decorator = decorator,
        )
    } else {
        BasicTextField(
            state = state,
            modifier = fieldModifier,
            enabled = enabled,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.accent),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = { onKeyboardAction() },
            decorator = decorator,
        )
    }
}

@Composable
private fun PasswordVisibilityToggle(
    isVisible: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = if (isVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = if (isVisible) {
                stringResource(StringRes.login_hide_password)
            } else {
                stringResource(StringRes.login_show_password)
            },
            tint = Ember.colors.ink2,
        )
    }
}

@Composable
private fun BrowserSignIn(
    isEnabled: Boolean,
    isInProgress: Boolean,
    onClick: () -> Unit,
) {
    val colors = Ember.colors
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.line))
        Text(
            text = stringResource(StringRes.login_or_divider),
            style = Ember.type.meta.copy(fontSize = 14.sp),
            color = colors.ink2,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(colors.line))
    }
    Spacer(modifier = Modifier.height(16.dp))
    EmberPillButton(
        text = stringResource(StringRes.login_browser_sign_in_button),
        onClick = onClick,
        enabled = isEnabled,
        inProgress = isInProgress,
        outlined = true,
        icon = Icons.AutoMirrored.Filled.OpenInNew,
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(
            if (isInProgress) StringRes.login_oauth_waiting_message else StringRes.login_browser_sign_in_caption,
        ),
        style = Ember.type.meta.copy(fontSize = if (Ember.style.isEink) 14.sp else 13.sp),
        color = colors.ink2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 54dp full-width pill. The spinner is replaced by plain text in E-ink mode (no motion). */
@Composable
private fun EmberPillButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    inProgress: Boolean,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = Ember.colors
    val isEink = Ember.style.isEink
    val active = enabled || inProgress
    val fill = when {
        outlined -> Color.Transparent
        active -> colors.accent
        isEink -> colors.surface
        else -> colors.line
    }
    val content = when {
        outlined -> if (active) colors.ink else colors.ink2
        active -> colors.onAccent
        else -> colors.ink2.copy(alpha = if (isEink) 1f else 0.55f)
    }
    val borderWidth = if (isEink) 2.dp else 1.5.dp
    val border = when {
        outlined -> Modifier.border(borderWidth, colors.chipBorder, CircleShape)
        isEink && !active -> Modifier.border(borderWidth, colors.line, CircleShape)
        else -> Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(CircleShape)
            .background(fill)
            .then(border)
            .clickable(enabled = enabled && !inProgress, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (inProgress && !isEink) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = content,
            )
            Spacer(modifier = Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = Ember.type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            color = content,
        )
    }
}

@Composable
private fun AddressHelpSheet(
    serverName: String,
    onDismiss: () -> Unit,
    onGotIt: () -> Unit,
) {
    val colors = Ember.colors
    val steps = listOf(
        AnnotatedString(stringResource(StringRes.login_help_step_open, serverName)),
        boldExamples(
            text = stringResource(StringRes.login_help_step_copy, EXAMPLE_DOMAIN, EXAMPLE_LOCAL),
            bold = listOf(EXAMPLE_DOMAIN, EXAMPLE_LOCAL),
        ),
        boldExamples(
            text = stringResource(StringRes.login_help_step_paste, HTTPS_PREFIX),
            bold = listOf(HTTPS_PREFIX),
        ),
    )
    EmberBottomSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 20.dp),
        ) {
            Text(
                text = stringResource(StringRes.login_help_title),
                style = Ember.type.cardTitle.copy(fontSize = 24.sp, lineHeight = 30.sp),
                color = colors.ink,
            )
            Spacer(modifier = Modifier.height(16.dp))
            steps.forEachIndexed { index, step ->
                Row(modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(
                        text = "${index + 1}.",
                        style = Ember.type.meta.copy(fontSize = 17.sp),
                        color = colors.ink,
                        modifier = Modifier.width(26.dp),
                    )
                    Text(
                        text = step,
                        style = Ember.type.meta.copy(fontSize = 17.sp, lineHeight = 25.sp),
                        color = colors.ink,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(StringRes.login_help_note),
                style = Ember.type.meta.copy(fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.ink2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.bg)
                    .then(
                        if (Ember.style.isEink) {
                            Modifier.border(1.5.dp, colors.line, RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                        },
                    )
                    .padding(14.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            EmberPillButton(
                text = stringResource(StringRes.login_help_got_it),
                onClick = onGotIt,
                enabled = true,
                inProgress = false,
            )
        }
    }
}

private fun boldExamples(text: String, bold: List<String>): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        bold.forEach { example ->
            val start = text.indexOf(example)
            if (start >= 0) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, start + example.length)
            }
        }
    }
}
