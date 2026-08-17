package moe.telecom.xbclient

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.text.TextUtils
import android.view.View
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val LanguageOptions = listOf(
    "" to "System",
    "zh-CN" to "中文",
    "en" to "English",
    "ja" to "日本語",
    "ru" to "Русский",
    "fa" to "فارسی"
)

internal val ThemeOptions = listOf(
    "" to R.string.theme_system,
    "light" to R.string.theme_light,
    "dark" to R.string.theme_dark
)

internal val MainTabScreens = setOf(PassScreen.NODES, PassScreen.PLANS, PassScreen.PROFILE, PassScreen.SETTINGS)

internal val OnboardingLanguageTitles = mapOf(
    "" to "Choose language\n选择语言",
    "zh-CN" to "选择语言\nChoose language",
    "en" to "Choose language",
    "ja" to "言語を選択\nChoose language",
    "ru" to "Выберите язык\nChoose language",
    "fa" to "انتخاب زبان\nChoose language"
)

internal val OnboardingLanguageSubtitles = mapOf(
    "" to "Please select a language.\n请选择语言。",
    "zh-CN" to "请选择语言。\nPlease select a language.",
    "en" to "Please select a language.",
    "ja" to "言語を選択してください。\nPlease select a language.",
    "ru" to "Выберите язык.\nPlease select a language.",
    "fa" to "لطفاً زبان را انتخاب کنید.\nPlease select a language."
)

@Composable
fun XbClientTheme(themeMode: String, content: @Composable () -> Unit) {
    val mode = when (themeMode) {
        "dark" -> ColorSchemeMode.MonetDark
        "light" -> ColorSchemeMode.MonetLight
        else -> ColorSchemeMode.MonetSystem
    }
    val controller = remember(mode) { ThemeController(mode) }
    MiuixTheme(controller = controller, content = content)
}

@Composable
internal fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRenderEffectSupported()) {
        return null
    }
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
internal fun BlurredBar(
    backdrop: LayerBackdrop?,
    content: @Composable () -> Unit
) {
    Box(
        modifier = if (backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.87f))
                    )
                )
            )
        } else {
            Modifier
        }
    ) {
        content()
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        SmallTitle(title)
        content()
    }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        content = content
    )
}

@Composable
internal fun PreferenceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        content = content
    )
}

@Composable
internal fun LanguageChooser(current: String, viewModel: XbClientViewModel) {
    val selectedIndex = LanguageOptions.indexOfFirst { it.first == current }.coerceAtLeast(0)
    OverlayDropdownPreference(
        title = stringResource(R.string.setting_language),
        summary = LanguageOptions[selectedIndex].second,
        items = LanguageOptions.map { it.second },
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { viewModel.setAppLanguage(LanguageOptions[it].first) }
    )
}

@Composable
internal fun ThemeChooser(current: String, appLanguage: String, viewModel: XbClientViewModel) {
    val themeOptions = ThemeOptions.map { it.first to themeOptionLabel(it.first, appLanguage) }
    val selectedIndex = themeOptions.indexOfFirst { it.first == current }.coerceAtLeast(0)
    OverlayDropdownPreference(
        title = stringResource(R.string.setting_theme),
        summary = themeOptions[selectedIndex].second,
        items = themeOptions.map { it.second },
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { viewModel.setThemeMode(themeOptions[it].first) }
    )
}

@Composable
internal fun EditPreference(
    title: String,
    value: String,
    onConfirm: (String) -> Unit,
    summary: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    var show by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    ArrowPreference(
        title = title,
        summary = summary ?: value.ifBlank { stringResource(R.string.common_unset) },
        onClick = {
            draft = value
            show = true
        },
        holdDownState = show
    )
    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = { show = false }
    ) {
        TextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(android.R.string.cancel),
                onClick = { show = false },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.common_confirm),
                onClick = {
                    onConfirm(draft)
                    show = false
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = MiuixTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

internal fun hasAuthFooterLinks() =
    BuildConfig.WEBSITE_URL.trim().isNotEmpty() ||
        BuildConfig.USER_AGREEMENT_URL.trim().isNotEmpty() ||
        BuildConfig.PRIVACY_POLICY_URL.trim().isNotEmpty()

internal fun selectedPackages(state: XbClientUiState): Set<String> =
    (if (state.appRuleMode == MODE_ALLOW) state.allowedApps else state.excludedApps)
        .split(Regex("[,;\\s]+"))
        .filter { it.isNotEmpty() }
        .toSet()

internal fun openBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is android.app.Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

internal fun effectiveLanguageTag(selected: String): String {
    if (selected.isNotEmpty()) {
        return selected
    }
    return when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "zh" -> "zh-CN"
        "ja" -> "ja"
        "ru" -> "ru"
        "fa", "per" -> "fa"
        "en" -> "en"
        else -> "en"
    }
}

internal fun localizedContext(context: Context, locale: Locale): Context {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return context.createConfigurationContext(configuration)
}

internal fun appLayoutDirection(locale: Locale): LayoutDirection =
    if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr

internal fun themeOptionLabel(mode: String, language: String): String {
    val primaryLanguage = effectiveLanguageTag(language).substringBefore("-")
    return when (primaryLanguage) {
        "zh" -> when (mode) {
            "light" -> "浅色"
            "dark" -> "深色"
            else -> "跟随系统"
        }
        "ja" -> when (mode) {
            "light" -> "ライト"
            "dark" -> "ダーク"
            else -> "システム"
        }
        "ru" -> when (mode) {
            "light" -> "Светлая"
            "dark" -> "Темная"
            else -> "Система"
        }
        "fa" -> when (mode) {
            "light" -> "روشن"
            "dark" -> "تاریک"
            else -> "سیستم"
        }
        else -> when (mode) {
            "light" -> "Light"
            "dark" -> "Dark"
            else -> "System"
        }
    }
}

internal fun plainNoticeText(value: String): String =
    value
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

internal fun visibleNodeTestText(text: String?): String? =
    text?.takeIf { it.isNotBlank() && it != "测试中" }

internal fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val restSeconds = seconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, restSeconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, restSeconds)
    }
}

internal fun formatMoney(amount: Int, symbol: String, unit: String): String =
    (symbol + String.format(Locale.US, "%.2f", amount / 100.0) + if (unit.isBlank()) "" else " $unit").trim()

internal fun formatUnixTime(value: Long): String =
    if (value <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value * 1000))

internal fun ticketLevelText(level: Int): String =
    when (level) {
        0 -> "低"
        1 -> "中"
        2 -> "高"
        else -> "等级 $level"
    }

internal fun ticketStatusText(status: Int): String =
    when (status) {
        0 -> "开启"
        1 -> "关闭"
        else -> "状态 $status"
    }

internal fun ticketReplyStatusText(status: Int): String =
    when (status) {
        0 -> "待回复"
        1 -> "已回复"
        else -> "回复状态 $status"
    }

@Composable
internal fun planPriceText(plan: PlanItem, symbol: String, unit: String, noPriceText: String): String {
    if (plan.prices.isEmpty()) {
        return noPriceText
    }
    val parts = mutableListOf<String>()
    for (price in plan.prices) {
        parts += "${planPriceLabel(price.field)} ${formatMoney(price.amount, symbol, unit)}"
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun planPriceLabel(field: String): String =
    stringResource(
        when (field) {
            "month_price" -> R.string.price_month
            "quarter_price" -> R.string.price_quarter
            "half_year_price" -> R.string.price_half_year
            "year_price" -> R.string.price_year
            "two_year_price" -> R.string.price_two_year
            "three_year_price" -> R.string.price_three_year
            "onetime_price" -> R.string.price_onetime
            "reset_price" -> R.string.price_reset
            else -> R.string.plan_price_unset
        }
    )

@Composable
internal fun rewardStatusText(status: String): String =
    when (status) {
        "credited" -> stringResource(R.string.reward_credited)
        "pending" -> stringResource(R.string.reward_pending)
        "failed" -> stringResource(R.string.reward_failed)
        else -> status
    }

internal val XbClientUiState.canHandleBack: Boolean
    get() = updateAvailable ||
        oauthWebViewUrl.isNotEmpty() ||
        !isLoggedIn && authMode == AuthMode.REGISTER ||
        isLoggedIn && screen !in MainTabScreens

internal fun AnimatedContentTransitionScope<*>.contentTransition() =
    (fadeIn(animationSpec = tween(180)) togetherWith
        fadeOut(animationSpec = tween(140))).using(SizeTransform(clip = false))

internal fun AnimatedContentTransitionScope<PassScreen>.screenTransition(): ContentTransform {
    val initialOrder = screenOrder(initialState)
    val targetOrder = screenOrder(targetState)
    return if (targetOrder >= initialOrder) {
        (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) +
            fadeIn(animationSpec = tween(160)) +
            scaleIn(initialScale = 0.985f, animationSpec = tween(220)) togetherWith
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) +
            fadeOut(animationSpec = tween(140)) +
            scaleOut(targetScale = 1.015f, animationSpec = tween(180))).using(SizeTransform(clip = false))
    } else {
        (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) +
            fadeIn(animationSpec = tween(160)) +
            scaleIn(initialScale = 0.985f, animationSpec = tween(220)) togetherWith
            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) +
            fadeOut(animationSpec = tween(140)) +
            scaleOut(targetScale = 1.015f, animationSpec = tween(180))).using(SizeTransform(clip = false))
    }
}

private fun screenOrder(screen: PassScreen): Int =
    when (screen) {
        PassScreen.NODES -> 0
        PassScreen.NODE_SELECT -> 1
        PassScreen.PLANS -> 2
        PassScreen.PROFILE -> 3
        PassScreen.GIFT_CARDS, PassScreen.ACCOUNT_SECURITY, PassScreen.INVITE_DETAILS, PassScreen.TRAFFIC_LOGS, PassScreen.TICKETS, PassScreen.TICKET_DETAIL -> 4
        PassScreen.SETTINGS -> 4
        PassScreen.APP_RULES, PassScreen.OPEN_SOURCE_LICENSES -> 5
    }

@Composable
internal fun screenTitle(screen: PassScreen): String =
    when (screen) {
        PassScreen.NODES -> stringResource(R.string.nav_home)
        PassScreen.PLANS -> stringResource(R.string.nav_plans)
        PassScreen.PROFILE -> stringResource(R.string.nav_profile)
        PassScreen.SETTINGS -> stringResource(R.string.common_settings)
        PassScreen.NODE_SELECT -> stringResource(R.string.section_available_nodes)
        PassScreen.APP_RULES -> stringResource(R.string.section_app_rules)
        PassScreen.OPEN_SOURCE_LICENSES -> stringResource(R.string.about_open_source_licenses)
        PassScreen.GIFT_CARDS -> stringResource(R.string.page_gift_cards)
        PassScreen.ACCOUNT_SECURITY -> stringResource(R.string.page_account_security)
        PassScreen.INVITE_DETAILS -> stringResource(R.string.page_invite_details)
        PassScreen.TRAFFIC_LOGS -> stringResource(R.string.page_traffic_logs)
        PassScreen.TICKETS -> stringResource(R.string.page_tickets)
        PassScreen.TICKET_DETAIL -> stringResource(R.string.page_ticket_detail)
    }
