fn main() {
    for key in [
        "XBCLIENT_DEFAULT_API_URL",
        "XBCLIENT_PANEL_TYPE",
        "XBCLIENT_APP_NAME",
        "XBCLIENT_APP_PAYMENT_ENABLED",
        "XBCLIENT_USER_AGENT",
        "XBCLIENT_OAUTH_CALLBACK_SCHEME",
        "XBCLIENT_WEBSITE_URL",
        "XBCLIENT_PRIVACY_POLICY_URL",
        "XBCLIENT_USER_AGREEMENT_URL",
    ] {
        println!("cargo:rerun-if-env-changed={key}");
    }
}
