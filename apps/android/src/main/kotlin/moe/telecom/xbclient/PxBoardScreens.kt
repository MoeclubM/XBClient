package moe.telecom.xbclient

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import moe.telecom.neto.sdk.NetoPreferenceCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PxBoardLogin(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    val authState by viewModel.pxboardAuthAdapter.authState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("login") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var mfaCode by rememberSaveable { mutableStateOf("") }
    var regProviderIdx by rememberSaveable { mutableIntStateOf(0) }
    val regValues = remember { mutableStateMapOf<String, String>() }
    var resetEmail by rememberSaveable { mutableStateOf("") }
    var resetToken by rememberSaveable { mutableStateOf("") }
    var resetPassword by rememberSaveable { mutableStateOf("") }

    val regProvider = authState.registrationProviders.getOrNull(regProviderIdx.coerceAtLeast(0))
    LaunchedEffect(regProvider?.key) {
        regProvider?.fields?.forEach { field ->
            if (field.key !in regValues) regValues[field.key] = field.defaultValue
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (authState.registrationProviders.isNotEmpty() || authState.passwordResetEnabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.auth_login),
                    onClick = { tab = "login" },
                    modifier = Modifier.weight(1f),
                    colors = if (tab == "login") ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                )
                if (authState.registrationProviders.isNotEmpty()) {
                    TextButton(
                        text = stringResource(R.string.auth_register),
                        onClick = { tab = "register" },
                        modifier = Modifier.weight(1f),
                        colors = if (tab == "register") ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                }
                if (authState.passwordResetEnabled) {
                    TextButton(
                        text = "找回密码",
                        onClick = { tab = "reset" },
                        modifier = Modifier.weight(1f),
                        colors = if (tab == "reset") ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            when (tab) {
                "register" -> if (regProvider != null) {
                    if (authState.registrationProviders.size > 1) {
                        OverlayDropdownPreference(
                            title = "注册方式",
                            items = authState.registrationProviders.map { it.title },
                            selectedIndex = regProviderIdx.coerceIn(0, authState.registrationProviders.lastIndex),
                            onSelectedIndexChange = { regProviderIdx = it },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (!authState.registerChallengeRequired) {
                        for (field in regProvider.fields) {
                            if (field.type == "hidden" && field.key != "referral") continue
                            if (field.type == "boolean") {
                                SwitchPreference(
                                    title = field.label,
                                    checked = regValues[field.key] == "true",
                                    onCheckedChange = { regValues[field.key] = it.toString() },
                                )
                            } else {
                                val isPwd = field.type == "password" || field.key.contains("password", ignoreCase = true)
                                val fieldLabel = if (field.key == "referral" && (field.type == "hidden" || field.label.isBlank())) {
                                    "推广码（选填）"
                                } else {
                                    field.label
                                }
                                TextField(
                                    value = regValues[field.key].orEmpty(),
                                    onValueChange = { regValues[field.key] = it },
                                    label = fieldLabel,
                                    useLabelAsPlaceholder = true,
                                    singleLine = true,
                                    visualTransformation = if (isPwd) PasswordVisualTransformation() else VisualTransformation.None,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        Button(
                            onClick = {
                                val payload = LinkedHashMap<String, Any?>()
                                for (f in regProvider.fields) {
                                    val raw = regValues[f.key].orEmpty()
                                    payload[f.key] = when (f.type) {
                                        "boolean" -> raw == "true"
                                        "number" -> raw.toDoubleOrNull() ?: 0.0
                                        else -> if (f.key == "referral") extractQueryParamOrRaw(raw, "ref", "code") else raw.trim()
                                    }
                                }
                                viewModel.pxboardAuthAdapter.register(regProvider, payload) {
                                    email = regValues["email"].orEmpty().trim()
                                    tab = "login"
                                }
                            },
                            enabled = !authState.busy && !state.nativeLoginPending,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(stringResource(R.string.auth_register))
                        }
                    } else {
                        Button(
                            onClick = { viewModel.loginPxBoard() },
                            enabled = !state.nativeLoginPending,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("在浏览器中完成注册与登录")
                        }
                    }
                }
                "reset" -> {
                    if (authState.resetChallengeRequired) {
                        Button(
                            onClick = { BrowserOpener.open(context, PxBoardApi.publicUrl("/forgot")) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("在浏览器中找回密码")
                        }
                    } else {
                    TextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        label = stringResource(R.string.auth_email),
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.pxboardAuthAdapter.requestReset(resetEmail) },
                        enabled = !authState.busy && resetEmail.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("发送重置邮件")
                    }
                    if (authState.resetSent) {
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = resetToken,
                            onValueChange = { resetToken = it },
                            label = "邮件重置码或链接",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        TextField(
                            value = resetPassword,
                            onValueChange = { resetPassword = it },
                            label = "新密码（至少 10 位）",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                viewModel.pxboardAuthAdapter.confirmReset(resetToken, resetPassword) {
                                    email = resetEmail.trim()
                                    resetToken = ""
                                    resetPassword = ""
                                    tab = "login"
                                }
                            },
                            enabled = !authState.busy && resetToken.isNotBlank() && resetPassword.length >= 10,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("确认重置密码")
                        }
                    }
                    }
                }
                else -> {
                    if (!authState.loginChallengeRequired) {
                        TextField(
                            value = email,
                            onValueChange = { email = it },
                            label = stringResource(R.string.auth_email),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        TextField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.auth_password),
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (authState.mfaRequired) {
                            Spacer(Modifier.height(10.dp))
                            TextField(
                                value = mfaCode,
                                onValueChange = { mfaCode = it },
                                label = "两步验证码",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.pxboardAuthAdapter.loginWithPassword(email, password, mfaCode) },
                            enabled = !authState.busy && !state.nativeLoginPending && email.isNotBlank() && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(stringResource(R.string.auth_login))
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Button(
                        onClick = { viewModel.loginPxBoard() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (authState.loginChallengeRequired) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        enabled = !state.nativeLoginPending && !authState.busy,
                    ) {
                        Text(
                            if (state.nativeLoginPending) stringResource(R.string.panel_authorizing)
                            else if (authState.loginChallengeRequired) stringResource(R.string.auth_login)
                            else "浏览器授权登录"
                        )
                    }
                    for (provider in state.oauthProviders) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { viewModel.loginPxBoard(provider.driver) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.nativeLoginPending && !authState.busy,
                        ) {
                            Text(stringResource(R.string.auth_oauth_login_button, provider.label))
                        }
                    }
                    if (state.nativeLoginPending) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = viewModel::cancelPxBoardLogin, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.panel_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PxBoardPlansScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val portal by viewModel.pxboardSession.portalState.collectAsStateWithLifecycle()
    var selectedPlan by remember { mutableStateOf<PxBoardPlan?>(null) }
    var selectedPeriodId by rememberSaveable { mutableStateOf("") }
    var coupon by rememberSaveable { mutableStateOf("") }
    var payMethod by rememberSaveable { mutableStateOf("wallet") }
    val hasCommerce = "commerce" in state.nativeExtensions

    RewardAdSection(
        title = stringResource(R.string.reward_plan_title),
        enabled = state.planRewardAdEnabled,
        scene = REWARD_SCENE_PLAN,
        state = state,
        viewModel = viewModel,
    )

    if (hasCommerce) {
        Section(stringResource(R.string.section_plans)) {
            Panel {
                Text(
                    stringResource(R.string.balance_amount, formatMoney(state.balance, state.currencySymbol, state.currencyUnit)),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }

    if (portal.plans.isEmpty()) {
        if (hasCommerce || !state.planRewardAdEnabled) {
            NetoPreferenceCard {
                Text(
                    stringResource(id = if (state.nodesLoading) R.string.plans_loading else R.string.plans_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    } else {
        for (plan in portal.plans) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                insideMargin = PaddingValues(18.dp),
            ) {
                Text(plan.name, style = MiuixTheme.textStyles.title2)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (plan.unlimited) "不限流量" else stringResource(R.string.plan_traffic, formatTrafficGb(plan.quotaGiB)),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (plan.description.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(plan.description, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Spacer(Modifier.height(12.dp))
                for (period in plan.periods) {
                    Button(
                        onClick = {
                            selectedPlan = plan
                            selectedPeriodId = period.id
                            coupon = ""
                            payMethod = "wallet"
                            viewModel.pxboardSession.clearActiveCheckout()
                            viewModel.pxboardSession.quoteOrder(plan.id, period.id, "")
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Text("${period.label} · ${formatMoney(period.price, state.currencySymbol, state.currencyUnit)}")
                    }
                }
            }
        }
    }

    val dialogPlan = selectedPlan
    val activeOrder = portal.activeOrder
    OverlayDialog(
        show = dialogPlan != null,
        title = if (activeOrder != null) "支付订单" else (dialogPlan?.name ?: ""),
        onDismissRequest = {
            if (!portal.busy) {
                selectedPlan = null
                viewModel.pxboardSession.clearActiveCheckout()
            }
        },
    ) {
        if (dialogPlan != null) {
            val activePeriod = dialogPlan.periods.firstOrNull { it.id == selectedPeriodId } ?: dialogPlan.periods.first()
            val due = activeOrder?.amount ?: portal.quote?.amount ?: activePeriod.price
            if (activeOrder == null) {
                OverlayDropdownPreference(
                    title = "购买周期",
                    items = dialogPlan.periods.map { "${it.label} · ${formatMoney(it.price, state.currencySymbol, state.currencyUnit)}" },
                    selectedIndex = dialogPlan.periods.indexOfFirst { it.id == selectedPeriodId }.coerceAtLeast(0),
                    onSelectedIndexChange = { idx ->
                        val next = dialogPlan.periods[idx]
                        selectedPeriodId = next.id
                        viewModel.pxboardSession.quoteOrder(dialogPlan.id, next.id, coupon)
                    },
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = coupon,
                    onValueChange = {
                        coupon = it
                        viewModel.pxboardSession.quoteOrder(dialogPlan.id, selectedPeriodId, it)
                    },
                    label = "优惠码（可选）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                if (due > 0) {
                    val paymentOptions = buildList {
                        add("wallet" to "账户余额（${formatMoney(state.balance, state.currencySymbol, state.currencyUnit)}）")
                        if (state.paymentEnabled) {
                            for (ch in portal.channels) {
                                add(ch.id to ch.name)
                            }
                        }
                    }
                    val selectedPayIdx = paymentOptions.indexOfFirst { it.first == payMethod }.coerceAtLeast(0)
                    OverlayDropdownPreference(
                        title = "支付方式",
                        items = paymentOptions.map { it.second },
                        selectedIndex = selectedPayIdx,
                        onSelectedIndexChange = { idx -> payMethod = paymentOptions[idx].first },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val discount = activeOrder?.discount ?: portal.quote?.discount ?: 0
            val surplus = activeOrder?.surplusAmount ?: portal.quote?.surplusAmount ?: 0
            if (discount > 0) {
                Text("优惠减免：-${formatMoney(discount, state.currencySymbol, state.currencyUnit)}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (surplus > 0) {
                Text("套餐折抵：-${formatMoney(surplus, state.currencySymbol, state.currencyUnit)}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Text(
                "应付金额：" + if (portal.quoting && activeOrder == null) "计算中…" else formatMoney(due, state.currencySymbol, state.currencyUnit),
                style = MiuixTheme.textStyles.title2,
            )
            val walletShort = activeOrder != null && due > 0 && payMethod == "wallet" && state.balance < due
            if (walletShort) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (state.paymentEnabled) "余额不足，请选择在线支付或前往账单充值" else "账户余额不足，请先使用兑换码补充余额或兑换套餐",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.panel_cancel),
                    onClick = {
                        selectedPlan = null
                        viewModel.pxboardSession.clearActiveCheckout()
                    },
                    enabled = !portal.busy,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = when {
                        activeOrder == null -> "创建订单"
                        due == 0 -> "确认开通"
                        payMethod == "wallet" -> "余额支付"
                        else -> "在线支付"
                    },
                    enabled = !portal.busy && (activeOrder != null && !walletShort || activeOrder == null && !portal.quoting),
                    onClick = {
                        if (activeOrder == null) {
                            viewModel.pxboardSession.createPlanOrder(dialogPlan.id, activePeriod.id, coupon)
                        } else if (payMethod == "wallet" || due == 0) {
                            viewModel.pxboardSession.payOrderWithWallet(activeOrder.id) { selectedPlan = null }
                        } else {
                            viewModel.pxboardSession.checkoutOrderOnline(activeOrder.id, payMethod) { selectedPlan = null }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
internal fun PxBoardAccount(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    val portal by viewModel.pxboardSession.portalState.collectAsStateWithLifecycle()
    var linkName by rememberSaveable { mutableStateOf("") }
    var bindUa by rememberSaveable { mutableStateOf(false) }
    val ext = state.nativeExtensions

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        insideMargin = PaddingValues(18.dp),
    ) {
        Text(state.userEmail, style = MiuixTheme.textStyles.title2)
        if ("commerce" in ext) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.balance_amount, formatMoney(state.balance, state.currencySymbol, state.currencyUnit)),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if ("affiliate" in ext) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.commission_amount, formatMoney(state.commissionBalance, state.currencySymbol, state.currencyUnit)),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            state.subscriptionSummary.ifEmpty { stringResource(R.string.subscription_not_synced) },
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }

    NetoPreferenceCard {
        if ("commerce" in ext || "redeem" in ext) {
            ArrowPreference(
                title = when {
                    "commerce" !in ext -> stringResource(R.string.page_gift_cards)
                    state.paymentEnabled -> "账单、充值与兑换码"
                    else -> "账单与兑换码"
                },
                onClick = { viewModel.openScreen(PassScreen.GIFT_CARDS) },
            )
        }
        if ("support" in ext) {
            ArrowPreference(
                title = "工单与使用帮助",
                onClick = { viewModel.openScreen(PassScreen.TICKETS) },
            )
        }
        if ("affiliate" in ext) {
            ArrowPreference(
                title = "推广返佣",
                onClick = { viewModel.openScreen(PassScreen.INVITE_DETAILS) },
            )
        }
        ArrowPreference(
            title = stringResource(R.string.page_account_security),
            onClick = { viewModel.openScreen(PassScreen.ACCOUNT_SECURITY) },
        )
        ArrowPreference(
            title = stringResource(R.string.common_settings),
            onClick = { viewModel.openScreen(PassScreen.SETTINGS) },
        )
        if (state.paymentEnabled) {
            ArrowPreference(
                title = stringResource(R.string.panel_user_center),
                summary = "在浏览器打开完整网页控制台",
                onClick = { viewModel.openPxBoardPortal("/user") },
            )
        }
        ArrowPreference(
            title = stringResource(R.string.common_logout),
            onClick = viewModel::logout,
        )
    }

    RewardAdSection(
        title = stringResource(R.string.reward_points_title),
        enabled = state.pointsRewardAdEnabled,
        scene = REWARD_SCENE_POINTS,
        state = state,
        viewModel = viewModel,
    )

    if ("subscriptions" in ext) {
        Section("订阅链接") {
            Panel {
                TextField(
                    value = linkName,
                    onValueChange = { linkName = it },
                    label = "订阅名称（如：手机 / 电脑）",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                SwitchPreference(
                    title = "绑定首次使用的客户端",
                    checked = bindUa,
                    onCheckedChange = { bindUa = it },
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        viewModel.pxboardSession.createSubLink(linkName, bindUa)
                        linkName = ""
                        bindUa = false
                    },
                    enabled = !portal.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("创建订阅链接")
                }
                if (portal.links.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    for ((idx, link) in portal.links.withIndex()) {
                        Text(
                            link.name + if (link.boundClient.isNotEmpty()) " · 已绑定 ${link.boundClient}" else "",
                            style = MiuixTheme.textStyles.title2,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (link.shareUrl.isNotEmpty()) {
                                TextButton(
                                    text = stringResource(R.string.invite_copy),
                                    onClick = {
                                        context.getSystemService(ClipboardManager::class.java)
                                            .setPrimaryClip(ClipData.newPlainText("sub", link.shareUrl))
                                        Toast.makeText(context, "订阅链接已复制", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                            } else {
                                TextButton(
                                    text = "前往验证",
                                    onClick = { viewModel.openPxBoardPortal("/user/subscriptions") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                            }
                            TextButton(
                                text = "重置",
                                onClick = { viewModel.pxboardSession.mutateSubLink(link.id, "rotate") },
                                enabled = !portal.busy,
                            )
                            TextButton(
                                text = "删除",
                                onClick = { viewModel.pxboardSession.mutateSubLink(link.id, "delete") },
                                enabled = !portal.busy,
                            )
                        }
                        if (idx != portal.links.lastIndex) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }
}
