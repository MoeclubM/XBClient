use crate::config::BuildConfig;
use crate::http::{BatchCall, open_external_url, public_url, pxboard_batch, pxboard_rpc};
use crate::models::{
    CommercialPeriod, CommercialPlan, NoticeEntry, OAuthProviderEntry, PanelStateSnapshot,
    PaymentChannel, TicketEntry, TicketMessage, format_bytes_brief,
};
use anyhow::{Context, Result, anyhow, bail, ensure};
use neto_core::{NetoController, SubscriptionUserInfo, build_profile_from_raw_nodes};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::collections::hash_map::RandomState;
use std::hash::{BuildHasher, Hasher};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

static NONCE_SEQ: AtomicU64 = AtomicU64::new(1);

fn random_hex_32() -> String {
    let mut bytes = [0u8; 32];
    for chunk in bytes.chunks_exact_mut(8) {
        let mut h = RandomState::new().build_hasher();
        h.write_u64(NONCE_SEQ.fetch_add(1, Ordering::Relaxed));
        h.write_u128(
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos(),
        );
        chunk.copy_from_slice(&h.finish().to_le_bytes());
    }
    let mut out = String::with_capacity(64);
    for b in bytes {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

fn sha256_hex(input: &str) -> String {
    let digest = Sha256::digest(input.as_bytes());
    let mut out = String::with_capacity(64);
    for b in digest {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

pub fn new_request_id() -> String {
    let hex = random_hex_32();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

pub async fn load_guest_meta(cfg: &BuildConfig, snap: &mut PanelStateSnapshot) -> Result<()> {
    let batch = pxboard_batch(
        &snap.base_url,
        &cfg.user_agent,
        "",
        &[BatchCall {
            key: "setup",
            service: "core",
            method: "setup.get",
            input: json!({}),
        }],
    )
    .await?;
    if let Some(setup) = batch.pointer("/setup/data") {
        let exts = setup
            .get("extensions")
            .and_then(Value::as_array)
            .map(|arr| {
                arr.iter()
                    .filter_map(Value::as_str)
                    .map(ToString::to_string)
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        if exts.iter().any(|e| e == "oauth")
            && let Ok(rows) = pxboard_rpc(
                &snap.base_url,
                &cfg.user_agent,
                "",
                "oauth",
                "providers.list",
                json!({}),
            )
            .await
            && let Some(arr) = rows.as_array()
        {
            snap.oauth_providers = arr
                .iter()
                .filter_map(|r| {
                    Some(OAuthProviderEntry {
                        id: r.get("id")?.as_str()?.to_string(),
                        label: r.get("name")?.as_str()?.to_string(),
                    })
                })
                .collect();
        }
        snap.extensions = exts;
    }
    Ok(())
}

pub async fn login_password(
    cfg: &BuildConfig,
    base_url: &str,
    email: &str,
    password: &str,
    code: Option<&str>,
) -> Result<(String, String)> {
    let mut payload = json!({
        "email": email.trim(),
        "password": password,
    });
    if let Some(c) = code.map(str::trim).filter(|s| !s.is_empty()) {
        payload["code"] = Value::String(c.to_string());
    }
    let data = pxboard_rpc(base_url, &cfg.user_agent, "", "core", "auth.login", payload).await?;
    let tok = data
        .get("token")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .context("登录响应缺少 token")?;
    let out_email = data
        .get("email")
        .and_then(Value::as_str)
        .unwrap_or_else(|| email.trim())
        .to_string();
    Ok((format!("PxBoard {tok}"), out_email))
}

pub async fn browser_authorize(
    cfg: &BuildConfig,
    base_url: &str,
    provider_id: Option<&str>,
    cancelled: Arc<AtomicBool>,
) -> Result<(String, String)> {
    let proof = random_hex_32();
    let challenge = sha256_hex(&proof);
    let flow = pxboard_rpc(
        base_url,
        &cfg.user_agent,
        "",
        "core",
        "auth.authorizeCreate",
        json!({ "name": cfg.app_name, "challenge": challenge }),
    )
    .await?;
    let flow_id = flow
        .get("id")
        .and_then(Value::as_str)
        .context("缺少授权会话 ID")?
        .to_string();
    let raw_auth_url = flow
        .get("authorizationUrl")
        .and_then(Value::as_str)
        .context("缺少授权地址")?;
    let mut auth_url = url::Url::parse(&public_url(base_url, raw_auth_url))?;
    if let Some(pid) = provider_id.map(str::trim).filter(|s| !s.is_empty()) {
        auth_url.query_pairs_mut().append_pair("provider", pid);
    }
    open_external_url(auth_url.as_str())?;
    let interval = flow
        .get("intervalSeconds")
        .and_then(Value::as_u64)
        .unwrap_or(2)
        .max(1);
    for _ in 0..90 {
        if cancelled.load(Ordering::Relaxed) {
            bail!("已取消浏览器登录授权");
        }
        tokio::time::sleep(Duration::from_secs(interval)).await;
        if cancelled.load(Ordering::Relaxed) {
            bail!("已取消浏览器登录授权");
        }
        let res = pxboard_rpc(
            base_url,
            &cfg.user_agent,
            "",
            "core",
            "auth.authorizeExchange",
            json!({ "id": flow_id, "proof": proof }),
        )
        .await?;
        let status = res.get("status").and_then(Value::as_str).unwrap_or("");
        if status == "pending" {
            continue;
        }
        if status != "approved" {
            bail!("浏览器授权未通过：{status}");
        }
        let tok = res
            .get("token")
            .and_then(Value::as_str)
            .context("授权响应缺少 token")?;
        let email = res
            .pointer("/user/email")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        return Ok((format!("PxBoard {tok}"), email));
    }
    bail!("浏览器登录授权超时")
}

pub async fn open_portal_url(
    cfg: &BuildConfig,
    base_url: &str,
    auth_data: &str,
    redirect_path: &str,
) -> Result<String> {
    let res = pxboard_rpc(
        base_url,
        &cfg.user_agent,
        auth_data,
        "core",
        "auth.browserLink",
        json!({ "redirectPath": redirect_path }),
    )
    .await?;
    let rel = res
        .get("url")
        .and_then(Value::as_str)
        .context("响应缺少 url")?;
    let full = public_url(base_url, rel);
    let _ = open_external_url(&full);
    Ok(full)
}

fn period_label(item: &Value) -> String {
    if let Some(lbl) = item.get("label").and_then(Value::as_str).filter(|s| !s.is_empty()) {
        return lbl.to_string();
    }
    match item.get("kind").and_then(Value::as_str).unwrap_or("") {
        "reset" => "重置流量".to_string(),
        "traffic" => "流量包".to_string(),
        _ => {
            let unit = item.get("durationUnit").and_then(Value::as_str).unwrap_or("");
            if unit == "permanent" {
                "长期".to_string()
            } else {
                let v = item.get("durationValue").and_then(Value::as_i64).unwrap_or(1);
                format!("{v} {}", if unit == "month" { "个月" } else { "天" })
            }
        }
    }
}

pub async fn sync_all(
    cfg: &BuildConfig,
    neto: &Arc<NetoController>,
    auth_data: &str,
    snap: &mut PanelStateSnapshot,
) -> Result<()> {
    let batch = pxboard_batch(
        &snap.base_url,
        &cfg.user_agent,
        auth_data,
        &[
            BatchCall { key: "me", service: "core", method: "account.me", input: json!({}) },
            BatchCall { key: "plugins", service: "core", method: "plugins.list", input: json!({}) },
            BatchCall { key: "profile", service: "subscriptions", method: "client.profile", input: json!({}) },
            BatchCall { key: "wallet", service: "commerce", method: "wallet.get", input: json!({}) },
            BatchCall { key: "announcements", service: "support", method: "announcements.list", input: json!({}) },
            BatchCall { key: "affiliate", service: "affiliate", method: "summary.get", input: json!({}) },
            BatchCall { key: "plans", service: "commerce", method: "plans.list", input: json!({}) },
            BatchCall { key: "channels", service: "payments", method: "channels.list", input: json!({}) },
            BatchCall { key: "tickets", service: "support", method: "tickets.list", input: json!({ "mine": true }) },
        ],
    )
    .await?;

    let me_status = batch.pointer("/me/status").and_then(Value::as_u64).unwrap_or(500);
    let me_data = batch
        .pointer("/me/data")
        .filter(|_| me_status == 200)
        .ok_or_else(|| {
            anyhow!(
                "{}",
                batch
                    .pointer("/me/error/message")
                    .and_then(Value::as_str)
                    .unwrap_or("读取账户信息失败")
            )
        })?;
    if let Some(email) = me_data.get("email").and_then(Value::as_str) {
        snap.email = email.to_string();
    }

    if let Some(arr) = batch.pointer("/plugins/data").and_then(Value::as_array) {
        snap.extensions = arr
            .iter()
            .filter(|p| p.get("enabled").and_then(Value::as_bool).unwrap_or(false))
            .filter_map(|p| p.get("id").and_then(Value::as_str).map(ToString::to_string))
            .collect();
        snap.payment_enabled = snap.extensions.iter().any(|e| e == "commerce");
    }

    if batch.pointer("/wallet/status").and_then(Value::as_u64) == Some(200)
        && let Some(w) = batch.pointer("/wallet/data")
    {
        snap.balance = w.get("balance").and_then(Value::as_f64).unwrap_or(0.0).round() as i64;
        snap.currency_symbol = w.get("currencySymbol").and_then(Value::as_str).unwrap_or("").to_string();
        snap.currency_unit = w.get("currencyName").and_then(Value::as_str).unwrap_or("").to_string();
    }

    if batch.pointer("/affiliate/status").and_then(Value::as_u64) == Some(200)
        && let Some(a) = batch.pointer("/affiliate/data")
    {
        snap.commission_balance =
            a.get("availableAmount").and_then(Value::as_f64).unwrap_or(0.0).round() as i64;
        snap.invite_commission_rate =
            a.get("commissionPercent").and_then(Value::as_i64).unwrap_or(0) as i32;
        snap.referral_url = a
            .get("referralPath")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .map(|p| public_url(&snap.base_url, p))
            .unwrap_or_default();
    }

    if batch.pointer("/announcements/status").and_then(Value::as_u64) == Some(200)
        && let Some(arr) = batch.pointer("/announcements/data").and_then(Value::as_array)
    {
        snap.notices = arr
            .iter()
            .filter(|r| r.get("published").and_then(Value::as_bool).unwrap_or(true))
            .enumerate()
            .map(|(idx, r)| NoticeEntry {
                id: (idx + 1) as i64,
                title: r.get("title").and_then(Value::as_str).unwrap_or("").to_string(),
                content: r.get("content").and_then(Value::as_str).unwrap_or("").to_string(),
                created_at: 0,
            })
            .collect();
    }

    if batch.pointer("/plans/status").and_then(Value::as_u64) == Some(200)
        && let Some(arr) = batch.pointer("/plans/data").and_then(Value::as_array)
    {
        snap.plans = arr
            .iter()
            .filter_map(|p| {
                let id = p.get("id")?.as_str()?.to_string();
                let name = p.get("name")?.as_str()?.to_string();
                let unlimited = p.get("unlimited").and_then(Value::as_bool).unwrap_or(false);
                let quota_gib = p.get("quotaGiB").and_then(Value::as_f64).unwrap_or(0.0);
                let quota_text = if unlimited {
                    "不限流量".to_string()
                } else if quota_gib > 0.0 {
                    format!("{quota_gib:.0} GB")
                } else {
                    String::new()
                };
                let periods = p
                    .get("periods")
                    .and_then(Value::as_array)
                    .map(|pa| {
                        pa.iter()
                            .filter_map(|item| {
                                Some(CommercialPeriod {
                                    id: item.get("id")?.as_str()?.to_string(),
                                    label: period_label(item),
                                    price_cents: item.get("price")?.as_f64()?.round() as i64,
                                })
                            })
                            .collect::<Vec<_>>()
                    })
                    .unwrap_or_default();
                Some(CommercialPlan {
                    id,
                    name,
                    description: p.get("description").and_then(Value::as_str).unwrap_or("").to_string(),
                    quota_text,
                    periods,
                })
            })
            .collect();
    }

    if batch.pointer("/channels/status").and_then(Value::as_u64) == Some(200)
        && let Some(arr) = batch.pointer("/channels/data").and_then(Value::as_array)
    {
        snap.payment_channels = arr
            .iter()
            .filter_map(|c| {
                Some(PaymentChannel {
                    id: c.get("id")?.as_str()?.to_string(),
                    name: c.get("name")?.as_str()?.to_string(),
                })
            })
            .collect();
    }

    if batch.pointer("/tickets/status").and_then(Value::as_u64) == Some(200)
        && let Some(arr) = batch.pointer("/tickets/data").and_then(Value::as_array)
    {
        snap.tickets = arr
            .iter()
            .filter_map(|t| {
                let id = t.get("id")?.as_str()?.to_string();
                let status = t.get("status").and_then(Value::as_str).unwrap_or("open");
                let msgs = t
                    .get("messages")
                    .and_then(Value::as_array)
                    .map(|ma| {
                        ma.iter()
                            .map(|m| {
                                let author = m.get("author").and_then(Value::as_str).unwrap_or("").to_string();
                                TicketMessage {
                                    id: m.get("id").and_then(Value::as_str).unwrap_or("").to_string(),
                                    is_me: author == "user" || author == snap.email,
                                    author,
                                    message: m.get("content").and_then(Value::as_str).unwrap_or("").to_string(),
                                    created_at: m.get("createdAt").and_then(Value::as_str).unwrap_or("").to_string(),
                                }
                            })
                            .collect::<Vec<_>>()
                    })
                    .unwrap_or_default();
                Some(TicketEntry {
                    id,
                    subject: t.get("subject").and_then(Value::as_str).unwrap_or("").to_string(),
                    status_text: status.to_string(),
                    closed: status == "closed",
                    updated_at: t.get("updatedAt").and_then(Value::as_str).unwrap_or("").to_string(),
                    messages: msgs,
                })
            })
            .collect();
    }

    if batch.pointer("/profile/status").and_then(Value::as_u64) == Some(200)
        && let Some(prof) = batch.pointer("/profile/data")
    {
        let acc = prof.get("account").cloned().unwrap_or_else(|| json!({}));
        let used = acc
            .get("usedBytes")
            .and_then(Value::as_u64)
            .unwrap_or_else(|| {
                (acc.get("usedGiB").and_then(Value::as_f64).unwrap_or(0.0) * 1073741824.0).round() as u64
            });
        let total = acc
            .get("quotaBytes")
            .and_then(Value::as_u64)
            .unwrap_or_else(|| {
                (acc.get("quotaGiB").and_then(Value::as_f64).unwrap_or(0.0) * 1073741824.0).round() as u64
            });
        let unlimited = acc.get("unlimited").and_then(Value::as_bool).unwrap_or(false);
        let exp = acc.get("expiredAtUnix").and_then(Value::as_i64).unwrap_or(0);
        let raw_block = prof.get("blockReason").and_then(Value::as_str).unwrap_or("");
        let block_reason = match raw_block {
            "" | "no_plan" | "expired" | "traffic_exceeded" | "suspended" => raw_block.to_string(),
            _ => "suspended".to_string(),
        };
        let sub_url = prof
            .get("links")
            .and_then(Value::as_array)
            .and_then(|arr| arr.iter().find_map(|l| l.get("shareUrl").and_then(Value::as_str)))
            .filter(|s| !s.is_empty())
            .map(|s| public_url(&snap.base_url, s))
            .unwrap_or_default();

        snap.traffic_used_bytes = used;
        snap.traffic_total_bytes = total;
        snap.expired_at_unix = exp;
        snap.block_reason = block_reason.clone();
        snap.subscribe_url = sub_url.clone();
        snap.subscription_summary = if acc.get("exists").and_then(Value::as_bool) == Some(false) {
            String::new()
        } else {
            format!(
                "{} / {}",
                format_bytes_brief(used),
                if unlimited { "不限".to_string() } else { format_bytes_brief(total) }
            )
        };

        if !block_reason.is_empty() {
            let empty_payload = build_profile_from_raw_nodes(Vec::new(), None, Vec::new(), 0, None);
            neto.upsert_managed_profile("managed-default", &cfg.app_name, &sub_url, empty_payload)?;
            if neto.snapshot().connection.connected {
                let _ = neto.disconnect().await;
            }
        } else {
            let raw_nodes = prof
                .get("nodes")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            let routing = prof.get("routing").cloned().unwrap_or_else(|| json!({}));
            let route_yaml = routing
                .get("routeConfigYaml")
                .and_then(Value::as_str)
                .map(str::trim)
                .filter(|s| !s.is_empty())
                .map(ToString::to_string);
            let rule_count = routing.get("ruleCount").and_then(Value::as_u64).unwrap_or(0) as usize;
            let preview = routing
                .get("rulesPreview")
                .or_else(|| routing.get("rules"))
                .and_then(Value::as_array)
                .map(|arr| {
                    arr.iter()
                        .take(20)
                        .filter_map(Value::as_str)
                        .map(ToString::to_string)
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            let user_info = Some(SubscriptionUserInfo {
                upload_bytes: 0,
                download_bytes: used,
                total_bytes: total,
                expire_unix: (exp > 0).then_some(exp as u64),
            });
            let payload = build_profile_from_raw_nodes(
                raw_nodes,
                route_yaml,
                preview,
                rule_count,
                user_info,
            );
            neto.upsert_managed_profile("managed-default", &cfg.app_name, &sub_url, payload)?;
        }
    }
    Ok(())
}

pub async fn purchase_plan(
    cfg: &BuildConfig,
    base_url: &str,
    auth_data: &str,
    plan_id: &str,
    period_id: &str,
    coupon: &str,
    channel_id: &str,
) -> Result<Option<String>> {
    let order = pxboard_rpc(
        base_url,
        &cfg.user_agent,
        auth_data,
        "commerce",
        "orders.create",
        json!({
            "planId": plan_id,
            "periodId": period_id,
            "coupon": coupon.trim(),
            "requestId": new_request_id(),
        }),
    )
    .await?;
    let order_id = order
        .get("id")
        .and_then(Value::as_str)
        .context("创建订单缺少 id")?;
    if channel_id.is_empty() || channel_id == "wallet" {
        pxboard_rpc(
            base_url,
            &cfg.user_agent,
            auth_data,
            "commerce",
            "orders.pay",
            json!({ "id": order_id }),
        )
        .await?;
        return Ok(None);
    }
    let checkout = pxboard_rpc(
        base_url,
        &cfg.user_agent,
        auth_data,
        "payments",
        "checkout",
        json!({ "orderId": order_id, "channelId": channel_id }),
    )
    .await?;
    let redirect = checkout
        .get("redirectUrl")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .context("支付渠道未返回跳转链接")?
        .to_string();
    let _ = open_external_url(&redirect);
    Ok(Some(redirect))
}

pub async fn redeem_code(
    cfg: &BuildConfig,
    base_url: &str,
    auth_data: &str,
    code: &str,
) -> Result<String> {
    ensure!(!code.trim().is_empty(), "请输入兑换码");
    let res = pxboard_rpc(
        base_url,
        &cfg.user_agent,
        auth_data,
        "redeem",
        "redeem.create",
        json!({ "code": code.trim(), "requestId": new_request_id() }),
    )
    .await?;
    Ok(res
        .get("message")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .unwrap_or("兑换已完成")
        .to_string())
}
