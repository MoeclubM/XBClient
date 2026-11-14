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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    "system" to R.string.theme_system,
    "light" to R.string.theme_light,
    "dark" to R.string.theme_dark
)

internal val MainTabScreens = setOf(PassScreen.HOME, PassScreen.NODES, PassScreen.CONNECTIONS, PassScreen.RULES, PassScreen.PLANS, PassScreen.PROFILE)

@Composable
internal fun rememberBlurBackdrop(enabled: Boolean = true): LayerBackdrop? {
    if (!enabled || !isRenderEffectSupported()) {
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
    shape: androidx.compose.ui.graphics.Shape = RectangleShape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.then(if (backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.72f))
                    )
                )
            )
        } else {
            Modifier
        })
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
internal fun AuthSegmentBar(
    items: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.size <= 1) return
    val containerShape = RoundedCornerShape(11.dp)
    val itemShape = RoundedCornerShape(8.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(containerShape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((key, label) in items) {
            val active = key == selectedKey
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(itemShape)
                    .then(
                        if (active) {
                            Modifier
                                .background(MiuixTheme.colorScheme.surface)
                                .border(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.22f), itemShape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun AuthFooterLinks(context: Context, modifier: Modifier = Modifier) {
    val links = listOf(
        R.string.about_website to BuildConfig.WEBSITE_URL.trim(),
        R.string.about_user_agreement to BuildConfig.USER_AGREEMENT_URL.trim(),
        R.string.about_privacy_policy to BuildConfig.PRIVACY_POLICY_URL.trim()
    ).filter { it.second.isNotEmpty() }
    if (links.isEmpty()) {
        return
    }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        for ((index, link) in links.withIndex()) {
            Text(
                text = stringResource(link.first),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable { openBrowser(context, link.second) },
            )
            if (index != links.lastIndex) {
                Text(
                    text = " · ",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
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
        PassScreen.HOME -> 0
        PassScreen.NODES -> 1
        PassScreen.CONNECTIONS -> 2
        PassScreen.RULES -> 3
        PassScreen.PLANS -> 4
        PassScreen.PROFILE -> 5
        PassScreen.GIFT_CARDS, PassScreen.ACCOUNT_SECURITY, PassScreen.INVITE_DETAILS, PassScreen.TRAFFIC_LOGS, PassScreen.TICKETS, PassScreen.TICKET_DETAIL, PassScreen.SUB_LINKS -> 6
        PassScreen.SETTINGS -> 6
        PassScreen.OPEN_SOURCE_LICENSES -> 7
    }

@Composable
internal fun screenTitle(screen: PassScreen): String =
    when (screen) {
        PassScreen.HOME -> ""
        PassScreen.NODES -> stringResource(R.string.nav_nodes)
        PassScreen.CONNECTIONS -> stringResource(R.string.nav_connections)
        PassScreen.RULES -> stringResource(R.string.nav_rules)
        PassScreen.PLANS -> stringResource(R.string.nav_plans)
        PassScreen.PROFILE -> stringResource(R.string.nav_profile)
        PassScreen.SETTINGS -> stringResource(R.string.common_settings)
        PassScreen.OPEN_SOURCE_LICENSES -> stringResource(R.string.about_open_source_licenses)
        PassScreen.GIFT_CARDS -> if (PxBoardApi.enabled) "账单与兑换" else stringResource(R.string.page_gift_cards)
        PassScreen.ACCOUNT_SECURITY -> stringResource(R.string.page_account_security)
        PassScreen.INVITE_DETAILS -> if (PxBoardApi.enabled) "推广返佣" else stringResource(R.string.page_invite_details)
        PassScreen.TRAFFIC_LOGS -> stringResource(R.string.page_traffic_logs)
        PassScreen.TICKETS -> stringResource(R.string.page_tickets)
        PassScreen.TICKET_DETAIL -> stringResource(R.string.page_ticket_detail)
        PassScreen.SUB_LINKS -> "订阅链接"
    }
