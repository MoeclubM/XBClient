package moe.telecom.xbclient

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.os.Message
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val context = LocalContext.current
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = state.loaded) { progress ->
        try {
            progress.collect { event ->
                backProgress = event.progress
            }
            backProgress = 0f
            if (state.canHandleBack) {
                viewModel.navigateBack()
            } else {
                (context as Activity).finish()
            }
        } catch (error: CancellationException) {
            backProgress = 0f
            throw error
        }
    }
    XbClientTheme {
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

@Composable
private fun XbClientTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val baseTypography = Typography()
    MaterialTheme(
        colorScheme = editorialColorScheme(dark),
        typography = baseTypography.copy(
            displaySmall = baseTypography.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
            headlineMedium = baseTypography.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
            titleLarge = baseTypography.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal)
        ),
        content = content
    )
}

private fun editorialColorScheme(dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = Color(0xFFF0E1CF),
            onPrimary = Color(0xFF221911),
            background = Color(0xFF171411),
            onBackground = Color(0xFFF4E8DA),
            surface = Color(0xFF171411),
            onSurface = Color(0xFFF4E8DA),
            surfaceVariant = Color(0xFF27211B),
            onSurfaceVariant = Color(0xFFE2D3C1),
            outline = Color(0xFFBCA78F)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2B2118),
            onPrimary = Color(0xFFFFFBF3),
            background = Color(0xFFFFFBF3),
            onBackground = Color(0xFF211A14),
            surface = Color(0xFFFFFBF3),
            onSurface = Color(0xFF211A14),
            surfaceVariant = Color(0xFFF2E6D6),
            onSurfaceVariant = Color(0xFF534437),
            outline = Color(0xFF756554)
        )
    }

@Composable
private fun LoadingScreen() {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Text("正在读取本机配置", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OAuthWebView(url: String, viewModel: XbClientViewModel) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("第三方登录") },
                actions = {
                    TextButton(onClick = viewModel::closeOAuthWebView) {
                        Text("关闭")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        AndroidView(
            factory = { context ->
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
                    settings.userAgentString = BuildConfig.USER_AGENT
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
                                settings.userAgentString = BuildConfig.USER_AGENT
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
        ) {
            item {
                AnimatedContent(
                    targetState = state.authMode,
                    transitionSpec = { editorialTransition() },
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

@Composable
private fun LoginContent(state: XbClientUiState, viewModel: XbClientViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
    ) {
        PageHeader("账号登录")
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("邮箱") },
            singleLine = true,
            modifier = Modifier
                .contentType(ContentType.Username + ContentType.EmailAddress)
                .fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .contentType(ContentType.Password)
                .fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = { viewModel.login(email, password) }, modifier = Modifier.fillMaxWidth()) {
            Text("登录")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = viewModel::showRegister, modifier = Modifier.fillMaxWidth()) {
            Text("注册账号")
        }
        if (state.oauthProviders.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("第三方登录", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            for (provider in state.oauthProviders) {
                OutlinedButton(
                    onClick = { viewModel.openOAuthPage("login", provider.driver) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("使用 ${provider.label} 登录")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RegisterContent(state: XbClientUiState, viewModel: XbClientViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var emailCode by rememberSaveable { mutableStateOf("") }
    var captcha by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        PageHeader("账号注册", "创建账号后会直接进入节点页面。")
        EditorialSection("账户") {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("邮箱") },
                singleLine = true,
                modifier = Modifier
                    .contentType(ContentType.NewUsername + ContentType.EmailAddress)
                    .fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .contentType(ContentType.NewPassword)
                    .fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = inviteCode, onValueChange = { inviteCode = it }, label = { Text("邀请码，可为空") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = emailCode, onValueChange = { emailCode = it }, label = { Text("邮箱验证码，可为空") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = captcha, onValueChange = { captcha = it }, label = { Text("验证码令牌，可为空") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { viewModel.sendEmailVerify(email, captcha) }, modifier = Modifier.fillMaxWidth()) {
                Text("发送邮箱验证码")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { viewModel.register(email, password, inviteCode, emailCode, captcha) }, modifier = Modifier.fillMaxWidth()) {
                Text("注册")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = viewModel::showLogin, modifier = Modifier.fillMaxWidth()) {
                Text("返回登录")
            }
            if (state.oauthConfirmToken.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("OAuth 注册确认", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "确认使用 ${state.oauthConfirmProvider.ifEmpty { "OAuth" }} 创建或绑定账号：${state.oauthConfirmEmail}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::confirmOAuthRegister, modifier = Modifier.fillMaxWidth()) {
                    Text("确认注册并登录")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = viewModel::clearOAuthConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text("取消 OAuth 注册")
                }
            }
            if (state.oauthProviders.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("第三方注册", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                for (provider in state.oauthProviders) {
                    OutlinedButton(
                        onClick = { viewModel.openOAuthPage("register", provider.driver, inviteCode) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("使用 ${provider.label} 注册")
                    }
                    Spacer(Modifier.height(8.dp))
                }
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
                                PassScreen.SETTINGS -> SettingsScreen(state, viewModel)
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
    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
        NavigationBarItem(
            selected = selected == PassScreen.NODES,
            onClick = { viewModel.openScreen(PassScreen.NODES) },
            icon = {},
            label = { Text("节点") }
        )
        NavigationBarItem(
            selected = selected == PassScreen.PLANS,
            onClick = { viewModel.openScreen(PassScreen.PLANS) },
            icon = {},
            label = { Text("套餐") }
        )
        NavigationBarItem(
            selected = selected == PassScreen.PROFILE,
            onClick = { viewModel.openScreen(PassScreen.PROFILE) },
            icon = {},
            label = { Text("我的") }
        )
    }
}

@Composable
private fun NodesScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    val selectedNode = state.anyTlsNodes.getOrNull(state.selectedNodeIndex)
    PageHeader("节点", "节点来自当前账户订阅，登录后自动同步。")
    EditorialSection("连接") {
        Text(
            if (state.vpnRequested) "已连接" else "未连接",
            style = MaterialTheme.typography.displaySmall
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { if (state.vpnRequested) viewModel.stopVpn(context) else viewModel.requestStartVpn() },
            enabled = !state.vpnStarting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.vpnStarting) "正在连接" else if (state.vpnRequested) "断开" else "开始连接")
        }
    }
    EditorialSection("当前节点") {
        Text(
            selectedNode?.displayName(state.selectedNodeIndex) ?: if (state.nodesLoading) "节点正在同步。" else "暂无可用节点",
            style = MaterialTheme.typography.headlineMedium
        )
        state.nodeTestResults[state.selectedNodeIndex]?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = { viewModel.testNode(state.selectedNodeIndex) }, modifier = Modifier.fillMaxWidth()) {
            Text("测试当前节点")
        }
    }
    EditorialSection("可用节点") {
        if (state.anyTlsNodes.isEmpty()) {
            Text(if (state.nodesLoading) "节点正在同步。" else "暂无可用节点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlansScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    PageHeader("套餐", "选择可用套餐。")
    RewardAdSection(state, viewModel)
    EditorialSection("套餐") {
        if (!state.paymentEnabled) {
            Text(
                "网页支付入口已关闭；当前仅支持余额足额抵扣，余额 ${formatMoney(state.balance, state.currencySymbol)}。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        }
        if (state.plansLoading) {
            Text("套餐正在加载。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.plans.isEmpty()) {
            Text("暂无可用套餐。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            for ((index, plan) in state.plans.withIndex()) {
                PlanRow(
                    plan = plan,
                    currencySymbol = state.currencySymbol,
                    paymentEnabled = state.paymentEnabled,
                    onOpenPayment = { viewModel.openPlanPage(context, plan.id) },
                    onBalancePurchase = { price -> viewModel.buyPlanWithBalance(plan.id, price.field, price.amount) }
                )
                if (index != state.plans.lastIndex) {
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PlanRow(
    plan: PlanItem,
    currencySymbol: String,
    paymentEnabled: Boolean,
    onOpenPayment: () -> Unit,
    onBalancePurchase: (PlanPrice) -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(enabled = paymentEnabled, onClick = onOpenPayment)
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(plan.name, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(planPriceText(plan, currencySymbol), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (plan.transferEnable > 0.0) {
            Spacer(Modifier.height(4.dp))
            Text("流量 ${formatTrafficGb(plan.transferEnable)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val content = plan.content.trim()
        if (content.isNotEmpty() && !content.startsWith("[") && !content.startsWith("{")) {
            Spacer(Modifier.height(4.dp))
            Text(content, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!paymentEnabled && plan.prices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            for (price in plan.prices) {
                TextButton(onClick = { onBalancePurchase(price) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${price.label} ${formatMoney(price.amount, currencySymbol)}")
                }
            }
        }
    }
}

@Composable
private fun RewardAdSection(state: XbClientUiState, viewModel: XbClientViewModel) {
    if (!state.adEnabled) {
        return
    }
    EditorialSection("广告奖励") {
        Text(
            "观看激励广告后由服务端验证，并通过礼品卡自动兑换到账户。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = viewModel::requestRewardAd, modifier = Modifier.weight(1f)) {
                Text("观看广告")
            }
            OutlinedButton(onClick = viewModel::refreshAdRewardHistory, modifier = Modifier.weight(1f)) {
                Text(if (state.adRewardLogsLoading) "刷新中" else "刷新记录")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.adRewardLogs.isEmpty()) {
            Text(
                if (state.adRewardLogsLoading) "奖励记录正在加载。" else "暂无已验证的广告奖励记录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            for ((index, log) in state.adRewardLogs.take(3).withIndex()) {
                Text(if (log.status == "credited") "已发放" else log.status, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text("兑换码 ${log.giftCardCode.ifEmpty { "未生成" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "模板 ${log.giftCardTemplateId} · 记录 ${log.id} · ${formatUnixTime(log.createdAt)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (index != state.adRewardLogs.take(3).lastIndex) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    PageHeader("我的", "账户、积分与邀请信息。")
    EditorialSection("账户") {
        Text(state.userEmail.ifEmpty { "已登录" }, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "余额 ${formatMoney(state.balance, state.currencySymbol)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "佣金 ${formatMoney(state.commissionBalance, state.currencySymbol)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            state.subscriptionSummary.ifEmpty { if (state.subscribeUrl.isEmpty()) "订阅未同步" else "订阅已同步" },
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = { viewModel.openScreen(PassScreen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
            Text("设置")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
            Text("退出登录")
        }
    }
    RewardAdSection(state, viewModel)
    EditorialSection("邀请") {
        if (state.invites.isEmpty()) {
            Text(if (state.invitesLoading) "邀请码正在加载。" else "暂无邀请码。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            for ((index, invite) in state.invites.withIndex()) {
                Text(invite.code, style = MaterialTheme.typography.titleLarge)
                Text(if (invite.status == 0) "可用" else "已使用", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (index != state.invites.lastIndex) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = viewModel::generateInvite, modifier = Modifier.fillMaxWidth()) {
            Text("生成邀请码")
        }
    }
}

@Composable
private fun SettingsScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    var nodeDns by rememberSaveable(state.nodeDns) { mutableStateOf(state.nodeDns) }
    var overseasDns by rememberSaveable(state.overseasDns) { mutableStateOf(state.overseasDns) }
    var directDns by rememberSaveable(state.directDns) { mutableStateOf(state.directDns) }
    var nodeTestTarget by rememberSaveable(state.nodeTestTarget) { mutableStateOf(state.nodeTestTarget) }
    PageHeader("设置", "设置 DNS、IPv6 与按应用规则。")
    EditorialSection("应用规则") {
        val selectedCount = selectedPackages(state).size
        Text(
            if (state.appRuleMode == MODE_ALLOW) "白名单模式：只有已选择的应用使用连接。" else "黑名单模式：已选择的应用不使用连接。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(if (selectedCount == 0) "尚未选择应用。" else "已选择 $selectedCount 个应用。")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.openScreen(PassScreen.APP_RULES) }, modifier = Modifier.weight(1f)) {
                Text("选择应用")
            }
            OutlinedButton(onClick = viewModel::clearSelectedApps, modifier = Modifier.weight(1f)) {
                Text("清空选择")
            }
        }
    }
    EditorialSection("DNS") {
        OutlinedTextField(value = nodeDns, onValueChange = { nodeDns = it }, label = { Text("解析节点域名的本地 DNS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = overseasDns, onValueChange = { overseasDns = it }, label = { Text("连接后解析域名的海外 DNS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = directDns, onValueChange = { directDns = it }, label = { Text("连接后解析分流直连域名的 DNS") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("启用 IPv6")
            Switch(checked = state.vpnIpv6Enabled, onCheckedChange = viewModel::setIpv6Enabled)
        }
    }
    EditorialSection("节点测试") {
        OutlinedTextField(value = nodeTestTarget, onValueChange = { nodeTestTarget = it }, label = { Text("测试目标网站") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            "可填写域名、域名:端口或 http/https 地址。节点测试会连续完成两次 HEAD 请求并显示第二次延迟。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { viewModel.saveDnsAndTestSettings(nodeDns, overseasDns, directDns, nodeTestTarget) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存设置")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { viewModel.openScreen(PassScreen.PROFILE) }, modifier = Modifier.fillMaxWidth()) {
            Text("返回我的")
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
            PageHeader("选择节点", "可在此测试每个节点到目标网站的真实连接延迟。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { viewModel.openScreen(PassScreen.NODES) }, modifier = Modifier.weight(1f)) {
                    Text("返回节点")
                }
                Button(onClick = viewModel::testAllNodes, modifier = Modifier.weight(1f)) {
                    Text(if (state.nodesTesting) "测试中" else "测试连接")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (state.anyTlsNodes.isEmpty()) {
            item {
                EditorialSection("可用节点") {
                    Text(if (state.nodesLoading) "节点正在同步。" else "暂无可用节点。", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp))
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
    Row(
        modifier = Modifier
            .clickable(onClick = onSelect)
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text((if (selected) "✓ " else "") + node.displayName(index), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(testText ?: "未测试", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onTest, modifier = Modifier.size(32.dp)) {
            Text("↻")
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
            PageHeader("应用规则", "选择黑名单或白名单模式，并搜索需要设置的应用。")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (state.appRuleMode == MODE_EXCLUDE) {
                    Button(onClick = { viewModel.switchAppRuleMode(MODE_EXCLUDE) }, modifier = Modifier.weight(1f)) { Text("黑名单") }
                    OutlinedButton(onClick = { viewModel.switchAppRuleMode(MODE_ALLOW) }, modifier = Modifier.weight(1f)) { Text("白名单") }
                } else {
                    OutlinedButton(onClick = { viewModel.switchAppRuleMode(MODE_EXCLUDE) }, modifier = Modifier.weight(1f)) { Text("黑名单") }
                    Button(onClick = { viewModel.switchAppRuleMode(MODE_ALLOW) }, modifier = Modifier.weight(1f)) { Text("白名单") }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.appSearchQuery,
                onValueChange = viewModel::setAppSearchQuery,
                label = { Text("搜索应用或包名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("已选择 ${packages.size} 个应用。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { viewModel.openScreen(PassScreen.SETTINGS) }, modifier = Modifier.weight(1f)) {
                    Text("返回设置")
                }
                TextButton(onClick = viewModel::clearSelectedApps, modifier = Modifier.weight(1f)) {
                    Text("清空选择")
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
    if (state.startupConfigDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissStartupConfigDialog,
            title = { Text("本地配置") },
            text = {
                Column {
                    Text("面板：${BuildConfig.DEFAULT_API_URL}")
                    Text("网页支付：${if (state.paymentEnabled) "开启" else "关闭"}")
                    Text("激励广告：${if (state.adEnabled) "开启" else "关闭"}")
                    Text("开屏广告：${if (state.appOpenAdEnabled) "开启" else "关闭"}")
                    Text("更新项目：${state.githubProjectUrl.ifEmpty { "未配置" }}")
                    Text("配置同步：${formatConfigTime(state.configUpdatedAt)}")
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissStartupConfigDialog) {
                    Text("知道了")
                }
            }
        )
    }
    if (state.updateAvailable) {
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdateDialog,
            title = { Text("发现新版本") },
            text = {
                Text("当前版本 ${BuildConfig.VERSION_NAME.removeSuffix(".debug")}，最新版本 ${state.latestReleaseVersion}。")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.openUpdatePage(context) }) {
                    Text(if (state.latestDownloadUrl.isEmpty()) "打开 Release" else "下载更新")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdateDialog) {
                    Text("稍后")
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
                if (state.nodeSwitchConnect) "更换节点" else "选择节点",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(state.anyTlsNodes, key = { index, node -> "${node.displayName(index)}-$index" }) { index, node ->
                    ListItem(
                        headlineContent = { Text(node.displayName(index)) },
                        supportingContent = { Text("${node.protocolLabel} · ${state.nodeTestResults[index] ?: "未测试"}") },
                        trailingContent = { if (index == state.selectedNodeIndex) Text("已选择") },
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
    Text(title, style = MaterialTheme.typography.displaySmall)
    if (subtitle.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun EditorialSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        content()
    }
}

private fun selectedPackages(state: XbClientUiState): Set<String> =
    (if (state.appRuleMode == MODE_ALLOW) state.allowedApps else state.excludedApps)
        .split(Regex("[,;\\s]+"))
        .filter { it.isNotEmpty() }
        .toSet()

private fun formatMoney(amount: Int, symbol: String): String =
    symbol + String.format(Locale.US, "%.2f", amount / 100.0)

private fun formatConfigTime(value: Long): String =
    if (value <= 0L) "尚未同步" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))

private fun formatUnixTime(value: Long): String =
    if (value <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value * 1000))

private fun planPriceText(plan: PlanItem, symbol: String): String =
    if (plan.prices.isEmpty()) "价格未设置" else plan.prices.joinToString(" · ") {
        "${it.label} ${formatMoney(it.amount, symbol)}"
    }

private val XbClientUiState.canHandleBack: Boolean
    get() = startupConfigDialogVisible ||
        updateAvailable ||
        oauthWebViewUrl.isNotEmpty() ||
        !isLoggedIn && authMode == AuthMode.REGISTER ||
        isLoggedIn && screen !in setOf(PassScreen.NODES, PassScreen.PLANS, PassScreen.PROFILE)

private fun AnimatedContentTransitionScope<*>.editorialTransition() =
    (fadeIn(animationSpec = tween(180)) togetherWith
        fadeOut(animationSpec = tween(140))).using(SizeTransform(clip = false))

private fun AnimatedContentTransitionScope<PassScreen>.screenTransition() =
    (fadeIn(animationSpec = tween(120)) togetherWith
        fadeOut(animationSpec = tween(90))).using(SizeTransform(clip = false))
