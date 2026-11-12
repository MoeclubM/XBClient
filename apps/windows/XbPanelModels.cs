using System.Globalization;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace XBClient.Windows;

public sealed class CommercialPeriod
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    [JsonPropertyName("price_cents")] public long PriceCents { get; set; }
}

public sealed class CommercialPlan
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
    [JsonPropertyName("description")] public string Description { get; set; } = "";
    [JsonPropertyName("quota_text")] public string QuotaText { get; set; } = "";
    [JsonPropertyName("periods")] public List<CommercialPeriod> Periods { get; set; } = [];
}

public sealed class PaymentChannel
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("name")] public string Name { get; set; } = "";
}

public sealed class NoticeEntry
{
    [JsonPropertyName("id")] public long Id { get; set; }
    [JsonPropertyName("title")] public string Title { get; set; } = "";
    [JsonPropertyName("content")] public string Content { get; set; } = "";
    [JsonPropertyName("created_at")] public long CreatedAt { get; set; }
}

public sealed class TicketMessage
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("is_me")] public bool IsMe { get; set; }
    [JsonPropertyName("author")] public string Author { get; set; } = "";
    [JsonPropertyName("message")] public string Message { get; set; } = "";
    [JsonPropertyName("created_at")] public string CreatedAt { get; set; } = "";
}

public sealed class TicketEntry
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("subject")] public string Subject { get; set; } = "";
    [JsonPropertyName("status_text")] public string StatusText { get; set; } = "";
    [JsonPropertyName("closed")] public bool Closed { get; set; }
    [JsonPropertyName("updated_at")] public string UpdatedAt { get; set; } = "";
    [JsonPropertyName("messages")] public List<TicketMessage> Messages { get; set; } = [];
}

public sealed class OAuthProviderEntry
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
}

public sealed class PanelStateSnapshot
{
    [JsonPropertyName("logged_in")] public bool LoggedIn { get; set; }
    [JsonPropertyName("panel_type")] public string PanelType { get; set; } = "xboard";
    [JsonPropertyName("app_name")] public string AppName { get; set; } = "XBClient";
    [JsonPropertyName("base_url")] public string BaseUrl { get; set; } = "";
    [JsonPropertyName("email")] public string Email { get; set; } = "";
    [JsonPropertyName("balance")] public long Balance { get; set; }
    [JsonPropertyName("commission_balance")] public long CommissionBalance { get; set; }
    [JsonPropertyName("currency_symbol")] public string CurrencySymbol { get; set; } = "";
    [JsonPropertyName("currency_unit")] public string CurrencyUnit { get; set; } = "";
    [JsonPropertyName("payment_enabled")] public bool PaymentEnabled { get; set; }
    [JsonPropertyName("extensions")] public List<string> Extensions { get; set; } = [];
    [JsonPropertyName("subscribe_url")] public string SubscribeUrl { get; set; } = "";
    [JsonPropertyName("subscription_summary")] public string SubscriptionSummary { get; set; } = "";
    [JsonPropertyName("block_reason")] public string BlockReason { get; set; } = "";
    [JsonPropertyName("traffic_used_bytes")] public ulong TrafficUsedBytes { get; set; }
    [JsonPropertyName("traffic_total_bytes")] public ulong TrafficTotalBytes { get; set; }
    [JsonPropertyName("expired_at_unix")] public long ExpiredAtUnix { get; set; }
    [JsonPropertyName("plans")] public List<CommercialPlan> Plans { get; set; } = [];
    [JsonPropertyName("payment_channels")] public List<PaymentChannel> PaymentChannels { get; set; } = [];
    [JsonPropertyName("notices")] public List<NoticeEntry> Notices { get; set; } = [];
    [JsonPropertyName("tickets")] public List<TicketEntry> Tickets { get; set; } = [];
    [JsonPropertyName("invite_codes")] public List<string> InviteCodes { get; set; } = [];
    [JsonPropertyName("referral_url")] public string ReferralUrl { get; set; } = "";
    [JsonPropertyName("invite_commission_rate")] public int InviteCommissionRate { get; set; }
    [JsonPropertyName("website_url")] public string WebsiteUrl { get; set; } = "";
    [JsonPropertyName("privacy_policy_url")] public string PrivacyPolicyUrl { get; set; } = "";
    [JsonPropertyName("user_agreement_url")] public string UserAgreementUrl { get; set; } = "";
    [JsonPropertyName("oauth_providers")] public List<OAuthProviderEntry> OAuthProviders { get; set; } = [];
    [JsonPropertyName("email_verify_required")] public bool EmailVerifyRequired { get; set; }
    [JsonPropertyName("browser_login_pending")] public bool BrowserLoginPending { get; set; }
    [JsonPropertyName("last_error")] public string? LastError { get; set; }

    [JsonIgnore]
    public bool IsPxBoard => string.Equals(PanelType, "pxboard", StringComparison.OrdinalIgnoreCase);

    [JsonIgnore]
    public bool ShowPlansTab => !IsPxBoard || Extensions.Contains("commerce");
}

public static class XbFormat
{
    public static string Money(long cents, string symbol, string unit)
    {
        var amount = cents / 100.0;
        var num = amount.ToString("F2", CultureInfo.InvariantCulture);
        if (!string.IsNullOrWhiteSpace(symbol)) return $"{symbol.Trim()}{num}";
        if (!string.IsNullOrWhiteSpace(unit)) return $"{num} {unit.Trim()}";
        return $"¥{num}";
    }

    public static string PlainText(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var s = Regex.Replace(raw, @"<br\s*/?>|</p>|</li>", "\n", RegexOptions.IgnoreCase);
        s = Regex.Replace(s, @"<[^>]+>", "");
        return System.Net.WebUtility.HtmlDecode(s).Trim();
    }

    public static string UnixDate(long unixSec)
    {
        if (unixSec <= 0) return "长期有效";
        return DateTimeOffset.FromUnixTimeSeconds(unixSec).ToLocalTime().ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);
    }

    public static (string Title, string Body) DescribeBlockReason(string reason)
    {
        return reason switch
        {
            "no_plan" => ("暂无可用订阅", "当前账户尚未开通套餐，请先前往套餐页购买或使用兑换码开通。"),
            "traffic_exceeded" => ("套餐流量已用尽", "当前计费周期流量已耗尽，请重置流量或续订套餐。"),
            "suspended" => ("账户不可用", "当前账户连接权限已被暂停，请联系工单支持。"),
            _ => ("订阅已到期", "当前套餐已到期，请续费或兑换套餐后继续连接。"),
        };
    }
}
