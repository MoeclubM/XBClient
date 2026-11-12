use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PanelSession {
    pub auth_data: String,
    pub email: String,
    pub base_url: String,
    pub panel_type: String,
    pub subscribe_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommercialPeriod {
    pub id: String,
    pub label: String,
    pub price_cents: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommercialPlan {
    pub id: String,
    pub name: String,
    pub description: String,
    pub quota_text: String,
    pub periods: Vec<CommercialPeriod>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PaymentChannel {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NoticeEntry {
    pub id: i64,
    pub title: String,
    pub content: String,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TicketMessage {
    pub id: String,
    pub is_me: bool,
    pub author: String,
    pub message: String,
    pub created_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TicketEntry {
    pub id: String,
    pub subject: String,
    pub status_text: String,
    pub closed: bool,
    pub updated_at: String,
    pub messages: Vec<TicketMessage>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OAuthProviderEntry {
    pub id: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OAuthBindingEntry {
    pub id: String,
    pub provider_id: String,
    pub provider_name: String,
    pub display_name: String,
    pub created_at: String,
    pub requires_password: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MfaSetupInfo {
    pub secret: String,
    pub otpauth_url: String,
    pub expires_at: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PanelStateSnapshot {
    pub logged_in: bool,
    pub panel_type: String,
    pub app_name: String,
    pub base_url: String,
    pub email: String,
    pub balance: i64,
    pub commission_balance: i64,
    pub currency_symbol: String,
    pub currency_unit: String,
    pub payment_enabled: bool,
    pub extensions: Vec<String>,
    pub subscribe_url: String,
    pub subscription_summary: String,
    pub block_reason: String,
    pub traffic_used_bytes: u64,
    pub traffic_total_bytes: u64,
    pub expired_at_unix: i64,
    pub plans: Vec<CommercialPlan>,
    pub payment_channels: Vec<PaymentChannel>,
    pub notices: Vec<NoticeEntry>,
    pub tickets: Vec<TicketEntry>,
    pub invite_codes: Vec<String>,
    pub referral_url: String,
    pub invite_commission_rate: i32,
    pub website_url: String,
    pub privacy_policy_url: String,
    pub user_agreement_url: String,
    pub oauth_providers: Vec<OAuthProviderEntry>,
    pub email_verify_required: bool,
    pub registration_enabled: bool,
    pub password_reset_enabled: bool,
    pub login_challenge_required: bool,
    pub register_challenge_required: bool,
    pub reset_challenge_required: bool,
    pub mfa_enabled: bool,
    pub mfa_required: bool,
    pub oauth_bindings: Vec<OAuthBindingEntry>,
    pub oauth_bind_pending: bool,
    pub browser_login_pending: bool,
    pub last_error: Option<String>,
}

pub fn format_bytes_brief(bytes: u64) -> String {
    const GIB: f64 = 1073741824.0;
    const MIB: f64 = 1048576.0;
    if bytes as f64 >= GIB {
        format!("{:.1} GB", bytes as f64 / GIB)
    } else {
        format!("{:.1} MB", bytes as f64 / MIB)
    }
}

pub fn format_money_cents(cents: i64, symbol: &str, unit: &str) -> String {
    let amount = cents as f64 / 100.0;
    if !symbol.trim().is_empty() {
        format!("{}{amount:.2}", symbol.trim())
    } else if !unit.trim().is_empty() {
        format!("{amount:.2} {}", unit.trim())
    } else {
        format!("¥{amount:.2}")
    }
}
