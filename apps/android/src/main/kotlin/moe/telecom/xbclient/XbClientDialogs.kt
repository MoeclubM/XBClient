package moe.telecom.xbclient

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun XbClientDialogs(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    OverlayDialog(
        show = state.rewardAdLoading,
        title = stringResource(R.string.reward_watch),
        summary = stringResource(R.string.reward_ad_loading),
        onDismissRequest = { viewModel.cancelRewardAdRequest() }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = { viewModel.cancelRewardAdRequest() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
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
        summary = buildString {
            append(stringResource(R.string.update_message, BuildConfig.VERSION_NAME.removeSuffix(".debug"), state.latestReleaseVersion))
            if (state.latestReleaseNotes.isNotBlank()) {
                append("\n\n")
                append(state.latestReleaseNotes)
            }
        },
        onDismissRequest = viewModel::dismissUpdateDialog
    ) {
        if (state.downloadingUpdate || state.updateDownloadError.isNotEmpty()) {
            if (state.downloadingUpdate) {
                val frac = ((state.updateDownloadPercent ?: 8).coerceIn(4, 100)) / 100f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.2f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(frac)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MiuixTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.updateDownloadStatus.ifEmpty { "正在下载更新…" },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
            } else {
                Text(
                    text = "下载失败：${state.updateDownloadError}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = if (state.downloadingUpdate) "后台下载" else stringResource(R.string.common_later),
                onClick = viewModel::dismissUpdateDialog,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(12.dp))
            TextButton(
                text = if (state.downloadingUpdate) "下载中…" else stringResource(id = if (state.latestDownloadUrl.isEmpty()) R.string.update_open_release else R.string.update_download),
                onClick = { viewModel.openUpdatePage(context) },
                modifier = Modifier.weight(1f),
                enabled = !state.downloadingUpdate,
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
