package moe.telecom.xbclient

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun XbClientDialogs(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    OverlayDialog(
        show = state.rewardCreditedDialog,
        title = stringResource(R.string.reward_credited_title),
        summary = if (state.rewardCreditedContent.isBlank()) {
            stringResource(R.string.reward_credited_message)
        } else {
            state.rewardCreditedContent
        },
        onDismissRequest = viewModel::dismissRewardCreditedDialog
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.size(58.dp),
                insideMargin = PaddingValues(0.dp)
            ) {
                Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.ic_gift),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSecondaryContainer),
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::dismissRewardCreditedDialog,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
    OverlayDialog(
        show = state.updateAvailable,
        title = stringResource(R.string.update_title),
        summary = stringResource(R.string.update_message, BuildConfig.VERSION_NAME.removeSuffix(".debug"), state.latestReleaseVersion),
        onDismissRequest = viewModel::dismissUpdateDialog
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.common_later),
                onClick = viewModel::dismissUpdateDialog,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(12.dp))
            TextButton(
                text = stringResource(id = if (state.latestDownloadUrl.isEmpty()) R.string.update_open_release else R.string.update_download),
                onClick = { viewModel.openUpdatePage(context) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
    OverlayDialog(
        show = state.noticeDialog,
        title = stringResource(R.string.section_announcement),
        onDismissRequest = viewModel::dismissNotices
    ) {
        if (state.notices.isEmpty()) {
            Text(stringResource(id = if (state.noticesLoading) R.string.notice_loading else R.string.notice_empty))
        } else {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(state.notices) { notice ->
                    NoticeCard(notice)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(
            text = stringResource(R.string.common_close),
            onClick = viewModel::dismissNotices,
            modifier = Modifier.fillMaxWidth()
        )
    }
    OverlayDialog(
        show = state.nodeSwitchSheet,
        title = stringResource(id = if (state.nodeSwitchConnect) R.string.sheet_change_node else R.string.sheet_select_node),
        onDismissRequest = viewModel::dismissNodeSwitchDialog
    ) {
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            itemsIndexed(state.anyTlsNodes, key = { index, node -> "${node.displayName(index)}-$index" }) { index, node ->
                val visibleTestText = visibleNodeTestText(state.nodeTestResults[index])
                val tagsText = node.tags.joinToString(" · ").takeIf { it.isNotEmpty() }
                val supportingText = listOfNotNull(node.protocolLabel, tagsText, visibleTestText).joinToString(" · ")
                ArrowPreference(
                    title = node.displayName(index, stringResource(R.string.node_default_name, index + 1)),
                    summary = supportingText,
                    onClick = { viewModel.chooseNodeFromDialog(index) },
                    endActions = {
                        if (index == state.selectedNodeIndex) {
                            Text(stringResource(R.string.common_selected), color = MiuixTheme.colorScheme.primary)
                        }
                    }
                )
            }
        }
    }
}

@Composable
internal fun NoticeCard(notice: NoticeItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp)
    ) {
        if (notice.title.isNotBlank()) {
            Text(notice.title, style = MiuixTheme.textStyles.title2)
        }
        val content = plainNoticeText(notice.content)
        if (content.isNotBlank()) {
            if (notice.title.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
            }
            Text(content, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (notice.createdAt > 0L) {
            Spacer(Modifier.height(8.dp))
            Text(formatUnixTime(notice.createdAt), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
