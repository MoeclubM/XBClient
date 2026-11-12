use crate::config::{BuildConfig, normalize_base_url};
use crate::http::{open_external_url, pxboard_rpc, xboard_get, xboard_post};
use crate::models::{PanelSession, PanelStateSnapshot};
use crate::{pxboard, xboard};
use anyhow::{Context, Result, bail, ensure};
use neto_core::{NetoController, build_profile_from_raw_nodes};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

struct InnerState {
    session: PanelSession,
    snapshot: PanelStateSnapshot,
    auth_cancel: Arc<AtomicBool>,
}

pub struct XbPanelClient {
    cfg: BuildConfig,
    data_dir: PathBuf,
    neto: Arc<NetoController>,
    inner: Mutex<InnerState>,
}

impl XbPanelClient {
    pub fn new(data_dir: impl Into<PathBuf>, neto: Arc<NetoController>, cfg: BuildConfig) -> Result<Arc<Self>> {
        let data_dir = data_dir.into();
        std::fs::create_dir_all(&data_dir)?;
        let session = load_session(&data_dir).unwrap_or_default();
        let base_url = if !session.base_url.trim().is_empty() {
            normalize_base_url(&session.base_url)
        } else {
            cfg.default_api_url.clone()
        };
        let snapshot = PanelStateSnapshot {
            logged_in: !session.auth_data.trim().is_empty(),
            panel_type: cfg.panel_type.clone(),
            app_name: cfg.app_name.clone(),
            base_url,
            email: session.email.clone(),
            payment_enabled: cfg.app_payment_enabled,
            subscribe_url: session.subscribe_url.clone(),
            website_url: cfg.website_url.clone(),
            privacy_policy_url: cfg.privacy_policy_url.clone(),
            user_agreement_url: cfg.user_agreement_url.clone(),
            ..Default::default()
        };
        Ok(Arc::new(Self {
            cfg,
            data_dir,
            neto,
            inner: Mutex::new(InnerState {
                session,
                snapshot,
                auth_cancel: Arc::new(AtomicBool::new(false)),
            }),
        }))
    }

    pub fn config(&self) -> &BuildConfig {
        &self.cfg
    }

    pub fn neto(&self) -> &Arc<NetoController> {
        &self.neto
    }

    pub fn snapshot(&self) -> PanelStateSnapshot {
        self.inner.lock().expect("panel lock poisoned").snapshot.clone()
    }

    fn persist_session(&self, sess: &PanelSession) -> Result<()> {
        save_session(&self.data_dir, sess)
    }

    pub async fn init_or_sync(&self) -> Result<PanelStateSnapshot> {
        let logged_in = self.snapshot().logged_in;
        if logged_in {
            let _ = self.sync_all().await;
        } else {
            let _ = self.load_guest_meta().await;
        }
        Ok(self.snapshot())
    }

    pub async fn load_guest_meta(&self) -> Result<PanelStateSnapshot> {
        let mut snap = self.snapshot();
        if self.cfg.is_pxboard() {
            pxboard::load_guest_meta(&self.cfg, &mut snap).await?;
        } else {
            xboard::load_guest_meta(&self.cfg, &mut snap).await?;
        }
        let mut guard = self.inner.lock().expect("panel lock poisoned");
        guard.snapshot.extensions = snap.extensions;
        guard.snapshot.oauth_providers = snap.oauth_providers;
        guard.snapshot.email_verify_required = snap.email_verify_required;
        Ok(guard.snapshot.clone())
    }

    pub async fn login_password(
        &self,
        email: &str,
        password: &str,
        code: Option<&str>,
    ) -> Result<PanelStateSnapshot> {
        let base_url = self.snapshot().base_url;
        let (auth_data, out_email) = if self.cfg.is_pxboard() {
            pxboard::login_password(&self.cfg, &base_url, email, password, code).await?
        } else {
            xboard::login_password(&self.cfg, &base_url, email, password).await?
        };
        self.finish_login(auth_data, out_email, base_url).await
    }

    pub async fn register_account(
        &self,
        email: &str,
        password: &str,
        invite_code: &str,
        email_code: &str,
    ) -> Result<PanelStateSnapshot> {
        let base_url = self.snapshot().base_url;
        if self.cfg.is_pxboard() {
            let mut body = json!({
                "email": email.trim(),
                "password": password,
            });
            if !invite_code.trim().is_empty() {
                body["inviteCode"] = Value::String(invite_code.trim().to_string());
            }
            if !email_code.trim().is_empty() {
                body["code"] = Value::String(email_code.trim().to_string());
            }
            pxboard_rpc(&base_url, &self.cfg.user_agent, "", "registration", "register", body).await?;
            return self.login_password(email, password, None).await;
        }
        let (auth_data, out_email) =
            xboard::register_account(&self.cfg, &base_url, email, password, invite_code, email_code).await?;
        self.finish_login(auth_data, out_email, base_url).await
    }

    pub async fn send_email_code(&self, email: &str) -> Result<()> {
        let base_url = self.snapshot().base_url;
        if self.cfg.is_pxboard() {
            pxboard_rpc(
                &base_url,
                &self.cfg.user_agent,
                "",
                "registration",
                "sendCode",
                json!({ "email": email.trim() }),
            )
            .await?;
            return Ok(());
        }
        xboard::send_email_verify(&self.cfg, &base_url, email).await
    }

    pub async fn browser_login(&self, provider_id: Option<&str>) -> Result<PanelStateSnapshot> {
        let (base_url, cancel) = {
            let mut guard = self.inner.lock().expect("panel lock poisoned");
            guard.auth_cancel.store(true, Ordering::Relaxed);
            let next_cancel = Arc::new(AtomicBool::new(false));
            guard.auth_cancel = next_cancel.clone();
            guard.snapshot.browser_login_pending = true;
            (guard.snapshot.base_url.clone(), next_cancel)
        };
        if !self.cfg.is_pxboard() {
            let driver = provider_id.unwrap_or("").trim();
            ensure!(!driver.is_empty(), "请选择第三方登录方式");
            let mut url = url::Url::parse(&format!(
                "{}/api/v1/passport/auth/oauth/{driver}/redirect",
                base_url
            ))?;
            url.query_pairs_mut()
                .append_pair("scene", "login")
                .append_pair("redirect", "dashboard")
                .append_pair("client", "app")
                .append_pair("app_scheme", &self.cfg.oauth_callback_scheme);
            open_external_url(url.as_str())?;
            let mut guard = self.inner.lock().expect("panel lock poisoned");
            guard.snapshot.browser_login_pending = false;
            return Ok(guard.snapshot.clone());
        }
        let res = pxboard::browser_authorize(&self.cfg, &base_url, provider_id, cancel).await;
        {
            let mut guard = self.inner.lock().expect("panel lock poisoned");
            guard.snapshot.browser_login_pending = false;
        }
        let (auth_data, email) = res?;
        self.finish_login(auth_data, email, base_url).await
    }

    pub fn cancel_browser_login(&self) -> PanelStateSnapshot {
        let mut guard = self.inner.lock().expect("panel lock poisoned");
        guard.auth_cancel.store(true, Ordering::Relaxed);
        guard.snapshot.browser_login_pending = false;
        guard.snapshot.clone()
    }

    async fn finish_login(
        &self,
        auth_data: String,
        email: String,
        base_url: String,
    ) -> Result<PanelStateSnapshot> {
        let sess = PanelSession {
            auth_data,
            email: email.clone(),
            base_url: base_url.clone(),
            panel_type: self.cfg.panel_type.clone(),
            subscribe_url: String::new(),
        };
        self.persist_session(&sess)?;
        {
            let mut guard = self.inner.lock().expect("panel lock poisoned");
            guard.session = sess;
            guard.snapshot.logged_in = true;
            guard.snapshot.email = email;
            guard.snapshot.base_url = base_url;
            guard.snapshot.last_error = None;
        }
        self.sync_all().await
    }

    pub async fn sync_all(&self) -> Result<PanelStateSnapshot> {
        let (auth_data, mut snap) = {
            let guard = self.inner.lock().expect("panel lock poisoned");
            (guard.session.auth_data.clone(), guard.snapshot.clone())
        };
        ensure!(!auth_data.trim().is_empty(), "尚未登录");
        let res = if self.cfg.is_pxboard() {
            pxboard::sync_all(&self.cfg, &self.neto, &auth_data, &mut snap).await
        } else {
            xboard::sync_all(&self.cfg, &self.neto, &auth_data, &mut snap).await
        };
        match res {
            Ok(()) => {
                snap.last_error = None;
                let sess = {
                    let mut guard = self.inner.lock().expect("panel lock poisoned");
                    guard.session.email = snap.email.clone();
                    guard.session.subscribe_url = snap.subscribe_url.clone();
                    guard.snapshot = snap.clone();
                    guard.session.clone()
                };
                let _ = self.persist_session(&sess);
                Ok(snap)
            }
            Err(err) => {
                let mut guard = self.inner.lock().expect("panel lock poisoned");
                guard.snapshot.last_error = Some(err.to_string());
                Err(err)
            }
        }
    }

    pub async fn logout(&self) -> Result<PanelStateSnapshot> {
        let (auth_data, base_url) = {
            let guard = self.inner.lock().expect("panel lock poisoned");
            (guard.session.auth_data.clone(), guard.snapshot.base_url.clone())
        };
        if self.cfg.is_pxboard() && !auth_data.trim().is_empty() {
            let _ = pxboard_rpc(&base_url, &self.cfg.user_agent, &auth_data, "core", "auth.logout", json!({})).await;
        }
        if self.neto.snapshot().connection.connected {
            let _ = self.neto.disconnect().await;
        }
        let _ = self.neto.upsert_managed_profile(
            "managed-default",
            &self.cfg.app_name,
            "",
            build_profile_from_raw_nodes(Vec::new(), None, Vec::new(), 0, None),
        );
        let empty_sess = PanelSession::default();
        self.persist_session(&empty_sess)?;
        let next_snap = PanelStateSnapshot {
            logged_in: false,
            panel_type: self.cfg.panel_type.clone(),
            app_name: self.cfg.app_name.clone(),
            base_url,
            payment_enabled: self.cfg.app_payment_enabled,
            website_url: self.cfg.website_url.clone(),
            privacy_policy_url: self.cfg.privacy_policy_url.clone(),
            user_agreement_url: self.cfg.user_agreement_url.clone(),
            ..Default::default()
        };
        let mut guard = self.inner.lock().expect("panel lock poisoned");
        guard.session = empty_sess;
        guard.snapshot = next_snap.clone();
        Ok(next_snap)
    }

    pub async fn purchase_plan(
        &self,
        plan_id: &str,
        period_id: &str,
        coupon: &str,
        channel_id: &str,
    ) -> Result<(Option<String>, PanelStateSnapshot)> {
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        let redirect = if self.cfg.is_pxboard() {
            pxboard::purchase_plan(&self.cfg, &base_url, &auth_data, plan_id, period_id, coupon, channel_id).await?
        } else {
            xboard::purchase_plan(
                &self.cfg,
                &base_url,
                &auth_data,
                self.cfg.app_payment_enabled,
                plan_id,
                period_id,
                coupon,
                channel_id,
            )
            .await?
        };
        let snap = self.sync_all().await.unwrap_or_else(|_| self.snapshot());
        Ok((redirect, snap))
    }

    pub async fn redeem_code(&self, code: &str) -> Result<(String, PanelStateSnapshot)> {
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        let msg = if self.cfg.is_pxboard() {
            pxboard::redeem_code(&self.cfg, &base_url, &auth_data, code).await?
        } else {
            xboard::redeem_gift_card(&self.cfg, &base_url, &auth_data, code).await?
        };
        let snap = self.sync_all().await.unwrap_or_else(|_| self.snapshot());
        Ok((msg, snap))
    }

    pub async fn create_ticket(&self, subject: &str, content: &str) -> Result<PanelStateSnapshot> {
        ensure!(!subject.trim().is_empty() && !content.trim().is_empty(), "请填写工单主题与内容");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        if self.cfg.is_pxboard() {
            pxboard_rpc(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "support",
                "tickets.create",
                json!({ "subject": subject.trim(), "content": content.trim() }),
            )
            .await?;
        } else {
            xboard_post(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "/api/v1/user/ticket/save",
                json!({ "subject": subject.trim(), "level": 1, "message": content.trim() }),
            )
            .await?;
        }
        self.sync_all().await
    }

    pub async fn reply_ticket(&self, ticket_id: &str, message: &str) -> Result<PanelStateSnapshot> {
        ensure!(!message.trim().is_empty(), "请输入回复内容");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        if self.cfg.is_pxboard() {
            pxboard_rpc(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "support",
                "tickets.reply",
                json!({ "id": ticket_id, "message": message.trim() }),
            )
            .await?;
        } else {
            let tid: i64 = ticket_id.parse().context("无效工单 ID")?;
            xboard_post(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "/api/v1/user/ticket/reply",
                json!({ "id": tid, "message": message.trim() }),
            )
            .await?;
        }
        self.sync_all().await
    }

    pub async fn close_ticket(&self, ticket_id: &str) -> Result<PanelStateSnapshot> {
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        if self.cfg.is_pxboard() {
            pxboard_rpc(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "support",
                "tickets.close",
                json!({ "id": ticket_id }),
            )
            .await?;
        } else {
            let tid: i64 = ticket_id.parse().context("无效工单 ID")?;
            xboard_post(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "/api/v1/user/ticket/close",
                json!({ "id": tid }),
            )
            .await?;
        }
        self.sync_all().await
    }

    pub async fn generate_invite(&self) -> Result<PanelStateSnapshot> {
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        if !self.cfg.is_pxboard() {
            xboard_get(&base_url, &self.cfg.user_agent, &auth_data, "/api/v1/user/invite/save", &[]).await?;
        }
        self.sync_all().await
    }

    pub async fn open_portal(&self, redirect_path: &str) -> Result<String> {
        ensure!(self.cfg.app_payment_enabled, "当前客户端已关闭外部网页收银台");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        if self.cfg.is_pxboard() {
            return pxboard::open_portal_url(&self.cfg, &base_url, &auth_data, redirect_path).await;
        }
        let url = format!("{base_url}{redirect_path}");
        open_external_url(&url)?;
        Ok(url)
    }

    pub async fn change_password(&self, old_password: &str, new_password: &str) -> Result<()> {
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        if self.cfg.is_pxboard() {
            pxboard_rpc(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "core",
                "account.changePassword",
                json!({ "currentPassword": old_password, "password": new_password }),
            )
            .await?;
        } else {
            xboard_post(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "/api/v1/user/changePassword",
                json!({ "old_password": old_password, "new_password": new_password }),
            )
            .await?;
        }
        Ok(())
    }

    pub async fn dispatch_json(&self, method: &str, params: Value) -> Result<Value> {
        match method {
            "snapshot" => Ok(serde_json::to_value(self.snapshot())?),
            "init_or_sync" => Ok(serde_json::to_value(self.init_or_sync().await?)?),
            "load_guest_meta" => Ok(serde_json::to_value(self.load_guest_meta().await?)?),
            "sync_all" => Ok(serde_json::to_value(self.sync_all().await?)?),
            "login_password" => {
                let email = params.get("email").and_then(Value::as_str).unwrap_or("");
                let password = params.get("password").and_then(Value::as_str).unwrap_or("");
                let code = params.get("code").and_then(Value::as_str);
                Ok(serde_json::to_value(self.login_password(email, password, code).await?)?)
            }
            "register_account" => {
                let email = params.get("email").and_then(Value::as_str).unwrap_or("");
                let password = params.get("password").and_then(Value::as_str).unwrap_or("");
                let invite = params.get("invite_code").and_then(Value::as_str).unwrap_or("");
                let code = params.get("email_code").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(
                    self.register_account(email, password, invite, code).await?,
                )?)
            }
            "send_email_code" => {
                let email = params.get("email").and_then(Value::as_str).unwrap_or("");
                self.send_email_code(email).await?;
                Ok(json!({ "sent": true }))
            }
            "browser_login" => {
                let provider = params.get("provider_id").and_then(Value::as_str);
                Ok(serde_json::to_value(self.browser_login(provider).await?)?)
            }
            "cancel_browser_login" => Ok(serde_json::to_value(self.cancel_browser_login())?),
            "logout" => Ok(serde_json::to_value(self.logout().await?)?),
            "purchase_plan" => {
                let plan_id = params.get("plan_id").and_then(Value::as_str).context("missing plan_id")?;
                let period_id = params.get("period_id").and_then(Value::as_str).unwrap_or("");
                let coupon = params.get("coupon").and_then(Value::as_str).unwrap_or("");
                let channel_id = params.get("channel_id").and_then(Value::as_str).unwrap_or("");
                let (redirect, snap) = self.purchase_plan(plan_id, period_id, coupon, channel_id).await?;
                Ok(json!({ "redirect_url": redirect, "snapshot": snap }))
            }
            "redeem_code" => {
                let code = params.get("code").and_then(Value::as_str).unwrap_or("");
                let (message, snap) = self.redeem_code(code).await?;
                Ok(json!({ "message": message, "snapshot": snap }))
            }
            "create_ticket" => {
                let subject = params.get("subject").and_then(Value::as_str).unwrap_or("");
                let content = params.get("content").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(self.create_ticket(subject, content).await?)?)
            }
            "reply_ticket" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                let msg = params.get("message").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(self.reply_ticket(id, msg).await?)?)
            }
            "close_ticket" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(self.close_ticket(id).await?)?)
            }
            "fetch_ticket_messages" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                if self.cfg.is_pxboard() {
                    let snap = self.snapshot();
                    let msgs = snap
                        .tickets
                        .into_iter()
                        .find(|t| t.id == id)
                        .map(|t| t.messages)
                        .unwrap_or_default();
                    Ok(serde_json::to_value(msgs)?)
                } else {
                    let (auth_data, base_url) = {
                        let g = self.inner.lock().expect("panel lock poisoned");
                        (g.session.auth_data.clone(), g.snapshot.base_url.clone())
                    };
                    let msgs = xboard::fetch_ticket_detail(&self.cfg, &base_url, &auth_data, id).await?;
                    Ok(serde_json::to_value(msgs)?)
                }
            }
            "generate_invite" => Ok(serde_json::to_value(self.generate_invite().await?)?),
            "open_portal" => {
                let path = params.get("path").and_then(Value::as_str).unwrap_or("/user");
                let url = self.open_portal(path).await?;
                Ok(json!({ "url": url }))
            }
            "change_password" => {
                let old_pw = params.get("old_password").and_then(Value::as_str).unwrap_or("");
                let new_pw = params.get("new_password").and_then(Value::as_str).unwrap_or("");
                self.change_password(old_pw, new_pw).await?;
                Ok(json!({ "changed": true }))
            }
            "open_url" => {
                let url = params.get("url").and_then(Value::as_str).unwrap_or("");
                open_external_url(url)?;
                Ok(json!({ "opened": true }))
            }
            other => bail!("unknown xbclient-panel method: {other}"),
        }
    }
}

fn session_file(data_dir: &Path) -> PathBuf {
    data_dir.join("xbclient_session.json")
}

fn load_session(data_dir: &Path) -> Result<PanelSession> {
    let bytes = std::fs::read(session_file(data_dir))?;
    Ok(serde_json::from_slice(&bytes)?)
}

fn save_session(data_dir: &Path, sess: &PanelSession) -> Result<()> {
    let tmp = data_dir.join("xbclient_session.json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(sess)?)?;
    std::fs::rename(&tmp, session_file(data_dir))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::parse_bool_flag;

    #[test]
    fn test_panel_client_session_roundtrip() {
        let dir = std::env::temp_dir().join(format!("xbclient-panel-test-{}", pxboard::new_request_id()));
        let neto = NetoController::new(&dir, "AerionTest", None).unwrap();
        let cfg = BuildConfig {
            default_api_url: "https://panel.example.com".into(),
            panel_type: "pxboard".into(),
            app_name: "XBClient".into(),
            app_payment_enabled: false,
            user_agent: "AerionTest".into(),
            oauth_callback_scheme: "xbclient".into(),
            website_url: "https://example.com".into(),
            privacy_policy_url: String::new(),
            user_agreement_url: String::new(),
        };
        let client = XbPanelClient::new(&dir, neto, cfg).unwrap();
        let snap = client.snapshot();
        assert!(!snap.logged_in);
        assert_eq!(snap.panel_type, "pxboard");
        assert_eq!(snap.base_url, "https://panel.example.com");
        assert!(!snap.payment_enabled);
        assert!(parse_bool_flag("", true));
        assert!(parse_bool_flag("true", false));
        assert!(!parse_bool_flag("false", true));
        assert!(!parse_bool_flag("0", true));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
