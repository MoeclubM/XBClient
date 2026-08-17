package moe.telecom.xbclient

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun PlansScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    RewardAdSection(
        title = stringResource(R.string.reward_plan_title),
        enabled = state.planRewardAdEnabled,
        scene = REWARD_SCENE_PLAN,
        state = state,
        viewModel = viewModel
    )
    if (state.plansLoading) {
        Text(
            stringResource(R.string.plans_loading),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 28.dp)
        )
    } else if (state.plans.isEmpty()) {
        Text(
            stringResource(R.string.plans_empty),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 28.dp)
        )
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .animateContentSize(animationSpec = tween(180)),
        insideMargin = PaddingValues(18.dp),
        pressFeedbackType = if (paymentEnabled) PressFeedbackType.Sink else PressFeedbackType.None,
        showIndication = paymentEnabled,
        onClick = if (paymentEnabled) onOpenPayment else null
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(plan.name, style = MiuixTheme.textStyles.title2)
                if (plan.transferEnable > 0.0) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.plan_traffic, formatTrafficGb(plan.transferEnable)), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (paymentEnabled || plan.prices.isEmpty()) {
                Spacer(Modifier.width(12.dp))
                Card(
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
                    cornerRadius = 50.dp,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        planPriceText(plan, currencySymbol, currencyUnit, noPriceText),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        val content = plan.content.trim()
        if (content.isNotEmpty() && !content.startsWith("[") && !content.startsWith("{")) {
            Spacer(Modifier.height(12.dp))
            Text(content, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (!paymentEnabled && plan.prices.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            for ((index, price) in plan.prices.withIndex()) {
                Button(onClick = { onBalancePurchase(price) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${planPriceLabel(price.field)} ${formatMoney(price.amount, currencySymbol, currencyUnit)}")
                }
                if (index != plan.prices.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
internal fun RewardAdSection(
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 18.dp)
            .animateContentSize(animationSpec = tween(180)),
        insideMargin = PaddingValues(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Card(
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer),
                cornerRadius = 18.dp,
                modifier = Modifier.size(50.dp),
                insideMargin = PaddingValues(0.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.ic_gift),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimaryContainer),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Text(title, style = MiuixTheme.textStyles.title2, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { viewModel.requestRewardAd(scene) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reward_watch))
        }
        if (logs.isNotEmpty()) {
            val visibleLogs = logs.take(3)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.reward_recent), style = MiuixTheme.textStyles.title2)
            Spacer(Modifier.height(8.dp))
            for ((index, log) in visibleLogs.withIndex()) {
                val statusColor = when (log.status) {
                    "credited" -> MiuixTheme.colorScheme.primary
                    "failed" -> MiuixTheme.colorScheme.error
                    else -> MiuixTheme.colorScheme.secondary
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(log.rewardContent, style = MiuixTheme.textStyles.title2)
                        Spacer(Modifier.height(2.dp))
                        Text(formatUnixTime(log.createdAt), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (log.status == "failed" && log.error.isNotEmpty()) {
                            Text(log.error, color = MiuixTheme.colorScheme.error)
                        }
                    }
                    Card(
                        colors = CardDefaults.defaultColors(color = statusColor.copy(alpha = 0.12f)),
                        cornerRadius = 50.dp,
                        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            rewardStatusText(log.status),
                            style = MiuixTheme.textStyles.body2,
                            color = statusColor
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
