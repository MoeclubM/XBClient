package moe.telecom.xbclient

import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.Locale

@Composable
fun XbClientApp(viewModel: XbClientViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val languageTag = effectiveLanguageTag(state.appLanguage)
    val appLocale = remember(languageTag) { Locale.forLanguageTag(languageTag) }
    val localizedContext = remember(baseContext, appLocale) { localizedContext(baseContext, appLocale) }
    val localizedConfiguration = remember(localizedContext) { localizedContext.resources.configuration }
    val layoutDirection = remember(appLocale) { appLayoutDirection(appLocale) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = state.loaded && state.isLoggedIn && state.canHandleBack) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            viewModel.navigateBack()
            backProgress = 0f
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
            val routeBackProgress = if (
                state.isLoggedIn &&
                state.screen !in MainTabScreens &&
                !state.updateAvailable &&
                state.oauthWebViewUrl.isEmpty()
            ) backProgress else 0f
            val modalBackProgress = if (routeBackProgress > 0f) 0f else backProgress
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - modalBackProgress * 0.08f
                    scaleX = 1f - modalBackProgress * 0.025f
                    scaleY = 1f - modalBackProgress * 0.025f
                }
            ) {
                if (state.loaded && state.isLoggedIn && state.languageOnboardingDone && state.vpnDisclosureDone) {
                    MainShell(state, viewModel, routeBackProgress)
                } else {
                    LoadingScreen()
                }
            }
        }
    }
}

@Composable
fun XbClientAuthApp(viewModel: XbClientViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val languageTag = effectiveLanguageTag(state.appLanguage)
    val appLocale = remember(languageTag) { Locale.forLanguageTag(languageTag) }
    val localizedContext = remember(baseContext, appLocale) { localizedContext(baseContext, appLocale) }
    val localizedConfiguration = remember(localizedContext) { localizedContext.resources.configuration }
    val layoutDirection = remember(appLocale) { appLayoutDirection(appLocale) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = state.loaded && state.canHandleBack) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            viewModel.navigateBack()
            backProgress = 0f
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
            Scaffold {
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
                    } else if (!state.vpnDisclosureDone) {
                        VpnDisclosureScreen(viewModel)
                    } else if (!state.isLoggedIn) {
                        AuthScreen(state, viewModel)
                    } else {
                        LoadingScreen()
                    }
                }
                XbClientDialogs(state, viewModel)
                if (state.oauthWebViewUrl.isNotEmpty()) {
                    OAuthWebView(state.oauthWebViewUrl, viewModel)
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun OAuthWebView(url: String, viewModel: XbClientViewModel) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.oauth_web_title),
                actions = {
                    TextButton(
                        text = stringResource(R.string.common_close),
                        onClick = viewModel::closeOAuthWebView
                    )
                }
            )
        }
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
private fun MainShell(state: XbClientUiState, viewModel: XbClientViewModel, backProgress: Float) {
    val visibleScreen = if (state.subscriptionBlocked && state.screen == PassScreen.NODE_SELECT) PassScreen.NODES else state.screen
    val backTargetScreen = when (visibleScreen) {
        PassScreen.NODE_SELECT -> PassScreen.NODES
        PassScreen.TICKET_DETAIL -> PassScreen.TICKETS
        PassScreen.GIFT_CARDS, PassScreen.ACCOUNT_SECURITY, PassScreen.INVITE_DETAILS, PassScreen.TRAFFIC_LOGS, PassScreen.TICKETS -> PassScreen.PROFILE
        PassScreen.APP_RULES, PassScreen.OPEN_SOURCE_LICENSES, PassScreen.THEME -> PassScreen.SETTINGS
        else -> null
    }
    val showBottomBar = visibleScreen in MainTabScreens || (backTargetScreen != null && backTargetScreen in MainTabScreens && backProgress > 0f)
    val floatingBar = state.floatingBottomBar
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(state.enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface
    val floatingBarColor = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer
    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = screenTitle(visibleScreen),
                    navigationIcon = {
                        if (visibleScreen !in MainTabScreens) {
                            IconButton(onClick = viewModel::navigateBack) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = MiuixTheme.colorScheme.onBackground
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::showNotices) {
                            Icon(
                                imageVector = ImageVector.vectorResource(R.drawable.ic_notice),
                                contentDescription = stringResource(R.string.section_announcement),
                                tint = MiuixTheme.colorScheme.onBackground
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        bottomBar = {
            if (showBottomBar && !floatingBar) {
                BlurredBar(backdrop) {
                    MainNavigationBar(state, viewModel, barColor, floating = false)
                }
            }
        }
    ) { padding ->
        XbClientDialogs(state, viewModel)
        val extraBottom = if (showBottomBar && floatingBar) 88.dp else 0.dp
        PullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refreshCurrentPage,
            modifier = Modifier
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .padding(
                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = if (floatingBar) 0.dp else padding.calculateBottomPadding()
                )
                .fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                if (backTargetScreen != null && backProgress > 0f) {
                    MainScreenContent(
                        screen = backTargetScreen,
                        state = state,
                        viewModel = viewModel,
                        scrollBehavior = scrollBehavior,
                        extraBottom = extraBottom,
                        modifier = Modifier.graphicsLayer {
                            alpha = 0.35f + backProgress * 0.65f
                            scaleX = 0.96f + backProgress * 0.04f
                            scaleY = 0.96f + backProgress * 0.04f
                            translationX = -size.width * 0.06f * (1f - backProgress)
                        }
                    )
                }
                AnimatedContent(
                    targetState = visibleScreen,
                    transitionSpec = { screenTransition() },
                    modifier = Modifier.graphicsLayer {
                        if (backTargetScreen != null && backProgress > 0f) {
                            translationX = size.width * (0.08f + backProgress * 0.82f)
                            alpha = 1f - backProgress * 0.16f
                            scaleX = 1f - backProgress * 0.035f
                            scaleY = 1f - backProgress * 0.035f
                        }
                    },
                    label = "main-screen"
                ) { screen ->
                    MainScreenContent(screen, state, viewModel, scrollBehavior, extraBottom)
                }
            }
        }
        if (showBottomBar && floatingBar) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                BlurredBar(backdrop, RoundedCornerShape(28.dp)) {
                    MainNavigationBar(state, viewModel, floatingBarColor, floating = true)
                }
            }
        }
    }
}

@Composable
private fun MainNavigationBar(state: XbClientUiState, viewModel: XbClientViewModel, color: Color, floating: Boolean) {
    val selected = when (state.screen) {
        PassScreen.SETTINGS, PassScreen.APP_RULES, PassScreen.OPEN_SOURCE_LICENSES, PassScreen.THEME -> PassScreen.SETTINGS
        PassScreen.PROFILE, PassScreen.GIFT_CARDS, PassScreen.ACCOUNT_SECURITY, PassScreen.INVITE_DETAILS, PassScreen.TRAFFIC_LOGS, PassScreen.TICKETS, PassScreen.TICKET_DETAIL -> PassScreen.PROFILE
        PassScreen.PLANS -> PassScreen.PLANS
        else -> PassScreen.NODES
    }
    val navScreens = listOf(PassScreen.NODES, PassScreen.PLANS, PassScreen.PROFILE, PassScreen.SETTINGS)
    if (floating) {
        FloatingNavigationBar(
            modifier = Modifier.padding(bottom = 12.dp),
            color = color,
            showDivider = false
        ) {
            for (screen in navScreens) {
                FloatingNavigationBarItem(
                    selected = selected == screen,
                    onClick = { viewModel.openScreen(screen) },
                    icon = navIcon(screen),
                    label = navLabel(screen)
                )
            }
        }
    } else {
        NavigationBar(color = color) {
            for (screen in navScreens) {
                NavigationBarItem(
                    selected = selected == screen,
                    onClick = { viewModel.openScreen(screen) },
                    icon = navIcon(screen),
                    label = navLabel(screen)
                )
            }
        }
    }
}

@Composable
private fun navIcon(screen: PassScreen): ImageVector =
    ImageVector.vectorResource(
        when (screen) {
            PassScreen.PLANS -> R.drawable.ic_nav_plans
            PassScreen.PROFILE -> R.drawable.ic_nav_profile
            PassScreen.SETTINGS -> R.drawable.ic_nav_settings
            else -> R.drawable.ic_nav_home
        }
    )

@Composable
private fun navLabel(screen: PassScreen): String =
    stringResource(
        id = when (screen) {
            PassScreen.PLANS -> R.string.nav_plans
            PassScreen.PROFILE -> R.string.nav_profile
            PassScreen.SETTINGS -> R.string.common_settings
            else -> R.string.nav_home
        }
    )

@Composable
private fun MainScreenContent(
    screen: PassScreen,
    state: XbClientUiState,
    viewModel: XbClientViewModel,
    scrollBehavior: ScrollBehavior,
    extraBottom: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val listModifier = modifier
        .fillMaxSize()
        .scrollEndHaptic()
        .overScrollVertical()
        .nestedScroll(scrollBehavior.nestedScrollConnection)
    when (screen) {
        PassScreen.NODE_SELECT -> NodeSelectScreen(state, viewModel, listModifier)
        PassScreen.APP_RULES -> AppRulesScreen(state, viewModel, listModifier)
        PassScreen.OPEN_SOURCE_LICENSES -> OpenSourceLicensesScreen(listModifier)
        else -> LazyColumn(
            modifier = listModifier,
            contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp + extraBottom),
            overscrollEffect = null
        ) {
            item {
                when (screen) {
                    PassScreen.PROFILE -> ProfileScreen(state, viewModel)
                    PassScreen.GIFT_CARDS -> GiftCardsScreen(state, viewModel)
                    PassScreen.ACCOUNT_SECURITY -> AccountSecurityScreen(state, viewModel)
                    PassScreen.INVITE_DETAILS -> InviteDetailsScreen(state)
                    PassScreen.TRAFFIC_LOGS -> TrafficLogsScreen(state)
                    PassScreen.TICKETS -> TicketsScreen(state, viewModel)
                    PassScreen.TICKET_DETAIL -> TicketDetailScreen(state, viewModel)
                    PassScreen.PLANS -> PlansScreen(state, viewModel)
                    PassScreen.SETTINGS -> SettingsScreen(state, viewModel)
                    PassScreen.THEME -> ThemeSettingsScreen(state, viewModel)
                    else -> HomeScreen(state, viewModel)
                }
            }
        }
    }
}
