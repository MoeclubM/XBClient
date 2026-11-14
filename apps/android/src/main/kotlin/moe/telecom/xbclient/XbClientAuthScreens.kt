package moe.telecom.xbclient

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
private fun AuthTopActions(
    appLanguage: String,
    themeMode: String,
    viewModel: XbClientViewModel,
) {
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    val normalizedTheme = themeMode.ifBlank { "system" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { showLanguageDialog = true },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_language),
                contentDescription = stringResource(R.string.setting_language),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(19.dp),
            )
        }
        IconButton(
            onClick = { showThemeDialog = true },
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_theme),
                contentDescription = stringResource(R.string.page_theme),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(19.dp),
            )
        }
    }

    OverlayDialog(
        show = showLanguageDialog,
        title = stringResource(R.string.setting_language),
        onDismissRequest = { showLanguageDialog = false },
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            for ((code, label) in LanguageOptions) {
                val displayLabel = if (code.isEmpty()) stringResource(R.string.language_system) else label
                RadioButtonPreference(
                    title = displayLabel,
                    selected = appLanguage == code,
                    radioButtonLocation = RadioButtonLocation.End,
                    onClick = {
                        viewModel.setAppLanguage(code)
                        showLanguageDialog = false
                    },
                )
            }
        }
    }

    OverlayDialog(
        show = showThemeDialog,
        title = stringResource(R.string.page_theme),
        onDismissRequest = { showThemeDialog = false },
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            for ((mode, labelRes) in ThemeOptions) {
                RadioButtonPreference(
                    title = stringResource(labelRes),
                    selected = normalizedTheme == mode,
                    radioButtonLocation = RadioButtonLocation.End,
                    onClick = {
                        viewModel.setThemeMode(mode)
                        showThemeDialog = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun VpnDisclosureScreen(
    state: XbClientUiState,
    themeMode: String,
    viewModel: XbClientViewModel,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minPageHeight = maxHeight
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minPageHeight)
                        .padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AuthTopActions(state.appLanguage, themeMode, viewModel)
                    Column(
                        modifier = Modifier
                            .widthIn(max = 420.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(13.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.vpn_disclosure_title),
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.vpn_disclosure_body),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(14.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(stringResource(R.string.vpn_disclosure_point_traffic), style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.vpn_disclosure_point_data), style = MiuixTheme.textStyles.body2)
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.vpn_disclosure_point_control), style = MiuixTheme.textStyles.body2)
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = viewModel::acceptVpnDisclosure,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(stringResource(R.string.vpn_disclosure_accept))
                        }
                    }
                    if (hasAuthFooterLinks()) {
                        AuthFooterLinks(context)
                    } else {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun AuthScreen(state: XbClientUiState, themeMode: String, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val minPageHeight = maxHeight
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = minPageHeight)
                        .padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AuthTopActions(state.appLanguage, themeMode, viewModel)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .widthIn(max = 400.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(14.dp)),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(id = R.string.app_name),
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(16.dp))
                        if (PxBoardApi.enabled) {
                            PxBoardLogin(state, viewModel)
                        } else {
                            AuthSegmentBar(
                                items = listOf(
                                    "login" to stringResource(R.string.auth_login),
                                    "register" to stringResource(R.string.auth_register),
                                ),
                                selectedKey = if (state.authMode == AuthMode.REGISTER) "register" else "login",
                                onSelect = { key ->
                                    if (key == "register") viewModel.showRegister() else viewModel.showLogin()
                                },
                            )
                            Spacer(Modifier.height(12.dp))
                            AnimatedContent(
                                targetState = state.authMode,
                                transitionSpec = { contentTransition() },
                                label = "auth-mode",
                            ) { authMode ->
                                if (authMode == AuthMode.LOGIN) {
                                    LoginContent(state, viewModel)
                                } else {
                                    RegisterContent(state, viewModel)
                                }
                            }
                        }
                    }
                    if (hasAuthFooterLinks()) {
                        AuthFooterLinks(context)
                    } else {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginContent(state: XbClientUiState, viewModel: XbClientViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp)
        ) {
            TextField(
                value = email,
                onValueChange = { email = it },
                label = stringResource(R.string.auth_email),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier
                    .contentType(ContentType.Username + ContentType.EmailAddress)
                    .fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.auth_password),
                useLabelAsPlaceholder = true,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .contentType(ContentType.Password)
                    .fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { viewModel.login(email, password) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.auth_login))
            }
            if (state.oauthProviders.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (provider in state.oauthProviders) {
                        Button(
                            onClick = { viewModel.openOAuthPage("login", provider.driver) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.auth_oauth_login_button, provider.label))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterContent(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var emailCode by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    var legalAccepted by rememberSaveable { mutableStateOf(false) }
    val legalRequired = BuildConfig.USER_AGREEMENT_URL.trim().isNotEmpty() && BuildConfig.PRIVACY_POLICY_URL.trim().isNotEmpty()
    val registerEnabled = !legalRequired || legalAccepted
    Column(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(180))) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(16.dp)
        ) {
            TextField(
                value = email,
                onValueChange = { email = it },
                label = stringResource(R.string.auth_email),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier
                    .contentType(ContentType.NewUsername + ContentType.EmailAddress)
                    .fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.auth_password),
                useLabelAsPlaceholder = true,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .contentType(ContentType.NewPassword)
                    .fillMaxWidth()
            )
            if (state.registerCaptchaEnabled) {
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = captcha,
                    onValueChange = { captcha = it },
                    label = stringResource(R.string.auth_captcha_optional),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (state.registerEmailVerifyEnabled) {
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextField(
                        value = emailCode,
                        onValueChange = { emailCode = it },
                        label = stringResource(R.string.auth_email_code_optional),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { viewModel.sendEmailVerify(email, captcha) },
                        enabled = email.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.auth_send_email_code), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = stringResource(R.string.auth_invite_code_optional),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            if (legalRequired) {
                RegisterLegalAgreement(legalAccepted, { legalAccepted = it }, context)
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = { viewModel.register(email, password, inviteCode, emailCode, captcha) },
                enabled = registerEnabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.auth_register))
            }
            if (state.oauthConfirmToken.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    stringResource(R.string.auth_oauth_confirm_message, state.oauthConfirmProvider, state.oauthConfirmEmail),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::confirmOAuthRegister,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(stringResource(R.string.auth_oauth_confirm_button))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = stringResource(R.string.auth_oauth_cancel),
                    onClick = viewModel::clearOAuthConfirm,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (state.oauthProviders.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (provider in state.oauthProviders) {
                        Button(
                            onClick = { viewModel.openOAuthPage("register", provider.driver, inviteCode) },
                            enabled = registerEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.auth_oauth_register_button, provider.label))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterLegalAgreement(checked: Boolean, onCheckedChange: (Boolean) -> Unit, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { onCheckedChange(!checked) }
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(stringResource(R.string.auth_terms_agree), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkText(stringResource(R.string.about_user_agreement)) { openBrowser(context, BuildConfig.USER_AGREEMENT_URL) }
                LinkText(stringResource(R.string.about_privacy_policy)) { openBrowser(context, BuildConfig.PRIVACY_POLICY_URL) }
            }
        }
    }
}
