package moe.telecom.xbclient

import android.content.ClipData
import android.content.ClipboardManager
import android.util.Base64
import android.widget.Toast
import moe.telecom.neto.sdk.NetoPreferenceCard
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun PxBoardRedeemAndBillingScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val portal by viewModel.pxboardSession.portalState.collectAsStateWithLifecycle()
    val ext = state.nativeExtensions
    var code by rememberSaveable { mutableStateOf("") }
    var rechargeText by rememberSaveable { mutableStateOf("50") }
    var channelIdx by rememberSaveable { mutableIntStateOf(0) }

    if ("redeem" in ext) {
        Section(stringResource(R.string.page_gift_cards)) {
            Panel {
                TextField(
                    value = code,
                    onValueChange = { code = it },
                    label = stringResource(R.string.gift_code),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { viewModel.pxboardSession.redeemCode(code) { code = "" } },
                    enabled = !portal.busy && code.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.gift_redeem))
                }
                if (portal.claims.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    for ((idx, claim) in portal.claims.withIndex()) {
                        Text("${claim.name} · ${claim.summary}", style = MiuixTheme.textStyles.title2)
                        Text(
                            (if (claim.status == "redeemed") "已兑换" else "处理中") + " · ${claim.timestamp}",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (idx != portal.claims.lastIndex) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }

    if ("commerce" in ext) {
        Section(if (state.paymentEnabled && portal.channels.isNotEmpty()) "余额与充值" else "账户余额") {
            Panel {
                Text(
                    stringResource(R.string.balance_amount, formatMoney(state.balance, state.currencySymbol, state.currencyUnit)),
                    style = MiuixTheme.textStyles.title2,
                )
                if (state.paymentEnabled && portal.channels.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    TextField(
                        value = rechargeText,
                        onValueChange = { rechargeText = it },
                        label = "充值金额",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val safeIdx = channelIdx.coerceIn(0, portal.channels.lastIndex)
                    OverlayDropdownPreference(
                        title = "支付渠道",
                        items = portal.channels.map { it.name },
                        selectedIndex = safeIdx,
                        onSelectedIndexChange = { channelIdx = it },
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val cents = ((rechargeText.toDoubleOrNull() ?: 0.0) * 100.0).roundToInt()
                            viewModel.pxboardSession.rechargeWallet(cents, portal.channels[safeIdx].id)
                        },
                        enabled = !portal.busy && (rechargeText.toDoubleOrNull() ?: 0.0) > 0.0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("在线充值")
                    }
                }
            }
        }

        Section("订单记录") {
            Panel {
                if (portal.orders.isEmpty()) {
                    Text("暂无订单记录", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                } else {
                    for ((idx, order) in portal.orders.withIndex()) {
                        Text(
                            "${order.planName.ifEmpty { "余额充值" }} · ${formatMoney(order.amount, state.currencySymbol, state.currencyUnit)}",
                            style = MiuixTheme.textStyles.title2,
                        )
                        Text("${order.number} · ${order.status}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (order.status == "pending") {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (order.kind != "recharge" && state.balance >= order.amount) {
                                    TextButton(
                                        text = "余额支付",
                                        onClick = { viewModel.pxboardSession.payOrderWithWallet(order.id) },
                                        enabled = !portal.busy,
                                        colors = ButtonDefaults.textButtonColorsPrimary(),
                                    )
                                }
                                if (state.paymentEnabled && portal.channels.isNotEmpty() && order.amount > 0) {
                                    val safeCh = portal.channels[channelIdx.coerceIn(0, portal.channels.lastIndex)]
                                    TextButton(
                                        text = "在线支付",
                                        onClick = { viewModel.pxboardSession.checkoutOrderOnline(order.id, safeCh.id) },
                                        enabled = !portal.busy,
                                    )
                                }
                                TextButton(
                                    text = "取消",
                                    onClick = { viewModel.pxboardSession.cancelOrder(order.id) },
                                    enabled = !portal.busy,
                                )
                            }
                        }
                        if (idx != portal.orders.lastIndex) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PxBoardTicketsScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    val portal by viewModel.pxboardSession.portalState.collectAsStateWithLifecycle()
    var subject by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        if (bytes.size > 2 * 1024 * 1024) {
            Toast.makeText(context, "图片大小不能超过 2 MB", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        viewModel.pxboardSession.uploadTicketAttachment(b64) { md ->
            content = if (content.isBlank()) md else "$content\n$md"
        }
    }

    Section(stringResource(R.string.section_new_ticket)) {
        Panel {
            TextField(
                value = subject,
                onValueChange = { subject = it },
                label = stringResource(R.string.ticket_subject),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = content,
                onValueChange = { content = it },
                label = stringResource(R.string.ticket_message),
                useLabelAsPlaceholder = true,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "上传图片",
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !portal.busy,
                )
                Button(
                    onClick = {
                        viewModel.pxboardSession.createTicket(subject, content) {
                            subject = ""
                            content = ""
                        }
                    },
                    enabled = !portal.busy && subject.isNotBlank() && content.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.ticket_submit))
                }
            }
        }
    }

    Section(stringResource(R.string.section_my_tickets)) {
        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (portal.tickets.isEmpty()) {
                Text(stringResource(R.string.ticket_empty), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
            } else {
                for (ticket in portal.tickets) {
                    ArrowPreference(
                        title = ticket.subject,
                        summary = "${ticket.status} · ${ticket.updatedAt}",
                        onClick = { viewModel.pxboardSession.selectTicket(ticket.id) },
                    )
                }
            }
        }
    }

    if (portal.articles.isNotEmpty()) {
        Section("使用帮助") {
            for (article in portal.articles) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text(article.title, style = MiuixTheme.textStyles.title2)
                    Spacer(Modifier.height(6.dp))
                    Text(article.content, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

@Composable
internal fun PxBoardTicketDetailScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val portal by viewModel.pxboardSession.portalState.collectAsStateWithLifecycle()
    val ticket = portal.tickets.firstOrNull { it.id == portal.selectedTicketId }
    var reply by rememberSaveable(ticket?.id) { mutableStateOf("") }

    if (ticket == null) {
        Section(stringResource(R.string.page_ticket_detail)) {
            Panel { Text(stringResource(R.string.ticket_not_selected), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
        return
    }

    Section(ticket.subject) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${ticket.status} · ${ticket.updatedAt}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                if (ticket.status != "closed") {
                    TextButton(
                        text = stringResource(R.string.ticket_close),
                        onClick = { viewModel.pxboardSession.closeTicket(ticket.id) },
                        enabled = !portal.busy,
                    )
                }
            }
        }
    }

    Section(stringResource(R.string.section_ticket_messages)) {
        Panel {
            if (ticket.thread.isNotEmpty()) {
                Text(ticket.thread)
                Spacer(Modifier.height(8.dp))
            }
            for ((idx, msg) in ticket.messages.withIndex()) {
                Text("${msg.author} · ${msg.createdAt}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(4.dp))
                Text(msg.content)
                if (idx != ticket.messages.lastIndex) {
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                }
            }
        }
    }

    if (ticket.status != "closed") {
        Section(stringResource(R.string.section_ticket_reply)) {
            Panel {
                TextField(
                    value = reply,
                    onValueChange = { reply = it },
                    label = stringResource(R.string.ticket_reply_hint),
                    useLabelAsPlaceholder = true,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { viewModel.pxboardSession.replyTicket(ticket.id, reply) { reply = "" } },
                    enabled = !portal.busy && reply.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.ticket_reply_submit))
                }
            }
        }
    }
}

@Composable
internal fun PxBoardAffiliateScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    val portal by viewModel.pxboardSession.portalState.collectAsStateWithLifecycle()
    val aff = portal.affiliate
    var amountText by rememberSaveable { mutableStateOf("") }
    var payoutAccount by rememberSaveable { mutableStateOf("") }
    var methodIdx by rememberSaveable { mutableIntStateOf(0) }
    val methods = listOf("wallet" to "转入余额", "cash" to "现金提现")

    Section("推广概况") {
        Panel {
            Text("可用佣金：${formatMoney(aff.availableAmount, state.currencySymbol, state.currencyUnit)}", style = MiuixTheme.textStyles.title2)
            Spacer(Modifier.height(4.dp))
            Text(
                "冻结 ${formatMoney(aff.pendingAmount, state.currencySymbol, state.currencyUnit)} · 累计 ${formatMoney(aff.totalAmount, state.currencySymbol, state.currencyUnit)} · 邀请 ${aff.invitedCount} 人",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "返佣 ${aff.commissionPercent}% · 冻结 ${aff.commissionHoldDays} 天",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (aff.referralUrl.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("referral", aff.referralUrl))
                        Toast.makeText(context, "推广链接已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("复制推广链接")
                }
            }
        }
    }

    Section("佣金结算") {
        Panel {
            OverlayDropdownPreference(
                title = "结算方式",
                items = methods.map { it.second },
                selectedIndex = methodIdx,
                onSelectedIndexChange = { methodIdx = it },
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = "金额",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (methods[methodIdx].first == "cash") {
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = payoutAccount,
                    onValueChange = { payoutAccount = it },
                    label = "收款方式与账户",
                    useLabelAsPlaceholder = true,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val cents = ((amountText.toDoubleOrNull() ?: 0.0) * 100.0).roundToInt()
                    viewModel.pxboardSession.createWithdrawal(cents, methods[methodIdx].first, payoutAccount) {
                        amountText = ""
                        payoutAccount = ""
                    }
                },
                enabled = !portal.busy && (amountText.toDoubleOrNull() ?: 0.0) > 0.0 && (methods[methodIdx].first != "cash" || payoutAccount.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("提交结算")
            }
        }
    }

    if (portal.withdrawals.isNotEmpty()) {
        Section("结算记录") {
            Panel {
                for ((idx, row) in portal.withdrawals.withIndex()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatMoney(row.amount, state.currencySymbol, state.currencyUnit)} · ${if (row.method == "cash") "现金提现" else "转入余额"}")
                            Text("${row.status} · ${row.createdAt}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (row.status == "pending") {
                            val act = if (row.method == "wallet") "transfer" else "cancel"
                            TextButton(
                                text = if (row.method == "wallet") "转入" else "撤回",
                                onClick = { viewModel.pxboardSession.mutateWithdrawal(row.id, act) },
                                enabled = !portal.busy,
                            )
                        }
                    }
                    if (idx != portal.withdrawals.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PxBoardSecurityScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    val portal by viewModel.pxboardSession.portalState.collectAsStateWithLifecycle()
    var oldPwd by rememberSaveable { mutableStateOf("") }
    var mfaCode by rememberSaveable { mutableStateOf("") }
    var newPwd by rememberSaveable { mutableStateOf("") }
    var mfaActionPwd by rememberSaveable { mutableStateOf("") }
    var mfaActionCode by rememberSaveable { mutableStateOf("") }
    var unbindPwd by rememberSaveable { mutableStateOf("") }
    var unbindCode by rememberSaveable { mutableStateOf("") }

    if ("access" in state.nativeExtensions && (portal.expireEnabled || portal.trafficEnabled)) {
        Section("邮件提醒") {
            NetoPreferenceCard {
                if (portal.expireEnabled) {
                    SwitchPreference(
                        title = "套餐到期前邮件提醒",
                        checked = portal.expireReminder,
                        onCheckedChange = { viewModel.pxboardSession.saveReminders(it, portal.trafficReminder) },
                    )
                }
                if (portal.trafficEnabled) {
                    SwitchPreference(
                        title = "流量不足邮件提醒",
                        checked = portal.trafficReminder,
                        onCheckedChange = { viewModel.pxboardSession.saveReminders(portal.expireReminder, it) },
                    )
                }
            }
        }
    }

    Section(stringResource(R.string.section_change_password)) {
        Panel {
            TextField(
                value = oldPwd,
                onValueChange = { oldPwd = it },
                label = stringResource(R.string.password_old),
                useLabelAsPlaceholder = true,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (portal.mfaEnabled) {
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = mfaCode,
                    onValueChange = { mfaCode = it },
                    label = "6 位动态验证码",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = newPwd,
                onValueChange = { newPwd = it },
                label = "新密码（至少 10 位）",
                useLabelAsPlaceholder = true,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.pxboardSession.changePassword(oldPwd, mfaCode, newPwd, viewModel::logout) },
                enabled = !portal.busy && oldPwd.isNotEmpty() && newPwd.length >= 10,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(stringResource(R.string.password_save))
            }
        }
    }

    Section("两步验证") {
        Panel {
            val setup = portal.mfaSetup
            if (portal.mfaEnabled) {
                Text(
                    "已启用 · 登录和敏感操作需输入 6 位动态验证码",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = mfaActionPwd,
                    onValueChange = { mfaActionPwd = it },
                    label = "当前登录密码",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = mfaActionCode,
                    onValueChange = { mfaActionCode = it },
                    label = "6 位动态验证码",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        viewModel.pxboardSession.disableMfa(mfaActionPwd, mfaActionCode) {
                            mfaActionPwd = ""
                            mfaActionCode = ""
                        }
                    },
                    enabled = !portal.busy && mfaActionPwd.isNotEmpty() && mfaActionCode.trim().length == 6,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("关闭两步验证")
                }
            } else if (setup != null) {
                Text(
                    "在身份验证器中添加密钥或导入链接，再输入生成的 6 位验证码完成启用：",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Text(setup.secret, style = MiuixTheme.textStyles.title2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = "复制密钥",
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("mfa_secret", setup.secret))
                            Toast.makeText(context, "验证器密钥已复制", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    if (setup.otpauthUrl.isNotEmpty()) {
                        TextButton(
                            text = "复制导入链接",
                            onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("otpauth", setup.otpauthUrl))
                                Toast.makeText(context, "导入链接已复制", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = mfaActionPwd,
                    onValueChange = { mfaActionPwd = it },
                    label = "当前登录密码",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = mfaActionCode,
                    onValueChange = { mfaActionCode = it },
                    label = "6 位动态验证码",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.panel_cancel),
                        onClick = { viewModel.pxboardSession.cancelMfaSetup() },
                        enabled = !portal.busy,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            viewModel.pxboardSession.enableMfa(mfaActionPwd, mfaActionCode) {
                                mfaActionPwd = ""
                                mfaActionCode = ""
                            }
                        },
                        enabled = !portal.busy && mfaActionPwd.isNotEmpty() && mfaActionCode.trim().length == 6,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("确认启用")
                    }
                }
            } else {
                Text(
                    "未启用 · 使用身份验证器生成 6 位动态验证码",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.pxboardSession.setupMfa() },
                    enabled = !portal.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("设置两步验证")
                }
            }
        }
    }

    if ("oauth" in state.nativeExtensions && (state.oauthProviders.isNotEmpty() || portal.oauthBindings.isNotEmpty())) {
        Section("第三方账号绑定") {
            Panel {
                val boundIds = portal.oauthBindings.map { it.providerId }.toSet()
                val needsPwd = portal.oauthBindings.any { it.requiresPassword }
                if (needsPwd) {
                    TextField(
                        value = unbindPwd,
                        onValueChange = { unbindPwd = it },
                        label = "解绑最后一个登录方式时需输入当前密码",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (portal.mfaEnabled) {
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = unbindCode,
                            onValueChange = { unbindCode = it },
                            label = "6 位动态验证码",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                for ((idx, binding) in portal.oauthBindings.withIndex()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                binding.providerName.ifEmpty { binding.providerId },
                                style = MiuixTheme.textStyles.title2,
                            )
                            Text(
                                binding.displayName.ifEmpty { "已绑定" },
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = "解除绑定",
                            onClick = {
                                if (binding.requiresPassword && unbindPwd.isEmpty()) {
                                    Toast.makeText(context, "解除最后一个登录绑定前请输入当前登录密码", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.pxboardSession.unbindOAuthBinding(binding.id, unbindPwd, unbindCode) {
                                        unbindPwd = ""
                                        unbindCode = ""
                                    }
                                }
                            },
                            enabled = !portal.busy,
                        )
                    }
                    if (idx != portal.oauthBindings.lastIndex) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    }
                }
                val unboundProviders = state.oauthProviders.filter { it.driver !in boundIds }
                if (portal.oauthBindPending) {
                    if (portal.oauthBindings.isNotEmpty()) Spacer(Modifier.height(12.dp))
                    Text("请在已打开的系统浏览器中完成授权…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.pxboardSession.cancelOAuthBind() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("取消绑定授权")
                    }
                } else if (unboundProviders.isNotEmpty()) {
                    if (portal.oauthBindings.isNotEmpty()) Spacer(Modifier.height(12.dp))
                    for ((idx, provider) in unboundProviders.withIndex()) {
                        Button(
                            onClick = { viewModel.pxboardSession.bindOAuthProvider(provider.driver) },
                            enabled = !portal.busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text("绑定 ${provider.label}")
                        }
                        if (idx != unboundProviders.lastIndex) {
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}
