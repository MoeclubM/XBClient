package moe.telecom.xbclient

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Message
import android.text.TextUtils
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun XbClientApp(viewModel: XbClientViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val languageTag = effectiveLanguageTag(state.appLanguage)
    val appLocale = remember(languageTag) { Locale.forLanguageTag(languageTag) }
    val localizedContext = remember(baseContext, appLocale) { localizedContext(baseContext, appLocale) }
    val localizedConfiguration = remember(localizedContext) { localizedContext.resources.configuration }
    val layoutDirection = remember(appLocale) {
        if (TextUtils.getLayoutDirectionFromLocale(appLocale) == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    }
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = state.loaded && state.canHandleBack) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            backProgress = 0f
            viewModel.navigateBack()
        } catch (error: CancellationException) {
            backProgress = 0f
            throw error
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
        LocalLayoutDirection provides layoutDirection
    ) {
        XbClientTheme(state.themeMode) {
            XbClientDialogs(state, viewModel)
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - backProgress * 0.08f
                    scaleX = 1f - backProgress * 0.025f
                    scaleY = 1f - backProgress * 0.025f
                }
            ) {
                if (!state.loaded) {
                    LoadingScreen()
                } else if (!state.languageOnboardingDone) {
                    LanguageOnboardingScreen(state, viewModel)
                } else if (!state.isLoggedIn) {
                    AuthScreen(state, viewModel)
                } else {
                    MainShell(state, viewModel)
                }
            }
            if (state.oauthWebViewUrl.isNotEmpty()) {
                OAuthWebView(state.oauthWebViewUrl, viewModel)
            }
        }
    }
}

@Composable
fun XbClientSettingsApp(viewModel: XbClientViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val languageTag = effectiveLanguageTag(state.appLanguage)
    val appLocale = remember(languageTag) { Locale.forLanguageTag(languageTag) }
    val localizedContext = remember(baseContext, appLocale) { localizedContext(baseContext, appLocale) }
    val localizedConfiguration = remember(localizedContext) { localizedContext.resources.configuration }
    val layoutDirection = remember(appLocale) {
        if (TextUtils.getLayoutDirectionFromLocale(appLocale) == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    }
    LaunchedEffect(state.loaded) {
        if (state.loaded && state.screen != PassScreen.APP_RULES) {
            viewModel.openScreen(PassScreen.SETTINGS)
        }
    }
    PredictiveBackHandler(enabled = state.loaded && state.screen == PassScreen.APP_RULES) { progress ->
        progress.collect { }
        viewModel.openScreen(PassScreen.SETTINGS)
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfiguration,
        LocalLayoutDirection provides layoutDirection
    ) {
        XbClientTheme(state.themeMode) {
            XbClientDialogs(state, viewModel)
            Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                if (!state.loaded) {
                    Box(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                    ) {
                        LoadingScreen()
                    }
                } else {
                    AnimatedContent(
                        targetState = if (state.screen == PassScreen.APP_RULES) PassScreen.APP_RULES else PassScreen.SETTINGS,
                        transitionSpec = { screenTransition() },
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                        label = "settings-screen"
                    ) { screen ->
                        if (screen == PassScreen.APP_RULES) {
                            AppRulesScreen(state, viewModel)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                item {
                                    SettingsScreen(state, viewModel, onClose)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XbClientTheme(themeMode: String, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(), content = content)
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OAuthWebView(url: String, viewModel: XbClientViewModel) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.oauth_web_title)) },
                actions = {
                    TextButton(onClick = viewModel::closeOAuthWebView) {
                        Text(stringResource(R.string.common_close))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        AndroidView(
            factory = { context ->
                val webUserAgent = WebSettings.getDefaultUserAgent(context).let { defaultUserAgent ->
                    if (defaultUserAgent.contains(BuildConfig.USER_AGENT)) defaultUserAgent else "$defaultUserAgent ${BuildConfig.USER_AGENT}"
                }
                WebView(context).apply {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    }
                    settings.userAgentString = webUserAgent
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            handleOAuthWebUrl(request.url, viewModel)

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                            handleOAuthWebUrl(Uri.parse(url), viewModel)
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                            val parent = view
                            val popup = WebView(view.context).apply {
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.userAgentString = webUserAgent
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        if (handleOAuthWebUrl(request.url, viewModel)) {
                                            return true
                                        }
                                        parent.loadUrl(request.url.toString())
                                        return true
                                    }

                                    @Deprecated("Deprecated in Java")
                                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                        val uri = Uri.parse(url)
                                        if (handleOAuthWebUrl(uri, viewModel)) {
                                            return true
                                        }
                                        parent.loadUrl(url)
                                        return true
                                    }
                                }
                            }
                            val transport = resultMsg.obj as WebView.WebViewTransport
                            transport.webView = popup
                            resultMsg.sendToTarget()
                            return true
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { webView ->
                if (webView.url != url) {
                    webView.loadUrl(url)
                }
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        )
    }
}

private fun handleOAuthWebUrl(uri: Uri, viewModel: XbClientViewModel): Boolean {
    if (uri.scheme == BuildConfig.OAUTH_CALLBACK_SCHEME && uri.host == "oauth") {
        viewModel.handleOAuthCallback(uri)
        return true
    }
    return false
}

@Composable
private fun LanguageOnboardingScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val languages = listOf(
        "" to R.string.language_system,
        "zh-CN" to R.string.language_zh,
        "en" to R.string.language_en,
        "ja" to R.string.language_ja,
        "ru" to R.string.language_ru,
        "fa" to R.string.language_fa
    )
    var selected by rememberSaveable { mutableStateOf(if (languages.any { it.first == state.appLanguage }) state.appLanguage else "") }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("XB", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text(stringResource(R.string.onboarding_language_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.onboarding_language_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        for ((index, item) in languages.withIndex()) {
                            ListItem(
                                headlineContent = { Text(stringResource(item.second)) },
                                trailingContent = {
                                    if (selected == item.first) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                                    }
                                },
                                modifier = Modifier.clickable { selected = item.first }
                            )
                            if (index != languages.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = { viewModel.finishLanguageOnboarding(selected) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = RoundedCornerShape(30.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.size(78.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("XB", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(id = R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(24.dp))
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
        PageHeader(stringResource(R.string.auth_login_title))
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.auth_email)) },
                    singleLine = true,
                    modifier = Modifier
                        .contentType(ContentType.Username + ContentType.EmailAddress)
                        .fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.auth_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .contentType(ContentType.Password)
                        .fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.login(email, password) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_login))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = viewModel::showRegister, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_register_account))
                }
            }
        }
        if (state.oauthProviders.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.auth_oauth_login_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    for (provider in state.oauthProviders) {
                        OutlinedButton(
                            onClick = { viewModel.openOAuthPage("login", provider.driver) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.auth_oauth_login_button, provider.label))
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                LanguageChooser(state.appLanguage, viewModel)
                Spacer(Modifier.height(12.dp))
                AuthFooterLinks(context)
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
        PageHeader(stringResource(R.string.auth_register_title), stringResource(R.string.auth_register_subtitle))
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.auth_email)) },
                    singleLine = true,
                    modifier = Modifier
                        .contentType(ContentType.NewUsername + ContentType.EmailAddress)
                        .fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.auth_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .contentType(ContentType.NewPassword)
                        .fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = inviteCode, onValueChange = { inviteCode = it }, label = { Text(stringResource(R.string.auth_invite_code_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = emailCode, onValueChange = { emailCode = it }, label = { Text(stringResource(R.string.auth_email_code_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = captcha, onValueChange = { captcha = it }, label = { Text(stringResource(R.string.auth_captcha_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { viewModel.sendEmailVerify(email, captcha) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_send_email_code))
                }
                Spacer(Modifier.height(8.dp))
                if (legalRequired) {
                    RegisterLegalAgreement(legalAccepted, { legalAccepted = it }, context)
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = { viewModel.register(email, password, inviteCode, emailCode, captcha) },
                    enabled = registerEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.auth_register))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::showLogin, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auth_back_login))
                }
                if (state.oauthConfirmToken.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.auth_oauth_confirm_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.auth_oauth_confirm_message, state.oauthConfirmProvider.ifEmpty { "OAuth" }, state.oauthConfirmEmail),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::confirmOAuthRegister, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auth_oauth_confirm_button))
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = viewModel::clearOAuthConfirm, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.auth_oauth_cancel))
                    }
                }
                if (state.oauthProviders.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.auth_oauth_register_title), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    for (provider in state.oauthProviders) {
                        OutlinedButton(
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
}

@Composable
private fun RegisterLegalAgreement(checked: Boolean, onCheckedChange: (Boolean) -> Unit, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.auth_terms_agree), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageChooser(current: String, viewModel: XbClientViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val options = listOf(
        "" to R.string.language_system,
        "zh-CN" to R.string.language_zh,
        "en" to R.string.language_en,
        "ja" to R.string.language_ja,
        "ru" to R.string.language_ru,
        "fa" to R.string.language_fa
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource((options.firstOrNull { it.first == current } ?: options.first()).second),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.setting_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((tag, label) in options) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        viewModel.setAppLanguage(tag)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChooser(current: String, viewModel: XbClientViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val options = listOf(
        "" to R.string.theme_system,
        "light" to R.string.theme_light,
        "dark" to R.string.theme_dark
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = stringResource((options.firstOrNull { it.first == current } ?: options.first()).second),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.setting_theme)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for ((mode, label) in options) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        viewModel.setThemeMode(mode)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(state: XbClientUiState, viewModel: XbClientViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = { BottomNavigation(state, viewModel) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refreshCurrentPage,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            AnimatedContent(
                targetState = state.screen,
                transitionSpec = { screenTransition() },
                label = "main-screen"
            ) { screen ->
                when (screen) {
                    PassScreen.NODE_SELECT -> NodeSelectScreen(state, viewModel)
                    PassScreen.APP_RULES -> AppRulesScreen(state, viewModel)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        item {
                            when (screen) {
                                PassScreen.PROFILE -> ProfileScreen(state, viewModel)
                                PassScreen.PLANS -> PlansScreen(state, viewModel)
                                PassScreen.SETTINGS -> ProfileScreen(state, viewModel)
                                else -> NodesScreen(state, viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(state: XbClientUiState, viewModel: XbClientViewModel) {
    val selected = when (state.screen) {
        PassScreen.PROFILE, PassScreen.SETTINGS, PassScreen.APP_RULES -> PassScreen.PROFILE
        PassScreen.PLANS -> PassScreen.PLANS
        else -> PassScreen.NODES
    }
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp
    ) {
        NavigationBarItem(
            selected = selected == PassScreen.NODES,
            onClick = { viewModel.openScreen(PassScreen.NODES) },
            icon = { Icon(painterResource(R.drawable.ic_nav_nodes), contentDescription = null, modifier = Modifier.size(22.dp)) },
            label = { Text(stringResource(R.string.nav_nodes), style = MaterialTheme.typography.labelMedium) }
        )
        NavigationBarItem(
            selected = selected == PassScreen.PLANS,
            onClick = { viewModel.openScreen(PassScreen.PLANS) },
            icon = { Icon(painterResource(R.drawable.ic_nav_plans), contentDescription = null, modifier = Modifier.size(22.dp)) },
            label = { Text(stringResource(R.string.nav_plans), style = MaterialTheme.typography.labelMedium) }
        )
        NavigationBarItem(
            selected = selected == PassScreen.PROFILE,
            onClick = { viewModel.openScreen(PassScreen.PROFILE) },
            icon = { Icon(painterResource(R.drawable.ic_nav_profile), contentDescription = null, modifier = Modifier.size(22.dp)) },
            label = { Text(stringResource(R.string.nav_profile), style = MaterialTheme.typography.labelMedium) }
        )
    }
}

@Composable
private fun NodesScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    PageHeader(stringResource(R.string.nav_nodes))
    if (state.subscriptionBlocked) {
        val blockTitle = stringResource(
            id = if (state.subscriptionBlockReason == SUBSCRIPTION_BLOCK_TRAFFIC) R.string.subscription_traffic_exceeded_title else R.string.subscription_expired_title
        )
        val blockDescription = stringResource(
            id = if (state.subscriptionBlockReason == SUBSCRIPTION_BLOCK_TRAFFIC) R.string.subscription_traffic_exceeded_body else R.string.subscription_expired_body
        )
        Section(blockTitle) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = tween(180))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(blockDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.subscriptionSummary.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.subscriptionSummary, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { viewModel.openScreen(PassScreen.PLANS) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.subscription_redeem_button))
                    }
                }
            }
        }
        return
    }
    val context = LocalContext.current
    val selectedNode = state.anyTlsNodes.getOrNull(state.selectedNodeIndex)
    Section(stringResource(R.string.section_connection)) {
        val connectionStateText = stringResource(id = if (state.vpnRequested) R.string.status_connected else R.string.status_disconnected)
        AnimatedContent(targetState = connectionStateText, transitionSpec = { contentTransition() }, label = "connection-state") { text ->
            Text(text, style = MaterialTheme.typography.displaySmall)
        }
        Spacer(Modifier.height(14.dp))
        val connectionActionText = stringResource(
            id = when {
                state.vpnStarting -> R.string.status_connecting
                state.vpnRequested -> R.string.action_disconnect
                else -> R.string.action_connect
            }
        )
        Button(
            onClick = { if (state.vpnRequested) viewModel.stopVpn(context) else viewModel.requestStartVpn() },
            enabled = !state.vpnStarting,
            modifier = Modifier.fillMaxWidth()
        ) {
            AnimatedContent(targetState = connectionActionText, transitionSpec = { contentTransition() }, label = "connection-action") { text ->
                Text(text)
            }
        }
    }
    Section(stringResource(R.string.section_current_node)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 78.dp)
                .animateContentSize(animationSpec = tween(180))
        ) {
            val nodeTitle = selectedNode?.displayName(state.selectedNodeIndex, stringResource(R.string.node_default_name, state.selectedNodeIndex + 1))
                ?: stringResource(id = if (state.nodesLoading) R.string.status_nodes_syncing else R.string.status_no_nodes)
            AnimatedContent(targetState = nodeTitle, transitionSpec = { contentTransition() }, label = "current-node") { title ->
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.height(6.dp))
            val testText = state.nodeTestResults[state.selectedNodeIndex] ?: stringResource(R.string.status_not_tested)
            AnimatedContent(targetState = testText, transitionSpec = { contentTransition() }, label = "current-node-test") { text ->
                Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.testNode(state.selectedNodeIndex) },
                enabled = selectedNode != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_test_current_node))
            }
            FilledTonalButton(
                onClick = viewModel::testAllNodes,
                enabled = !state.nodesTesting && state.anyTlsNodes.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                AnimatedContent(
                    targetState = stringResource(id = if (state.nodesTesting) R.string.action_test_testing else R.string.action_test_all_nodes),
                    transitionSpec = { contentTransition() },
                    label = "test-all-main"
                ) { text ->
                    Text(text)
                }
            }
        }
    }
    Section(stringResource(R.string.section_available_nodes)) {
        if (state.anyTlsNodes.isEmpty()) {
            Text(stringResource(id = if (state.nodesLoading) R.string.status_nodes_syncing else R.string.status_no_nodes_sentence), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            for ((index, node) in state.anyTlsNodes.withIndex()) {
                NodeRow(
                    index = index,
                    node = node,
                    selected = index == state.selectedNodeIndex,
                    testText = state.nodeTestResults[index],
                    onTest = { viewModel.testNode(index) },
                    onSelect = { viewModel.selectNode(index, returnToNodes = true) }
                )
                if (index != state.anyTlsNodes.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun PlansScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    PageHeader(stringResource(R.string.nav_plans))
    RewardAdSection(
        title = stringResource(R.string.reward_plan_title),
        enabled = state.planRewardAdEnabled,
        scene = REWARD_SCENE_PLAN,
        state = state,
        viewModel = viewModel
    )
    if (state.plansLoading) {
        Text(stringResource(R.string.plans_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (state.plans.isEmpty()) {
        Text(stringResource(R.string.plans_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val noPriceText = stringResource(R.string.plan_price_unset)
        for ((index, plan) in state.plans.withIndex()) {
            PlanRow(
                plan = plan,
                currencySymbol = state.currencySymbol,
                currencyUnit = state.currencyUnit,
                noPriceText = noPriceText,
                paymentEnabled = state.paymentEnabled,
                onOpenPayment = { viewModel.openPlanPage(context, plan.id) },
                onBalancePurchase = { price -> viewModel.buyPlanWithBalance(plan.id, price.field, price.amount) }
            )
            if (index != state.plans.lastIndex) {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun PlanRow(
    plan: PlanItem,
    currencySymbol: String,
    currencyUnit: String,
    noPriceText: String,
    paymentEnabled: Boolean,
    onOpenPayment: () -> Unit,
    onBalancePurchase: (PlanPrice) -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .clickable(enabled = paymentEnabled, onClick = onOpenPayment)
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, style = MaterialTheme.typography.titleLarge)
                    if (plan.transferEnable > 0.0) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.plan_traffic, formatTrafficGb(plan.transferEnable)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (paymentEnabled || plan.prices.isEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            planPriceText(plan, currencySymbol, currencyUnit, noPriceText),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                }
            }
            val content = plan.content.trim()
            if (content.isNotEmpty() && !content.startsWith("[") && !content.startsWith("{")) {
                Spacer(Modifier.height(12.dp))
                Text(content, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!paymentEnabled && plan.prices.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                for ((index, price) in plan.prices.withIndex()) {
                    FilledTonalButton(onClick = { onBalancePurchase(price) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${planPriceLabel(price.field)} ${formatMoney(price.amount, currencySymbol, currencyUnit)}")
                    }
                    if (index != plan.prices.lastIndex) {
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RewardAdSection(
    title: String,
    enabled: Boolean,
    scene: String,
    state: XbClientUiState,
    viewModel: XbClientViewModel
) {
    if (!enabled) {
        return
    }
    val logs = state.adRewardLogs.filter { it.scene == scene }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
            .animateContentSize(animationSpec = tween(180))
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.size(50.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("AD", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = { viewModel.requestRewardAd(scene) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reward_watch))
            }
            if (logs.isNotEmpty()) {
                val visibleLogs = logs.take(3)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.reward_recent), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                for ((index, log) in visibleLogs.withIndex()) {
                    val statusColor = when (log.status) {
                        "credited" -> MaterialTheme.colorScheme.primary
                        "failed" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.tertiary
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(log.giftCardCode.ifEmpty { stringResource(R.string.reward_code_generating) }, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.reward_template_time, log.giftCardTemplateId, formatUnixTime(log.createdAt)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (log.status == "failed" && log.error.isNotEmpty()) {
                                Text(log.error, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Surface(
                            color = statusColor.copy(alpha = 0.12f),
                            contentColor = statusColor,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                rewardStatusText(log.status),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    if (index != visibleLogs.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    PageHeader(stringResource(R.string.nav_profile))
    Section(stringResource(R.string.section_account)) {
        Text(state.userEmail.ifEmpty { stringResource(R.string.status_logged_in) }, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.balance_amount, formatMoney(state.balance, state.currencySymbol, state.currencyUnit)),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.commission_amount, formatMoney(state.commissionBalance, state.currencySymbol, state.currencyUnit)),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        val subscriptionText = state.subscriptionSummary.ifEmpty {
            stringResource(id = if (state.subscribeUrl.isEmpty()) R.string.subscription_not_synced else R.string.subscription_synced)
        }
        Text(subscriptionText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Button(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_settings))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_logout))
        }
    }
    RewardAdSection(
        title = stringResource(R.string.reward_points_title),
        enabled = state.pointsRewardAdEnabled,
        scene = REWARD_SCENE_POINTS,
        state = state,
        viewModel = viewModel
    )
    if (state.inviteForce || state.inviteCommissionRate > 0) {
        Section(stringResource(R.string.section_invite)) {
            Text(stringResource(R.string.invite_aff_rate, state.inviteCommissionRate), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.invite_commission_account, formatMoney(state.inviteCommissionBalance, state.currencySymbol, state.currencyUnit)),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (state.invites.isEmpty()) {
                Text(stringResource(id = if (state.invitesLoading) R.string.invite_loading else R.string.invite_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val copiedText = stringResource(R.string.invite_code_copied)
                for ((index, invite) in state.invites.withIndex()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("invite", invite.code))
                                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(invite.code, style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(id = if (invite.status == 0) R.string.invite_available else R.string.invite_used), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("invite", invite.code))
                                Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(stringResource(R.string.invite_copy))
                        }
                    }
                    if (index != state.invites.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = viewModel::generateInvite, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_generate_invite))
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: XbClientUiState, viewModel: XbClientViewModel, onClose: () -> Unit = { viewModel.openScreen(PassScreen.PROFILE) }) {
    val context = LocalContext.current
    var nodeDns by rememberSaveable(state.nodeDns) { mutableStateOf(state.nodeDns) }
    var overseasDns by rememberSaveable(state.overseasDns) { mutableStateOf(state.overseasDns) }
    var directDns by rememberSaveable(state.directDns) { mutableStateOf(state.directDns) }
    var nodeTestTarget by rememberSaveable(state.nodeTestTarget) { mutableStateOf(state.nodeTestTarget) }
    PageHeader(stringResource(R.string.common_settings), stringResource(R.string.page_settings_subtitle))
    Section(stringResource(R.string.section_appearance)) {
        LanguageChooser(state.appLanguage, viewModel)
        Spacer(Modifier.height(14.dp))
        ThemeChooser(state.themeMode, viewModel)
    }
    Section(stringResource(R.string.section_app_rules)) {
        val selectedCount = selectedPackages(state).size
        Text(
            stringResource(id = if (state.appRuleMode == MODE_ALLOW) R.string.app_rules_allow_desc else R.string.app_rules_exclude_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(if (selectedCount == 0) stringResource(R.string.app_rules_none_selected) else stringResource(R.string.app_rules_selected_count, selectedCount))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.openScreen(PassScreen.APP_RULES) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_select_apps))
            }
            OutlinedButton(onClick = viewModel::clearSelectedApps, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.common_clear_selection))
            }
        }
    }
    Section("DNS") {
        OutlinedTextField(value = nodeDns, onValueChange = { nodeDns = it }, label = { Text(stringResource(R.string.dns_node_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = overseasDns, onValueChange = { overseasDns = it }, label = { Text(stringResource(R.string.dns_overseas_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = directDns, onValueChange = { directDns = it }, label = { Text(stringResource(R.string.dns_direct_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.enable_ipv6))
            Switch(checked = state.vpnIpv6Enabled, onCheckedChange = viewModel::setIpv6Enabled)
        }
    }
    Section(stringResource(R.string.section_node_test)) {
        OutlinedTextField(value = nodeTestTarget, onValueChange = { nodeTestTarget = it }, label = { Text(stringResource(R.string.node_test_target_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.node_test_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { viewModel.saveDnsAndTestSettings(nodeDns, overseasDns, directDns, nodeTestTarget) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.common_save_settings))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_back_profile))
        }
    }
    Section(stringResource(R.string.section_about)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME.removeSuffix(".debug")),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val links = listOf(
            R.string.about_website to BuildConfig.WEBSITE_URL.trim(),
            R.string.about_user_agreement to BuildConfig.USER_AGREEMENT_URL.trim(),
            R.string.about_privacy_policy to BuildConfig.PRIVACY_POLICY_URL.trim()
        ).filter { it.second.isNotEmpty() }
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                for ((label, url) in links) {
                    LinkText(stringResource(label)) { openBrowser(context, url) }
                }
            }
        }
    }
}

@Composable
private fun NodeSelectScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        item {
            PageHeader(stringResource(R.string.page_node_select_title), stringResource(R.string.page_node_select_subtitle))
            if (state.subscriptionBlocked) {
                val blockTitle = stringResource(
                    id = if (state.subscriptionBlockReason == SUBSCRIPTION_BLOCK_TRAFFIC) R.string.subscription_traffic_exceeded_title else R.string.subscription_expired_title
                )
                val blockDescription = stringResource(
                    id = if (state.subscriptionBlockReason == SUBSCRIPTION_BLOCK_TRAFFIC) R.string.subscription_traffic_exceeded_body else R.string.subscription_expired_body
                )
                Section(blockTitle) {
                    Text(blockDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { viewModel.openScreen(PassScreen.PLANS) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.subscription_redeem_button))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { viewModel.openScreen(PassScreen.NODES) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_back_nodes))
                    }
                    Button(
                        onClick = viewModel::testAllNodes,
                        enabled = !state.nodesTesting && state.anyTlsNodes.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        AnimatedContent(targetState = stringResource(id = if (state.nodesTesting) R.string.action_test_testing else R.string.action_test_all_nodes), transitionSpec = { contentTransition() }, label = "test-all") { text ->
                            Text(text)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (!state.subscriptionBlocked) {
            if (state.anyTlsNodes.isEmpty()) {
                item {
                    Section(stringResource(R.string.section_available_nodes)) {
                        Text(stringResource(id = if (state.nodesLoading) R.string.status_nodes_syncing else R.string.status_no_nodes_sentence), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                itemsIndexed(state.anyTlsNodes, key = { index, node -> "${node.displayName(index)}-$index" }) { index, node ->
                    NodeRow(
                        index = index,
                        node = node,
                        selected = index == state.selectedNodeIndex,
                        testText = state.nodeTestResults[index],
                        onTest = { viewModel.testNode(index) },
                        onSelect = { viewModel.selectNode(index, returnToNodes = true) }
                    )
                    if (index != state.anyTlsNodes.lastIndex) {
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    index: Int,
    node: AnyTlsNode,
    selected: Boolean,
    testText: String?,
    onTest: () -> Unit,
    onSelect: () -> Unit
) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f) else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .clickable(onClick = onSelect)
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    (if (selected) "✓ " else "") + node.displayName(index, stringResource(R.string.node_default_name, index + 1)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onTest, modifier = Modifier.size(34.dp)) {
                    Text("↻")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(node.protocolLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        testText ?: stringResource(R.string.status_not_tested),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRulesScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val packages = selectedPackages(state)
    val query = state.appSearchQuery.trim().lowercase(Locale.ROOT)
    val apps = remember(state.installedApps, query) {
        if (query.isEmpty()) {
            state.installedApps
        } else {
            state.installedApps.filter {
                it.label.lowercase(Locale.ROOT).contains(query) || it.packageName.lowercase(Locale.ROOT).contains(query)
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        item {
            PageHeader(stringResource(R.string.page_app_rules_title), stringResource(R.string.page_app_rules_subtitle))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.appRuleMode == MODE_EXCLUDE) {
                    Button(onClick = { viewModel.switchAppRuleMode(MODE_EXCLUDE) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.mode_exclude)) }
                    OutlinedButton(onClick = { viewModel.switchAppRuleMode(MODE_ALLOW) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.mode_allow)) }
                } else {
                    OutlinedButton(onClick = { viewModel.switchAppRuleMode(MODE_EXCLUDE) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.mode_exclude)) }
                    Button(onClick = { viewModel.switchAppRuleMode(MODE_ALLOW) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.mode_allow)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.appSearchQuery,
                onValueChange = viewModel::setAppSearchQuery,
                label = { Text(stringResource(R.string.search_app_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.app_rules_selected_count, packages.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { viewModel.openScreen(PassScreen.SETTINGS) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_back_settings))
                }
                TextButton(onClick = viewModel::clearSelectedApps, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.common_clear_selection))
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
        }
        items(apps, key = { it.packageName }) { app ->
            val selected = packages.contains(app.packageName)
            ListItem(
                headlineContent = { Text(app.label) },
                supportingContent = { Text(app.packageName) },
                trailingContent = {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { viewModel.setAppSelected(app.packageName, it) }
                    )
                },
                modifier = Modifier.clickable { viewModel.setAppSelected(app.packageName, !selected) }
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XbClientDialogs(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    if (state.updateAvailable) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdateDialog,
            title = { Text(stringResource(R.string.update_title)) },
            text = {
                Text(stringResource(R.string.update_message, BuildConfig.VERSION_NAME.removeSuffix(".debug"), state.latestReleaseVersion))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.openUpdatePage(context) }) {
                    Text(stringResource(id = if (state.latestDownloadUrl.isEmpty()) R.string.update_open_release else R.string.update_download))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdateDialog) {
                    Text(stringResource(R.string.common_later))
                }
            }
        )
    }
    if (state.nodeSwitchSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissNodeSwitchDialog,
            sheetState = sheetState
        ) {
            Text(
                stringResource(id = if (state.nodeSwitchConnect) R.string.sheet_change_node else R.string.sheet_select_node),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(state.anyTlsNodes, key = { index, node -> "${node.displayName(index)}-$index" }) { index, node ->
                    ListItem(
                        headlineContent = { Text(node.displayName(index, stringResource(R.string.node_default_name, index + 1))) },
                        supportingContent = { Text("${node.protocolLabel} · ${state.nodeTestResults[index] ?: stringResource(R.string.status_not_tested)}") },
                        trailingContent = { if (index == state.selectedNodeIndex) Text(stringResource(R.string.common_selected)) },
                        modifier = Modifier.clickable { viewModel.chooseNodeFromDialog(index) }
                    )
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String = "") {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (subtitle.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180))
            .padding(bottom = 20.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

private fun selectedPackages(state: XbClientUiState): Set<String> =
    (if (state.appRuleMode == MODE_ALLOW) state.allowedApps else state.excludedApps)
        .split(Regex("[,;\\s]+"))
        .filter { it.isNotEmpty() }
        .toSet()

private fun openBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is android.app.Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun effectiveLanguageTag(selected: String): String {
    if (selected.isNotEmpty()) {
        return selected
    }
    return when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "zh" -> "zh-CN"
        "ja" -> "ja"
        "ru" -> "ru"
        "fa", "per" -> "fa"
        "en" -> "en"
        else -> "en"
    }
}

private fun localizedContext(context: Context, locale: Locale): Context {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return context.createConfigurationContext(configuration)
}

private fun formatMoney(amount: Int, symbol: String, unit: String): String =
    (symbol + String.format(Locale.US, "%.2f", amount / 100.0) + if (unit.isBlank()) "" else " $unit").trim()

private fun formatUnixTime(value: Long): String =
    if (value <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value * 1000))

@Composable
private fun planPriceText(plan: PlanItem, symbol: String, unit: String, noPriceText: String): String {
    if (plan.prices.isEmpty()) {
        return noPriceText
    }
    val parts = mutableListOf<String>()
    for (price in plan.prices) {
        parts += "${planPriceLabel(price.field)} ${formatMoney(price.amount, symbol, unit)}"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun planPriceLabel(field: String): String =
    stringResource(
        when (field) {
            "month_price" -> R.string.price_month
            "quarter_price" -> R.string.price_quarter
            "half_year_price" -> R.string.price_half_year
            "year_price" -> R.string.price_year
            "two_year_price" -> R.string.price_two_year
            "three_year_price" -> R.string.price_three_year
            "onetime_price" -> R.string.price_onetime
            "reset_price" -> R.string.price_reset
            else -> R.string.plan_price_unset
        }
    )

@Composable
private fun rewardStatusText(status: String): String =
    when (status) {
        "credited" -> stringResource(R.string.reward_credited)
        "pending" -> stringResource(R.string.reward_pending)
        "failed" -> stringResource(R.string.reward_failed)
        else -> status
    }

private val XbClientUiState.canHandleBack: Boolean
    get() = updateAvailable ||
        oauthWebViewUrl.isNotEmpty() ||
        !isLoggedIn && authMode == AuthMode.REGISTER ||
        isLoggedIn && screen !in setOf(PassScreen.NODES, PassScreen.PLANS, PassScreen.PROFILE)

private fun AnimatedContentTransitionScope<*>.contentTransition() =
    (fadeIn(animationSpec = tween(180)) togetherWith
        fadeOut(animationSpec = tween(140))).using(SizeTransform(clip = false))

private fun AnimatedContentTransitionScope<PassScreen>.screenTransition() =
    if (targetState.ordinal >= initialState.ordinal) {
        (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(220)) + fadeIn(animationSpec = tween(160)) togetherWith
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(220)) + fadeOut(animationSpec = tween(140))).using(SizeTransform(clip = false))
    } else {
        (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(220)) + fadeIn(animationSpec = tween(160)) togetherWith
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(220)) + fadeOut(animationSpec = tween(140))).using(SizeTransform(clip = false))
    }
