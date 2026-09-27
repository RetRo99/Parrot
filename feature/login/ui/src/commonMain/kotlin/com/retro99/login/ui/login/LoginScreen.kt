package com.retro99.login.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.retro99.base.server.ServerType
import com.retro99.base.ui.BaseScreen
import com.retro99.base.ui.IntentDispatcher
import com.retro99.translations.StringRes
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import resources.translations.general_back
import resources.translations.login_error_invalid_url
import resources.translations.login_hide_password
import resources.translations.login_oauth_sign_in_button
import resources.translations.login_oauth_waiting_message
import resources.translations.login_password_label
import resources.translations.login_server_type_label
import resources.translations.login_show_password
import resources.translations.login_sign_in_button
import resources.translations.login_sign_in_hint
import resources.translations.login_subtitle
import resources.translations.login_title
import resources.translations.login_url_info
import resources.translations.login_url_label
import resources.translations.login_url_tooltip_description
import resources.translations.login_url_tooltip_dismiss
import resources.translations.login_url_tooltip_title
import resources.translations.login_username_label

@Composable
fun LoginScreen(
    onSignInSuccess: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = koinViewModel { parametersOf(onSignInSuccess, onBackClick) },
) {
    BaseScreen(
        modifier = modifier.imePadding(),
        viewModel = viewModel,
    ) { viewState, intentDispatcher ->
        LoginScreenContent(
            urlState = viewModel.urlState,
            usernameState = viewModel.usernameState,
            passwordState = viewModel.passwordState,
            isSignInEnabled = viewState.isSignInEnabled,
            isOAuthSignInEnabled = viewState.isOAuthSignInEnabled,
            isOAuthInProgress = viewState.isOAuthInProgress,
            isOAuthVisible = viewState.isOAuthVisible,
            isLoading = viewState.isLoading,
            selectedServerType = viewState.selectedServerType,
            urlError = viewState.urlError,
            loginError = viewState.loginError,
            intentDispatcher = intentDispatcher,
            onBackClick = onBackClick,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginScreenContent(
    urlState: TextFieldState,
    usernameState: TextFieldState,
    passwordState: TextFieldState,
    isSignInEnabled: Boolean,
    isOAuthSignInEnabled: Boolean,
    isOAuthInProgress: Boolean,
    isOAuthVisible: Boolean,
    isLoading: Boolean,
    selectedServerType: ServerType,
    urlError: LoginFieldError?,
    loginError: String?,
    intentDispatcher: IntentDispatcher<LoginIntent>,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    var serverTypeExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val urlErrorText = urlError?.let { error ->
        when (error) {
            LoginFieldError.InvalidUrl -> stringResource(StringRes.login_error_invalid_url)
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(StringRes.general_back),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(StringRes.login_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(StringRes.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            ExposedDropdownMenuBox(
                expanded = serverTypeExpanded,
                onExpandedChange = { expanded ->
                    if (expanded) {
                        intentDispatcher(LoginIntent.OnServerTypePickerOpened)
                    } else {
                        intentDispatcher(
                            LoginIntent.OnServerTypePickerDismissed(
                                reason = ServerTypePickerDismissalReason.AnchorToggle,
                            ),
                        )
                    }
                    serverTypeExpanded = expanded
                },
            ) {
                OutlinedTextField(
                    value = selectedServerType.displayName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(StringRes.login_server_type_label)) },
                    leadingIcon = { Icon(Icons.Filled.Dns, contentDescription = null) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = serverTypeExpanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = serverTypeExpanded,
                    onDismissRequest = {
                        intentDispatcher(
                            LoginIntent.OnServerTypePickerDismissed(
                                reason = ServerTypePickerDismissalReason.DismissRequest,
                            ),
                        )
                        serverTypeExpanded = false
                    },
                ) {
                    ServerType.entries
                        .filter { serverType ->
                            serverType != ServerType.Local && serverType != ServerType.ParrotCloud
                        }
                        .forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.displayName) },
                            onClick = {
                                intentDispatcher(LoginIntent.OnServerTypeSelected(type))
                                serverTypeExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                state = urlState,
                label = { Text(stringResource(StringRes.login_url_label)) },
                leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
                trailingIcon = {
                    UrlInfoTooltip(
                        serverName = selectedServerType.displayName,
                        intentDispatcher = intentDispatcher,
                    )
                },
                isError = urlError != null,
                supportingText = urlErrorText?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                onKeyboardAction = { focusManager.moveFocus(FocusDirection.Down) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                state = usernameState,
                label = { Text(stringResource(StringRes.login_username_label)) },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next,
                ),
                onKeyboardAction = { focusManager.moveFocus(FocusDirection.Down) },
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedSecureTextField(
                state = passwordState,
                label = { Text(stringResource(StringRes.login_password_label)) },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                textObfuscationMode = if (passwordVisible) {
                    TextObfuscationMode.Visible
                } else {
                    TextObfuscationMode.Hidden
                },
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                    keyboardType = KeyboardType.Password,
                ),
                onKeyboardAction = {
                    focusManager.clearFocus()
                    if (isSignInEnabled) {
                        intentDispatcher(LoginIntent.OnSignInClicked)
                    }
                },
                trailingIcon = {
                    PasswordVisibilityToggle(
                        isVisible = passwordVisible,
                        onToggle = { passwordVisible = !passwordVisible },
                    )
                },
            )

            AnimatedVisibility(
                visible = loginError != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = loginError.orEmpty(),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { intentDispatcher(LoginIntent.OnSignInClicked) },
                modifier = Modifier.fillMaxWidth(),
                enabled = isSignInEnabled,
            ) {
                if (isLoading && !isOAuthInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(StringRes.login_sign_in_button))
                }
            }

            if (!isSignInEnabled && !isLoading) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(StringRes.login_sign_in_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (isOAuthVisible) {
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { intentDispatcher(LoginIntent.OnOAuthSignInClicked) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isOAuthSignInEnabled,
                ) {
                    if (isOAuthInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(stringResource(StringRes.login_oauth_sign_in_button))
                    }
                }

                AnimatedVisibility(
                    visible = isOAuthInProgress,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        text = stringResource(StringRes.login_oauth_waiting_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordVisibilityToggle(
    isVisible: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (isVisible) {
                Icons.Filled.VisibilityOff
            } else {
                Icons.Filled.Visibility
            },
            contentDescription = if (isVisible) {
                stringResource(StringRes.login_hide_password)
            } else {
                stringResource(StringRes.login_show_password)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlInfoTooltip(
    serverName: String,
    intentDispatcher: IntentDispatcher<LoginIntent>,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(tooltipState) {
        snapshotFlow { tooltipState.isVisible }
            .distinctUntilChanged()
            .collect { isVisible ->
                if (isVisible) intentDispatcher(LoginIntent.OnUrlHelpOpened)
            }
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Above
        ),
        tooltip = {
            RichTooltip(
                title = { Text(stringResource(StringRes.login_url_tooltip_title)) },
                action = {
                    TextButton(onClick = { scope.launch { tooltipState.dismiss() } }) {
                        Text(stringResource(StringRes.login_url_tooltip_dismiss))
                    }
                },
            ) {
                Text(stringResource(StringRes.login_url_tooltip_description, serverName))
            }
        },
        state = tooltipState,
        onDismissRequest = {
            intentDispatcher(
                LoginIntent.OnUrlHelpDismissed(UrlHelpDismissalReason.DismissRequest),
            )
            tooltipState.dismiss()
        },
    ) {
        IconButton(
            onClick = {
                if (tooltipState.isVisible) {
                    intentDispatcher(
                        LoginIntent.OnUrlHelpDismissed(UrlHelpDismissalReason.AnchorToggle),
                    )
                    tooltipState.dismiss()
                } else {
                    intentDispatcher(LoginIntent.OnUrlHelpOpenRequested)
                    scope.launch { tooltipState.show() }
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = stringResource(StringRes.login_url_info),
            )
        }
    }
}
