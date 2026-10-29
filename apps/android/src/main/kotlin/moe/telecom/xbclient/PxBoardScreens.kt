package moe.telecom.xbclient

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun PxBoardLogin(state: XbClientUiState, viewModel: XbClientViewModel) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Button(onClick = viewModel::loginPxBoard, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary(), enabled = !state.nativeLoginPending) {
            Text(stringResource(if (state.nativeLoginPending) R.string.panel_authorizing else R.string.auth_login))
        }
        if (state.nativeLoginPending) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = viewModel::cancelPxBoardLogin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.panel_cancel)) }
        }
    }
}

@Composable
internal fun PxBoardAccount(state: XbClientUiState, viewModel: XbClientViewModel, plans: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        if (!plans) {
            Text(state.userEmail)
            Spacer(Modifier.height(12.dp))
            Text(state.subscriptionSummary)
            Spacer(Modifier.height(20.dp))
        }
        Button(onClick = { viewModel.openPxBoardPortal(if (plans) "/user/plans" else "/user") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(stringResource(if (plans) R.string.nav_plans else R.string.panel_user_center))
        }
        if (!plans) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_logout)) }
        }
    }
}
