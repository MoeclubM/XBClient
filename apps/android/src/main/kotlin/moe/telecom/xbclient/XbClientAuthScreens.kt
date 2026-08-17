package moe.telecom.xbclient

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LanguageOnboardingScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    var selected by rememberSaveable { mutableStateOf(if (LanguageOptions.any { it.first == state.appLanguage }) state.appLanguage else "") }
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1000)
        showLanguagePicker = true
    }
    Scaffold { padding ->
        AnimatedContent(
            targetState = showLanguagePicker,
            transitionSpec = { contentTransition() },
            label = "language-onboarding-stage",
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) { showPicker ->
            if (!showPicker) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier.size(112.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.app_name), style = MiuixTheme.textStyles.title1)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 28.dp)
                ) {
                    item {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher),
                            contentDescription = null,
                            modifier = Modifier
                                .padding(horizontal = 28.dp)
                                .size(72.dp)
                        )
                        Spacer(Modifier.height(22.dp))
                        Text(
                            OnboardingLanguageTitles[selected] ?: OnboardingLanguageTitles.getValue(""),
                            style = MiuixTheme.textStyles.title1,
                            modifier = Modifier.padding(horizontal = 28.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            OnboardingLanguageSubtitles[selected] ?: OnboardingLanguageSubtitles.getValue(""),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 28.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            for (item in LanguageOptions) {
                                RadioButtonPreference(
                                    title = item.second,
                                    selected = selected == item.first,
                                    radioButtonLocation = RadioButtonLocation.End,
                                    onClick = {
                                        selected = item.first
                                        viewModel.setAppLanguage(item.first)
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.finishLanguageOnboarding(selected) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(stringResource(R.string.onboarding_continue))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VpnDisclosureScreen(viewModel: XbClientViewModel) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 28.dp)
        ) {
            item {
                Image(
                    painter = painterResource(R.drawable.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 28.dp)
                        .size(72.dp)
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    stringResource(R.string.vpn_disclosure_title),
                    style = MiuixTheme.textStyles.title1,
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.vpn_disclosure_body),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    insideMargin = PaddingValues(18.dp)
                ) {
                    Text(stringResource(R.string.vpn_disclosure_point_traffic))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.vpn_disclosure_point_data))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.vpn_disclosure_point_control))
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = viewModel::acceptVpnDisclosure,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(stringResource(R.string.vpn_disclosure_accept))
                }
            }
        }
    }
}

@Composable
internal fun AuthScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 20.dp)
        ) {
            item {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    LanguageChooser(state.appLanguage, viewModel)
                    ThemeChooser(state.themeMode, state.appLanguage, viewModel)
                }
                Spacer(Modifier.height(28.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(id = R.string.app_name),
                        style = MiuixTheme.textStyles.title2,
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(26.dp))
                    AnimatedContent(
                        targetState = state.authMode,
                        transitionSpec = { contentTransition() },
                        label = "auth-mode"
                    ) { authMode ->
                        if (authMode == AuthMode.LOGIN) {
                            LoginContent(state, viewModel)
                        } else {
                            RegisterContent(state, viewModel)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginContent(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
            .animateContentSize(animationSpec = tween(180))
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(18.dp)
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
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.login(email, password) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.auth_login))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = viewModel::showRegister, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.auth_register_account))
            }
        }
        if (state.oauthProviders.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                insideMargin = PaddingValues(18.dp)
            ) {
                for (provider in state.oauthProviders) {
                    Button(
                        onClick = { viewModel.openOAuthPage("login", provider.driver) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.auth_oauth_login_button, provider.label))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (hasAuthFooterLinks()) {
            Spacer(Modifier.height(14.dp))
            AuthFooterLinks(context)
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(18.dp)
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
            Spacer(Modifier.height(10.dp))
            TextField(
                value = inviteCode,
                onValueChange = { inviteCode = it },
                label = stringResource(R.string.auth_invite_code_optional),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.registerEmailVerifyEnabled) {
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = emailCode,
                    onValueChange = { emailCode = it },
                    label = stringResource(R.string.auth_email_code_optional),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
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
                Spacer(Modifier.height(14.dp))
                Button(onClick = { viewModel.sendEmailVerify(email, captcha) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_send_email_code))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (legalRequired) {
                RegisterLegalAgreement(legalAccepted, { legalAccepted = it }, context)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = { viewModel.register(email, password, inviteCode, emailCode, captcha) },
                enabled = registerEnabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.auth_register))
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.auth_back_login),
                onClick = viewModel::showLogin,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.oauthConfirmToken.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.auth_oauth_confirm_message, state.oauthConfirmProvider, state.oauthConfirmEmail),
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
                Spacer(Modifier.height(14.dp))
                for (provider in state.oauthProviders) {
                    Button(
                        onClick = { viewModel.openOAuthPage("register", provider.driver, inviteCode) },
                        enabled = registerEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.auth_oauth_register_button, provider.label))
                    }
                    Spacer(Modifier.height(8.dp))
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

@Composable
private fun AuthFooterLinks(context: Context) {
    val links = listOf(
        R.string.about_website to BuildConfig.WEBSITE_URL.trim(),
        R.string.about_user_agreement to BuildConfig.USER_AGREEMENT_URL.trim(),
        R.string.about_privacy_policy to BuildConfig.PRIVACY_POLICY_URL.trim()
    ).filter { it.second.isNotEmpty() }
    if (links.isEmpty()) {
        return
    }
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        for ((index, link) in links.withIndex()) {
            LinkText(stringResource(link.first)) { openBrowser(context, link.second) }
            if (index != links.lastIndex) {
                Text(" · ", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}
