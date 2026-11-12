use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BuildConfig {
    pub default_api_url: String,
    pub panel_type: String,
    pub app_name: String,
    pub user_agent: String,
    pub oauth_callback_scheme: String,
    pub website_url: String,
    pub privacy_policy_url: String,
    pub user_agreement_url: String,
}

fn env_or_compile(compile_val: Option<&'static str>, runtime_key: &str, default: &str) -> String {
    if let Some(v) = compile_val.map(str::trim).filter(|s| !s.is_empty()) {
        return v.to_string();
    }
    if let Ok(v) = std::env::var(runtime_key) {
        let trimmed = v.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }
    default.to_string()
}

pub fn normalize_base_url(raw: &str) -> String {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return String::new();
    }
    if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        trimmed.to_string()
    } else {
        format!("https://{trimmed}")
    }
}

impl BuildConfig {
    pub fn from_compile_env() -> Self {
        let raw_url = env_or_compile(
            option_env!("XBCLIENT_DEFAULT_API_URL"),
            "XBCLIENT_DEFAULT_API_URL",
            "",
        );
        let panel_raw = env_or_compile(
            option_env!("XBCLIENT_PANEL_TYPE"),
            "XBCLIENT_PANEL_TYPE",
            "xboard",
        )
        .to_ascii_lowercase();
        let panel_type = if panel_raw == "pxboard" {
            "pxboard".to_string()
        } else {
            "xboard".to_string()
        };
        Self {
            default_api_url: normalize_base_url(&raw_url),
            panel_type,
            app_name: env_or_compile(
                option_env!("XBCLIENT_APP_NAME"),
                "XBCLIENT_APP_NAME",
                "XBClient",
            ),
            user_agent: env_or_compile(
                option_env!("XBCLIENT_USER_AGENT"),
                "XBCLIENT_USER_AGENT",
                "Aerion",
            ),
            oauth_callback_scheme: env_or_compile(
                option_env!("XBCLIENT_OAUTH_CALLBACK_SCHEME"),
                "XBCLIENT_OAUTH_CALLBACK_SCHEME",
                "xbclient",
            ),
            website_url: env_or_compile(
                option_env!("XBCLIENT_WEBSITE_URL"),
                "XBCLIENT_WEBSITE_URL",
                "",
            ),
            privacy_policy_url: env_or_compile(
                option_env!("XBCLIENT_PRIVACY_POLICY_URL"),
                "XBCLIENT_PRIVACY_POLICY_URL",
                "",
            ),
            user_agreement_url: env_or_compile(
                option_env!("XBCLIENT_USER_AGREEMENT_URL"),
                "XBCLIENT_USER_AGREEMENT_URL",
                "",
            ),
        }
    }

    pub fn is_pxboard(&self) -> bool {
        self.panel_type == "pxboard"
    }
}
