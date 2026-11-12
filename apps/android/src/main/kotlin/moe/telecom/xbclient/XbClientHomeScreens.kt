package moe.telecom.xbclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.telecom.neto.sdk.NetoConnectPane
import moe.telecom.neto.sdk.NetoDashboardPane
import moe.telecom.neto.sdk.NetoNodesPane
import moe.telecom.neto.sdk.NetoSnapshot
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConnectScreen(
    state: XbClientUiState,
    snapshot: NetoSnapshot,
    actionError: String?,
    viewModel: XbClientViewModel,
    modifier: Modifier = Modifier,
) {
    NetoConnectPane(
        snapshot = snapshot,
        onToggleConnect = viewModel::toggleVpn,
        onSelectRoutingMode = viewModel.netoClient::setRoutingMode,
        onOpenNodes = { viewModel.openScreen(PassScreen.NODES) },
        modifier = modifier,
        actionError = actionError,
        blockedContent = if (state.subscriptionBlocked) {
            { SubscriptionBlockedCard(state, viewModel) }
        } else {
            null
        },
    )
}

@Composable
internal fun DashboardScreen(
    state: XbClientUiState,
    snapshot: NetoSnapshot,
    viewModel: XbClientViewModel,
    modifier: Modifier = Modifier,
) {
    NetoDashboardPane(
        snapshot = snapshot,
        client = viewModel.netoClient,
        onOpenNodes = { viewModel.openScreen(PassScreen.NODES) },
        onOpenProfiles = { viewModel.openScreen(PassScreen.PROFILE) },
        modifier = modifier,
        extraHeaderSlot = {
            CommercialAccountHeaderCard(state, viewModel)
        },
    )
}

@Composable
internal fun NodesScreen(
    state: XbClientUiState,
    snapshot: NetoSnapshot,
    viewModel: XbClientViewModel,
    modifier: Modifier = Modifier,
) {
    NetoNodesPane(
        snapshot = snapshot,
        client = viewModel.netoClient,
        modifier = modifier,
        blockedContent = if (state.subscriptionBlocked) {
            { SubscriptionBlockedCard(state, viewModel) }
        } else {
            null
        },
    )
}

@Composable
private fun CommercialAccountHeaderCard(
    state: XbClientUiState,
    viewModel: XbClientViewModel,
) {
    val summaryParts = buildList {
        if (state.subscriptionSummary.isNotBlank()) add(state.subscriptionSummary)
        add(stringResource(R.string.balance_amount, formatMoney(state.balance, state.currencySymbol, state.currencyUnit)))
    }.joinToString(" · ")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        ArrowPreference(
            title = state.userEmail.ifBlank { stringResource(R.string.app_name) },
            summary = summaryParts,
            onClick = {
                val target = if (!PxBoardApi.enabled || "commerce" in state.nativeExtensions) {
                    PassScreen.PLANS
                } else {
                    PassScreen.PROFILE
                }
                viewModel.openScreen(target)
            },
        )
    }
}

@Composable
private fun SubscriptionBlockedCard(
    state: XbClientUiState,
    viewModel: XbClientViewModel,
) {
    val blockTitle = stringResource(
        id = when (state.subscriptionBlockReason) {
            "suspended" -> R.string.panel_account_suspended
            SUBSCRIPTION_BLOCK_NO_PLAN -> R.string.subscription_no_plan_title
            SUBSCRIPTION_BLOCK_TRAFFIC -> R.string.subscription_traffic_exceeded_title
            else -> R.string.subscription_expired_title
        }
    )
    val blockDescription = stringResource(
        id = when (state.subscriptionBlockReason) {
            "suspended" -> R.string.panel_account_suspended_body
            SUBSCRIPTION_BLOCK_NO_PLAN -> R.string.subscription_no_plan_body
            SUBSCRIPTION_BLOCK_TRAFFIC -> R.string.subscription_traffic_exceeded_body
            else -> R.string.subscription_expired_body
        }
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(18.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(blockTitle, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(blockDescription, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (state.subscriptionSummary.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(state.subscriptionSummary, style = MiuixTheme.textStyles.title2)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val target = when {
                        !PxBoardApi.enabled || "commerce" in state.nativeExtensions -> PassScreen.PLANS
                        "redeem" in state.nativeExtensions -> PassScreen.GIFT_CARDS
                        else -> PassScreen.PROFILE
                    }
                    viewModel.openScreen(target)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(stringResource(R.string.subscription_redeem_button))
            }
        }
    }
}
