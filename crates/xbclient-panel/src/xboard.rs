use crate::config::BuildConfig;
use crate::http::{open_external_url, xboard_get, xboard_post};
use crate::models::{
    CommercialPeriod, CommercialPlan, NoticeEntry, OAuthProviderEntry, PanelStateSnapshot,
    PaymentChannel, TicketEntry, TicketMessage, format_bytes_brief,
};
use anyhow::{Context, Result, bail, ensure};
use neto_core::{
    NetoController, ParsedProfilePayload, SubscriptionUserInfo, build_profile_from_raw_nodes,
    fetch_and_parse_subscription,
};
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

fn num_f64(v: Option<&Value>) -> f64 {
    match v {
        Some(Value::Number(n)) => n.as_f64().unwrap_or(0.0),
        Some(Value::String(s)) => s.trim().parse::<f64>().unwrap_or(0.0),
        _ => 0.0,
    }
}

pub async fn load_guest_meta(cfg: &BuildConfig, snap: &mut PanelStateSnapshot) -> Result<()> {
    let body = xboard_get(&snap.base_url, &cfg.user_agent, "", "/api/v1/guest/comm/config", &[]).await?;
    snap.registration_enabled = true;
    snap.password_reset_enabled = false;
    snap.login_challenge_required = false;
    snap.register_challenge_required = false;
    snap.reset_challenge_required = false;
    if let Some(data) = body.get("data") {
        snap.registration_enabled =
            num_f64(data.get("is_stop_register")) as i64 == 0
                && data.get("is_stop_register").and_then(Value::as_bool) != Some(true);
        snap.email_verify_required =
            num_f64(data.get("is_email_verify")) as i64 == 1
                || data.get("is_email_verify").and_then(Value::as_bool).unwrap_or(false);
        if let Some(arr) = data.get("oauth_providers").and_then(Value::as_array) {
            snap.oauth_providers = arr
                .iter()
                .filter_map(|r| {
                    Some(OAuthProviderEntry {
                        id: r.get("driver")?.as_str()?.to_string(),
                        label: r.get("label")?.as_str()?.to_string(),
                    })
                })
                .collect();
        }
    }
    Ok(())
}

pub async fn login_password(
    cfg: &BuildConfig,
    base_url: &str,
    email: &str,
    password: &str,
) -> Result<(String, String)> {
    let body = xboard_post(
        base_url,
        &cfg.user_agent,
        "",
        "/api/v1/passport/auth/login",
        json!({ "email": email.trim(), "password": password }),
    )
    .await?;
    let auth_data = body
        .pointer("/data/auth_data")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .context("登录响应缺少 auth_data")?
        .to_string();
    Ok((auth_data, email.trim().to_string()))
}

pub async fn send_email_verify(cfg: &BuildConfig, base_url: &str, email: &str) -> Result<()> {
    ensure!(!email.trim().is_empty(), "请输入邮箱");
    xboard_post(
        base_url,
        &cfg.user_agent,
        "",
        "/api/v1/passport/comm/sendEmailVerify",
        json!({ "email": email.trim() }),
    )
    .await?;
    Ok(())
}

pub async fn register_account(
    cfg: &BuildConfig,
    base_url: &str,
    email: &str,
    password: &str,
    invite_code: &str,
    email_code: &str,
) -> Result<(String, String)> {
    let mut payload = json!({
        "email": email.trim(),
        "password": password,
    });
    if !invite_code.trim().is_empty() {
        payload["invite_code"] = Value::String(invite_code.trim().to_string());
    }
    if !email_code.trim().is_empty() {
        payload["email_code"] = Value::String(email_code.trim().to_string());
    }
    let body = xboard_post(
        base_url,
        &cfg.user_agent,
        "",
        "/api/v1/passport/auth/register",
        payload,
    )
    .await?;
    let auth_data = body
        .pointer("/data/auth_data")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .context("注册响应缺少 auth_data")?
        .to_string();
    Ok((auth_data, email.trim().to_string()))
}

pub async fn sync_all(
    cfg: &BuildConfig,
    neto: &Arc<NetoController>,
    auth_data: &str,
    snap: &mut PanelStateSnapshot,
) -> Result<()> {
    let info = xboard_get(&snap.base_url, &cfg.user_agent, auth_data, "/api/v1/user/info", &[]).await?;
    if let Some(d) = info.get("data") {
        if let Some(email) = d.get("email").and_then(Value::as_str) {
            snap.email = email.to_string();
        }
        snap.balance = num_f64(d.get("balance")).round() as i64;
        snap.commission_balance = num_f64(d.get("commission_balance")).round() as i64;
    }

    if let Ok(uconf) =
        xboard_get(&snap.base_url, &cfg.user_agent, auth_data, "/api/v1/user/comm/config", &[]).await
        && let Some(d) = uconf.get("data")
    {
        snap.currency_symbol = d.get("currency_symbol").and_then(Value::as_str).unwrap_or("").to_string();
        snap.currency_unit = d.get("currency").and_then(Value::as_str).unwrap_or("").to_string();
    }

    snap.payment_enabled = cfg.app_payment_enabled;
    if cfg.app_payment_enabled {
        if let Ok(mbody) = xboard_get(
            &snap.base_url,
            &cfg.user_agent,
            auth_data,
            "/api/v1/user/order/getPaymentMethod",
            &[],
        )
        .await
            && let Some(arr) = mbody.get("data").and_then(Value::as_array)
        {
            snap.payment_channels = arr
                .iter()
                .filter_map(|m| {
                    let id = num_f64(m.get("id")) as i64;
                    let name = m.get("name")?.as_str()?.trim().to_string();
                    (id > 0 && !name.is_empty()).then(|| PaymentChannel {
                        id: id.to_string(),
                        name,
                    })
                })
                .collect();
        }
    } else {
        snap.payment_channels.clear();
    }

    let sub = xboard_get(
        &snap.base_url,
        &cfg.user_agent,
        auth_data,
        "/api/v1/user/getSubscribe",
        &[],
    )
    .await?;
    if let Some(d) = sub.get("data") {
        let u = num_f64(d.get("u")) as u64;
        let dl = num_f64(d.get("d")) as u64;
        let used = u.saturating_add(dl);
        let total = num_f64(d.get("transfer_enable")) as u64;
        let expired_at = num_f64(d.get("expired_at")) as i64;
        let plan_id = num_f64(d.get("plan_id")) as i64;
        let plan_name = d
            .pointer("/plan/name")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let sub_url = d
            .get("subscribe_url")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let now_unix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs() as i64;
        let block_reason = if plan_id <= 0 && plan_name.is_empty() {
            "no_plan".to_string()
        } else if expired_at > 0 && expired_at <= now_unix {
            "expired".to_string()
        } else if total == 0 || used >= total {
            "traffic_exceeded".to_string()
        } else {
            String::new()
        };

        let mut summary_parts = Vec::new();
        if !plan_name.is_empty() {
            summary_parts.push(plan_name.clone());
        }
        if total > 0 {
            summary_parts.push(format!(
                "{} / {}",
                format_bytes_brief(used),
                format_bytes_brief(total)
            ));
        }
        snap.traffic_used_bytes = used;
        snap.traffic_total_bytes = total;
        snap.expired_at_unix = expired_at;
        snap.subscribe_url = sub_url.clone();
        snap.block_reason = block_reason.clone();
        snap.subscription_summary = summary_parts.join(" · ");

        if !block_reason.is_empty() {
            let empty_payload = build_profile_from_raw_nodes(Vec::new(), None, Vec::new(), 0, None);
            neto.upsert_managed_profile("managed-default", &cfg.app_name, &sub_url, empty_payload)?;
            if neto.snapshot().connection.connected {
                let _ = neto.disconnect().await;
            }
        } else {
            let sub_parsed: Option<ParsedProfilePayload> = if !sub_url.trim().is_empty() {
                fetch_and_parse_subscription(&sub_url, "aerion", Some(&cfg.user_agent), None)
                    .await
                    .ok()
            } else {
                None
            };
            let user_info = Some(SubscriptionUserInfo {
                upload_bytes: u,
                download_bytes: dl,
                total_bytes: total,
                expire_unix: (expired_at > 0).then_some(expired_at as u64),
            });
            let plugin_nodes = xboard_get(
                &snap.base_url,
                &cfg.user_agent,
                auth_data,
                "/api/v1/admob/user/nodes",
                &[],
            )
            .await
            .ok()
            .and_then(|b| {
                b.pointer("/data/nodes")
                    .or_else(|| b.get("data"))
                    .and_then(Value::as_array)
                    .cloned()
            })
            .filter(|arr| !arr.is_empty());
            let final_payload = match (plugin_nodes, sub_parsed) {
                (Some(raw_nodes), Some(sp)) => build_profile_from_raw_nodes(
                    raw_nodes,
                    sp.route_config_yaml,
                    sp.rules_preview,
                    sp.rule_count,
                    user_info,
                ),
                (Some(raw_nodes), None) => {
                    build_profile_from_raw_nodes(raw_nodes, None, Vec::new(), 0, user_info)
                }
                (None, Some(mut sp)) => {
                    sp.user_info = user_info;
                    sp
                }
                (None, None) => build_profile_from_raw_nodes(Vec::new(), None, Vec::new(), 0, user_info),
            };
            neto.upsert_managed_profile("managed-default", &cfg.app_name, &sub_url, final_payload)?;
        }
    }

    if let Ok(plans_body) =
        xboard_get(&snap.base_url, &cfg.user_agent, auth_data, "/api/v1/user/plan/fetch", &[]).await
        && let Some(arr) = plans_body.get("data").and_then(Value::as_array)
    {
        let period_fields = [
            ("month_price", "月付"),
            ("quarter_price", "季付"),
            ("half_year_price", "半年付"),
            ("year_price", "年付"),
            ("two_year_price", "两年付"),
            ("three_year_price", "三年付"),
            ("onetime_price", "一次性"),
            ("reset_price", "重置流量"),
        ];
        snap.plans = arr
            .iter()
            .filter_map(|p| {
                let id = num_f64(p.get("id")) as i64;
                let name = p.get("name")?.as_str()?.to_string();
                let gb = num_f64(p.get("transfer_enable"));
                let periods = period_fields
                    .iter()
                    .filter_map(|(field, label)| {
                        let amt = num_f64(p.get(*field)).round() as i64;
                        (amt > 0).then(|| CommercialPeriod {
                            id: (*field).to_string(),
                            label: (*label).to_string(),
                            price_cents: amt,
                        })
                    })
                    .collect::<Vec<_>>();
                Some(CommercialPlan {
                    id: id.to_string(),
                    name,
                    description: p.get("content").and_then(Value::as_str).unwrap_or("").to_string(),
                    quota_text: if gb > 0.0 { format!("{gb:.0} GB") } else { String::new() },
                    periods,
                })
            })
            .collect();
    }

    if let Ok(nbody) =
        xboard_get(&snap.base_url, &cfg.user_agent, auth_data, "/api/v1/user/notice/fetch", &[]).await
        && let Some(arr) = nbody.get("data").and_then(Value::as_array)
    {
        snap.notices = arr
            .iter()
            .map(|r| NoticeEntry {
                id: num_f64(r.get("id")) as i64,
                title: r.get("title").and_then(Value::as_str).unwrap_or("").to_string(),
                content: r.get("content").and_then(Value::as_str).unwrap_or("").to_string(),
                created_at: num_f64(r.get("created_at")) as i64,
            })
            .collect();
    }

    if let Ok(ibody) =
        xboard_get(&snap.base_url, &cfg.user_agent, auth_data, "/api/v1/user/invite/fetch", &[]).await
        && let Some(d) = ibody.get("data")
    {
        snap.invite_codes = d
            .get("codes")
            .and_then(Value::as_array)
            .map(|arr| {
                arr.iter()
                    .filter_map(|c| c.get("code").and_then(Value::as_str).map(ToString::to_string))
                    .collect()
            })
            .unwrap_or_default();
        if let Some(stat) = d.get("stat").and_then(Value::as_array) {
            snap.invite_commission_rate = stat.get(3).map(|v| num_f64(Some(v)) as i32).unwrap_or(0);
        }
    }

    if let Ok(tbody) =
        xboard_get(&snap.base_url, &cfg.user_agent, auth_data, "/api/v1/user/ticket/fetch", &[]).await
        && let Some(arr) = tbody.get("data").and_then(Value::as_array)
    {
        snap.tickets = arr
            .iter()
            .map(|t| {
                let status = num_f64(t.get("status")) as i64;
                TicketEntry {
                    id: (num_f64(t.get("id")) as i64).to_string(),
                    subject: t.get("subject").and_then(Value::as_str).unwrap_or("").to_string(),
                    status_text: if status == 1 { "已关闭".to_string() } else { "处理中".to_string() },
                    closed: status == 1,
                    updated_at: String::new(),
                    messages: Vec::new(),
                }
            })
            .collect();
    }
    Ok(())
}

pub async fn purchase_plan(
    cfg: &BuildConfig,
    base_url: &str,
    auth_data: &str,
    payment_enabled: bool,
    plan_id: &str,
    period_id: &str,
    coupon: &str,
    channel_id: &str,
) -> Result<Option<String>> {
    let pid: i64 = plan_id.parse().context("无效套餐 ID")?;
    let mut save_body = json!({ "plan_id": pid, "period": period_id });
    if !coupon.trim().is_empty() {
        save_body["coupon_code"] = Value::String(coupon.trim().to_string());
    }
    let save = xboard_post(
        base_url,
        &cfg.user_agent,
        auth_data,
        "/api/v1/user/order/save",
        save_body,
    )
    .await?;
    let trade_no = save
        .get("data")
        .and_then(Value::as_str)
        .context("创建订单未返回 trade_no")?;
    if !payment_enabled || channel_id.is_empty() || channel_id == "wallet" {
        let checkout = xboard_post(
            base_url,
            &cfg.user_agent,
            auth_data,
            "/api/v1/user/order/checkout",
            json!({ "trade_no": trade_no }),
        )
        .await;
        if let Ok(ref res) = checkout
            && num_f64(res.get("type")) as i64 == -1
        {
            return Ok(None);
        }
        let _ = xboard_post(
            base_url,
            &cfg.user_agent,
            auth_data,
            "/api/v1/user/order/cancel",
            json!({ "trade_no": trade_no }),
        )
        .await;
        bail!("账户余额不足以抵扣该套餐");
    }
    let method_id: i64 = channel_id.parse().context("无效支付渠道")?;
    let checkout = xboard_post(
        base_url,
        &cfg.user_agent,
        auth_data,
        "/api/v1/user/order/checkout",
        json!({ "trade_no": trade_no, "method": method_id }),
    )
    .await?;
    if num_f64(checkout.get("type")) as i64 == -1 {
        return Ok(None);
    }
    if let Some(url) = checkout.get("data").and_then(Value::as_str).filter(|s| s.starts_with("http")) {
        let _ = open_external_url(url);
        return Ok(Some(url.to_string()));
    }
    bail!("支付渠道未返回有效收银台链接")
}

pub async fn redeem_gift_card(
    cfg: &BuildConfig,
    base_url: &str,
    auth_data: &str,
    code: &str,
) -> Result<String> {
    ensure!(!code.trim().is_empty(), "请输入兑换码");
    let body = xboard_post(
        base_url,
        &cfg.user_agent,
        auth_data,
        "/api/v1/user/gift-card/redeem",
        json!({ "code": code.trim() }),
    )
    .await?;
    Ok(body
        .get("message")
        .and_then(Value::as_str)
        .unwrap_or("兑换成功")
        .to_string())
}

pub async fn fetch_ticket_detail(
    cfg: &BuildConfig,
    base_url: &str,
    auth_data: &str,
    ticket_id: &str,
) -> Result<Vec<TicketMessage>> {
    let body = xboard_get(
        base_url,
        &cfg.user_agent,
        auth_data,
        "/api/v1/user/ticket/fetch",
        &[("id", ticket_id.to_string())],
    )
    .await?;
    let msgs = body
        .pointer("/data/message")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .map(|m| TicketMessage {
                    id: (num_f64(m.get("id")) as i64).to_string(),
                    is_me: m.get("is_me").and_then(Value::as_bool).unwrap_or(false),
                    author: if m.get("is_me").and_then(Value::as_bool).unwrap_or(false) {
                        "我".to_string()
                    } else {
                        "客服".to_string()
                    },
                    message: m.get("message").and_then(Value::as_str).unwrap_or("").to_string(),
                    created_at: String::new(),
                })
                .collect()
        })
        .unwrap_or_default();
    Ok(msgs)
}
