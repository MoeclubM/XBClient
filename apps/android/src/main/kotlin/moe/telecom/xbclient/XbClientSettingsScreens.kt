package moe.telecom.xbclient

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    val context = LocalContext.current
    var nodeDns by rememberSaveable(state.nodeDns) { mutableStateOf(state.nodeDns) }
    var overseasDns by rememberSaveable(state.overseasDns) { mutableStateOf(state.overseasDns) }
    var directDns by rememberSaveable(state.directDns) { mutableStateOf(state.directDns) }
    var vpnDnsMode by rememberSaveable(state.vpnDnsMode) { mutableStateOf(state.vpnDnsMode) }
    var virtualDnsPool by rememberSaveable(state.virtualDnsPool) { mutableStateOf(state.virtualDnsPool) }
    var nodeTestTarget by rememberSaveable(state.nodeTestTarget) { mutableStateOf(state.nodeTestTarget) }
    var customRouteConfigYaml by rememberSaveable(state.customRouteConfigYaml, state.routeConfigYaml) { mutableStateOf(state.customRouteConfigYaml.ifBlank { state.routeConfigYaml }) }
    var geoipDir by rememberSaveable(state.geoipDir) { mutableStateOf(state.geoipDir) }
    val selectedCount = selectedPackages(state).size
    val dnsModes = listOf(
        DNS_MODE_OVER_TCP to stringResource(R.string.dns_mode_over_tcp),
        DNS_MODE_VIRTUAL to stringResource(R.string.dns_mode_virtual),
        DNS_MODE_DIRECT to stringResource(R.string.dns_mode_direct)
    )
    val dnsModeIndex = dnsModes.indexOfFirst { it.first == vpnDnsMode }.coerceAtLeast(0)
    PreferenceCard {
        OverlayDropdownPreference(
            title = stringResource(R.string.setting_language),
            summary = LanguageOptions.firstOrNull { it.first == state.appLanguage }?.second ?: LanguageOptions[0].second,
            items = LanguageOptions.map { it.second },
            startAction = {
                Icon(Icons.Rounded.Language, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            },
            selectedIndex = LanguageOptions.indexOfFirst { it.first == state.appLanguage }.coerceAtLeast(0),
            onSelectedIndexChange = { viewModel.setAppLanguage(LanguageOptions[it].first) }
        )
        ArrowPreference(
            title = stringResource(R.string.page_theme),
            summary = stringResource(R.string.setting_theme_summary),
            startAction = {
                Icon(Icons.Rounded.Palette, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            },
            onClick = { viewModel.openScreen(PassScreen.THEME) }
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
            }
        )
    }
    PreferenceCard {
        ArrowPreference(
            title = stringResource(R.string.section_app_rules),
            summary = if (selectedCount == 0) {
                stringResource(R.string.app_rules_none_selected)
            } else {
                stringResource(R.string.app_rules_selected_count, selectedCount)
            },
            startAction = {
                Icon(Icons.Rounded.Apps, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            },
            onClick = { viewModel.openScreen(PassScreen.APP_RULES) }
        )
        ArrowPreference(
            title = stringResource(R.string.common_clear_selection),
            onClick = viewModel::clearSelectedApps
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Rounded.AltRoute, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            Text(stringResource(R.string.section_traffic_rules), style = MiuixTheme.textStyles.title2)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.customRouteConfigYaml.isNotBlank()) stringResource(R.string.traffic_rules_custom_enabled)
            else if (state.routeRuleCount > 0) stringResource(R.string.traffic_rules_count, state.routeRuleCount)
            else stringResource(R.string.traffic_rules_none),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        if (state.routeRulesPreview.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            for (rule in state.routeRulesPreview.take(6)) {
                Text(rule, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.height(10.dp))
        TextField(
            value = customRouteConfigYaml,
            onValueChange = { customRouteConfigYaml = it },
            label = stringResource(R.string.traffic_rules_config_label),
            useLabelAsPlaceholder = true,
            minLines = 6,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        TextField(
            value = geoipDir,
            onValueChange = { geoipDir = it },
            label = stringResource(R.string.geoip_dir_label),
            useLabelAsPlaceholder = true,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.traffic_rules_config_help), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.saveRouteConfigYaml(customRouteConfigYaml, geoipDir) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(stringResource(R.string.common_save_settings))
            }
            Button(
                onClick = {
                    customRouteConfigYaml = ""
                    viewModel.saveRouteConfigYaml("", geoipDir)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.common_clear_selection))
            }
        }
    }
    PreferenceCard {
        OverlayDropdownPreference(
            title = stringResource(R.string.dns_mode_label),
            summary = dnsModes[dnsModeIndex].second,
            items = dnsModes.map { it.second },
            startAction = {
                Icon(Icons.Rounded.Dns, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            },
            selectedIndex = dnsModeIndex,
            onSelectedIndexChange = {
                vpnDnsMode = dnsModes[it].first
                viewModel.saveDnsAndTestSettings(nodeDns, overseasDns, directDns, nodeTestTarget, vpnDnsMode, virtualDnsPool)
            }
        )
        EditPreference(title = stringResource(R.string.dns_node_label), value = nodeDns, onConfirm = {
            nodeDns = it
            viewModel.saveDnsAndTestSettings(it, overseasDns, directDns, nodeTestTarget, vpnDnsMode, virtualDnsPool)
        })
        EditPreference(title = stringResource(R.string.dns_overseas_label), value = overseasDns, onConfirm = {
            overseasDns = it
            viewModel.saveDnsAndTestSettings(nodeDns, it, directDns, nodeTestTarget, vpnDnsMode, virtualDnsPool)
        })
        EditPreference(title = stringResource(R.string.dns_direct_label), value = directDns, onConfirm = {
            directDns = it
            viewModel.saveDnsAndTestSettings(nodeDns, overseasDns, it, nodeTestTarget, vpnDnsMode, virtualDnsPool)
        })
        EditPreference(
            title = stringResource(R.string.dns_virtual_pool_label),
            value = virtualDnsPool,
            onConfirm = {
                virtualDnsPool = it
                viewModel.saveDnsAndTestSettings(nodeDns, overseasDns, directDns, nodeTestTarget, vpnDnsMode, it)
            }
        )
        SwitchPreference(
            title = stringResource(R.string.enable_ipv6),
            checked = state.vpnIpv6Enabled,
            onCheckedChange = viewModel::setIpv6Enabled
        )
        EditPreference(
            title = stringResource(R.string.node_test_target_label),
            value = nodeTestTarget,
            onConfirm = {
                nodeTestTarget = it
                viewModel.saveDnsAndTestSettings(nodeDns, overseasDns, directDns, it, vpnDnsMode, virtualDnsPool)
            }
        )
    }
    PreferenceCard {
        ArrowPreference(
            title = stringResource(R.string.app_name),
            summary = stringResource(R.string.about_version, BuildConfig.VERSION_NAME.removeSuffix(".debug")),
            startAction = {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            }
        )
        val links = listOf(
            R.string.about_source_code to "https://github.com/MoeclubM/XBClient",
            R.string.about_website to BuildConfig.WEBSITE_URL.trim(),
            R.string.about_user_agreement to BuildConfig.USER_AGREEMENT_URL.trim(),
            R.string.about_privacy_policy to BuildConfig.PRIVACY_POLICY_URL.trim()
        ).filter { it.second.isNotEmpty() }
        for ((label, url) in links) {
            ArrowPreference(title = stringResource(label), onClick = { openBrowser(context, url) })
        }
        ArrowPreference(
            title = stringResource(R.string.about_open_source_licenses),
            startAction = {
                Icon(Icons.Rounded.Article, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            },
            onClick = { viewModel.openScreen(PassScreen.OPEN_SOURCE_LICENSES) }
        )
    }
}

@Composable
internal fun ThemeSettingsScreen(state: XbClientUiState, viewModel: XbClientViewModel) {
    PreferenceCard {
        OverlayDropdownPreference(
            title = stringResource(R.string.setting_theme),
            summary = themeOptionLabel(state.themeMode, state.appLanguage),
            items = ThemeOptions.map { themeOptionLabel(it.first, state.appLanguage) },
            startAction = {
                Icon(Icons.Rounded.Palette, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.padding(end = 6.dp))
            },
            selectedIndex = ThemeOptions.indexOfFirst { it.first == state.themeMode }.coerceAtLeast(0),
            onSelectedIndexChange = { viewModel.setThemeMode(ThemeOptions[it].first) }
        )
    }
    PreferenceCard {
        SwitchPreference(
            title = stringResource(R.string.setting_theme_blur),
            summary = stringResource(R.string.setting_theme_blur_summary),
            checked = state.enableBlur,
            onCheckedChange = viewModel::setEnableBlur
        )
        SwitchPreference(
            title = stringResource(R.string.setting_theme_floating_bar),
            summary = stringResource(R.string.setting_theme_floating_bar_summary),
            checked = state.floatingBottomBar,
            onCheckedChange = viewModel::setFloatingBottomBar
        )
    }
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

@Composable
internal fun AppRulesScreen(state: XbClientUiState, viewModel: XbClientViewModel, modifier: Modifier = Modifier) {
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
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        overscrollEffect = null
    ) {
        item {
            PreferenceCard {
                OverlayDropdownPreference(
                    title = stringResource(R.string.section_app_rules),
                    summary = stringResource(id = if (state.appRuleMode == MODE_ALLOW) R.string.app_rules_allow_desc else R.string.app_rules_exclude_desc),
                    items = listOf(stringResource(R.string.mode_exclude), stringResource(R.string.mode_allow)),
                    selectedIndex = if (state.appRuleMode == MODE_ALLOW) 1 else 0,
                    onSelectedIndexChange = { viewModel.switchAppRuleMode(if (it == 1) MODE_ALLOW else MODE_EXCLUDE) }
                )
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                TextField(
                    value = state.appSearchQuery,
                    onValueChange = viewModel::setAppSearchQuery,
                    label = stringResource(R.string.search_app_label),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.app_rules_selected_count, packages.size), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::clearSelectedApps, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_clear_selection))
                }
            }
        }
        items(apps, key = { it.packageName }) { app ->
            val selected = packages.contains(app.packageName)
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                CheckboxPreference(
                    title = app.label,
                    summary = app.packageName,
                    checked = selected,
                    onCheckedChange = { viewModel.setAppSelected(app.packageName, it) }
                )
            }
        }
    }
}
