package moe.telecom.xbclient

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.telecom.neto.sdk.NetoPreferenceCard
import moe.telecom.neto.sdk.NetoSettingsPane
import moe.telecom.neto.sdk.NetoSnapshot
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScreen(
    state: XbClientUiState,
    snapshot: NetoSnapshot,
    viewModel: XbClientViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val logs by viewModel.netoClient.logs.collectAsStateWithLifecycle()
    val installedApps by viewModel.netoClient.installedApps.collectAsStateWithLifecycle()

    NetoSettingsPane(
        snapshot = snapshot,
        logs = logs,
        installedApps = installedApps,
        client = viewModel.netoClient,
        modifier = modifier,
        extraHeaderSlot = {
            NetoPreferenceCard {
                OverlayDropdownPreference(
                    title = stringResource(R.string.setting_language),
                    summary = LanguageOptions.firstOrNull { it.first == state.appLanguage }?.second ?: LanguageOptions[0].second,
                    items = LanguageOptions.map { it.second },
                    startAction = {
                        Icon(Icons.Rounded.Language, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
                    },
                    selectedIndex = LanguageOptions.indexOfFirst { it.first == state.appLanguage }.coerceAtLeast(0),
                    onSelectedIndexChange = { viewModel.setAppLanguage(LanguageOptions[it].first) },
                )
                SwitchPreference(
                    title = stringResource(R.string.setting_theme_blur),
                    summary = stringResource(R.string.setting_theme_blur_summary),
                    checked = state.enableBlur,
                    onCheckedChange = viewModel::setEnableBlur,
                )
                SwitchPreference(
                    title = stringResource(R.string.setting_theme_floating_bar),
                    summary = stringResource(R.string.setting_theme_floating_bar_summary),
                    checked = state.floatingBottomBar,
                    onCheckedChange = viewModel::setFloatingBottomBar,
                )
                ArrowPreference(
                    title = stringResource(R.string.setting_reset_onboarding),
                    summary = stringResource(R.string.setting_reset_onboarding_summary),
                    startAction = {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
                    },
                    onClick = {
                        viewModel.resetOnboarding()
                        val intent = Intent(context, AuthActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        if (context !is android.app.Activity) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                )
            }
        },
        extraFooterSlot = {
            SmallTitle(stringResource(R.string.app_name))
            NetoPreferenceCard {
                ArrowPreference(
                    title = stringResource(R.string.app_name),
                    summary = stringResource(R.string.about_version, BuildConfig.VERSION_NAME.removeSuffix(".debug")),
                    startAction = {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
                    },
                )
                val links = listOf(
                    R.string.about_website to BuildConfig.WEBSITE_URL.trim(),
                    R.string.about_user_agreement to BuildConfig.USER_AGREEMENT_URL.trim(),
                    R.string.about_privacy_policy to BuildConfig.PRIVACY_POLICY_URL.trim(),
                    R.string.about_source_code to "https://github.com/MoeclubM/XBClient",
                ).filter { it.second.isNotEmpty() }
                for ((label, url) in links) {
                    ArrowPreference(title = stringResource(label), onClick = { openBrowser(context, url) })
                }
                ArrowPreference(
                    title = stringResource(R.string.about_open_source_licenses),
                    startAction = {
                        Icon(Icons.Rounded.Article, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
                    },
                    onClick = { viewModel.openScreen(PassScreen.OPEN_SOURCE_LICENSES) },
                )
            }
        },
    )
}

@Composable
internal fun OpenSourceLicensesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val licenses = remember(context) {
        context.resources.openRawResource(R.raw.open_source_licenses)
            .bufferedReader()
            .use { it.readText() }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        overscrollEffect = null
    ) {
        item {
            Panel {
                Text(
                    licenses,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}
