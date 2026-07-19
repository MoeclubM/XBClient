package moe.telecom.xbclient

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun GiftCardsScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    Section("礼品卡 / 兑换码") {
        Panel {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("兑换码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.checkGiftCard(code) },
                    enabled = !state.giftCardChecking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.giftCardChecking) "查询中" else "查询")
                }
                Button(
                    onClick = { viewModel.redeemGiftCard(code) },
                    enabled = !state.giftCardRedeeming,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (state.giftCardRedeeming) "兑换中" else "兑换")
                }
            }
            val preview = state.giftCardPreview
            if (preview != null) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(preview.templateName, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${preview.typeName} · ${preview.statusName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(preview.rewardText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!preview.canRedeem) {
                    Spacer(Modifier.height(6.dp))
                    Text(preview.reason, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    Section("兑换记录") {
        Panel {
            when {
                state.giftCardHistoryLoading -> Text("正在加载兑换记录...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.giftCardHistory.isEmpty() -> Text("暂无兑换记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    for ((index, item) in state.giftCardHistory.withIndex()) {
                        Text(item.templateName, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOf(item.code, item.typeName).filter { it.isNotEmpty() }.joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(item.rewardsText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.inviteRewardsText.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text("邀请奖励：${item.inviteRewardsText}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.multiplierApplied != 1.0) {
                            Spacer(Modifier.height(2.dp))
                            Text("倍率 ${String.format(Locale.US, "%.2f", item.multiplierApplied)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (item.createdAt.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(item.createdAt, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (index != state.giftCardHistory.lastIndex) {
                            HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountSecurityScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    Section("修改密码") {
        Panel {
            OutlinedTextField(
                value = oldPassword,
                onValueChange = { oldPassword = it },
                label = { Text("旧密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("确认新密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.changePassword(oldPassword, newPassword, confirmPassword) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存新密码")
            }
        }
    }
    if (state.oauthProviders.isNotEmpty()) {
        Section("OAuth 绑定") {
            Panel {
                if (state.oauthBindingsLoading) {
                    Text("正在加载 OAuth 绑定状态...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    for ((index, provider) in state.oauthProviders.withIndex()) {
                        val binding = state.oauthBindings.firstOrNull { it.driver == provider.driver }
                        val bound = binding?.bound == true
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(provider.label, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (bound) binding?.identity?.ifEmpty { "已绑定" } ?: "已绑定" else "未绑定",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (bound) {
                                OutlinedButton(onClick = { viewModel.unbindOAuth(provider.driver) }) {
                                    Text("解绑")
                                }
                            } else {
                                Button(onClick = { viewModel.bindOAuth(provider.driver) }) {
                                    Text("绑定")
                                }
                            }
                        }
                        if (index != state.oauthProviders.lastIndex) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.refreshOAuthBindings(force = true, showLoading = true, showErrors = true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("刷新绑定状态")
                    }
                }
            }
        }
    }
}
