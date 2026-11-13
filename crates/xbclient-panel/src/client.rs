use crate::config::{BuildConfig, normalize_base_url};
use crate::http::{PxBoardRpcError, open_external_url, pxboard_rpc, xboard_get, xboard_post};
use crate::models::{MfaSetupInfo, PanelSession, PanelStateSnapshot};
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
    bind_cancel: Arc<AtomicBool>,
}

pub fn extract_reset_token(raw: &str) -> String {
    let trimmed = raw.trim();
    if (trimmed.starts_with("http://") || trimmed.starts_with("https://"))
        && let Ok(parsed) = url::Url::parse(trimmed)
        && let Some((_, tok)) = parsed.query_pairs().find(|(k, _)| k == "token")
        && !tok.trim().is_empty()
    {
        return tok.trim().to_string();
    }
    trimmed.to_string()
}

pub fn extract_referral_code(raw: &str) -> String {
    let trimmed = raw.trim();
    if (trimmed.starts_with("http://") || trimmed.starts_with("https://"))
        && let Ok(parsed) = url::Url::parse(trimmed)
        && let Some((_, code)) = parsed
            .query_pairs()
            .find(|(k, _)| k == "ref" || k == "code")
        && !code.trim().is_empty()
    {
        return code.trim().to_string();
    }
    trimmed.to_string()
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
            github_repo: cfg.github_repo.clone(),
            registration_enabled: true,
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
                bind_cancel: Arc::new(AtomicBool::new(false)),
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
        guard.snapshot.registration_enabled = snap.registration_enabled;
        guard.snapshot.password_reset_enabled = snap.password_reset_enabled;
        guard.snapshot.login_challenge_required = snap.login_challenge_required;
        guard.snapshot.register_challenge_required = snap.register_challenge_required;
        guard.snapshot.reset_challenge_required = snap.reset_challenge_required;
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
            match pxboard::login_password(&self.cfg, &base_url, email, password, code).await {
                Ok(res) => res,
                Err(err) => {
                    if let Some(rpc_err) = err.downcast_ref::<PxBoardRpcError>()
                        && rpc_err.code == "mfa_required"
                    {
                        let mut guard = self.inner.lock().expect("panel lock poisoned");
                        guard.snapshot.mfa_required = true;
                        guard.snapshot.last_error = Some(rpc_err.message.clone());
                    }
                    return Err(err);
                }
            }
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
            ensure!(password.len() >= 10, "密码至少 10 位");
            let mut body = json!({
                "email": email.trim(),
                "password": password,
            });
            let referral = extract_referral_code(invite_code);
            if !referral.is_empty() {
                body["referral"] = Value::String(referral);
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
            bail!("当前面板注册无需邮箱验证码");
        }
        xboard::send_email_verify(&self.cfg, &base_url, email).await
    }

    pub async fn request_password_reset(&self, email: &str) -> Result<String> {
        let trimmed = email.trim();
        ensure!(!trimmed.is_empty(), "请输入注册邮箱");
        ensure!(self.cfg.is_pxboard(), "请在网页端找回密码");
        let base_url = self.snapshot().base_url;
        let res = pxboard_rpc(
            &base_url,
            &self.cfg.user_agent,
            "",
            "core",
            "auth.resetRequest",
            json!({ "email": trimmed }),
        )
        .await?;
        Ok(res
            .get("message")
            .and_then(Value::as_str)
            .filter(|s| !s.is_empty())
            .unwrap_or("如果该邮箱可用，将收到密码重置邮件")
            .to_string())
    }

    pub async fn confirm_password_reset(&self, token_or_url: &str, new_password: &str) -> Result<()> {
        let token = extract_reset_token(token_or_url);
        ensure!(!token.is_empty(), "请输入邮件重置码或链接");
        ensure!(new_password.len() >= 10, "新密码至少 10 位");
        ensure!(self.cfg.is_pxboard(), "请在网页端找回密码");
        let base_url = self.snapshot().base_url;
        pxboard_rpc(
            &base_url,
            &self.cfg.user_agent,
            "",
            "core",
            "auth.resetPassword",
            json!({ "token": token, "password": new_password }),
        )
        .await?;
        Ok(())
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
            guard.snapshot.mfa_required = false;
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
        self.clear_local_session(base_url).await
    }

    async fn clear_local_session(&self, base_url: String) -> Result<PanelStateSnapshot> {
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
            github_repo: self.cfg.github_repo.clone(),
            registration_enabled: true,
            ..Default::default()
        };
        {
            let mut guard = self.inner.lock().expect("panel lock poisoned");
            guard.auth_cancel.store(true, Ordering::Relaxed);
            guard.bind_cancel.store(true, Ordering::Relaxed);
            guard.session = empty_sess;
            guard.snapshot = next_snap.clone();
        }
        Ok(self.load_guest_meta().await.unwrap_or(next_snap))
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

    pub async fn recharge_wallet(
        &self,
        amount_cents: i64,
        channel_id: &str,
    ) -> Result<(String, PanelStateSnapshot)> {
        ensure!(self.cfg.is_pxboard(), "请在套餐页或网页端完成充值");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        let redirect =
            pxboard::recharge_wallet(&self.cfg, &base_url, &auth_data, amount_cents, channel_id).await?;
        let snap = self.sync_all().await.unwrap_or_else(|_| self.snapshot());
        Ok((redirect, snap))
    }

    pub async fn change_password(
        &self,
        old_password: &str,
        new_password: &str,
        code: Option<&str>,
    ) -> Result<PanelStateSnapshot> {
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        if self.cfg.is_pxboard() {
            ensure!(new_password.len() >= 10, "新密码至少 10 位");
            let mut payload = json!({
                "currentPassword": old_password,
                "password": new_password,
            });
            if let Some(c) = code.map(str::trim).filter(|s| !s.is_empty()) {
                payload["code"] = Value::String(c.to_string());
            }
            pxboard_rpc(
                &base_url,
                &self.cfg.user_agent,
                &auth_data,
                "core",
                "account.changePassword",
                payload,
            )
            .await?;
            return self.clear_local_session(base_url).await;
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
        Ok(self.snapshot())
    }

    pub async fn mfa_setup(&self) -> Result<MfaSetupInfo> {
        ensure!(self.cfg.is_pxboard(), "当前面板不支持原生两步验证");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        let res = pxboard_rpc(
            &base_url,
            &self.cfg.user_agent,
            &auth_data,
            "core",
            "account.mfaSetup",
            json!({}),
        )
        .await?;
        Ok(MfaSetupInfo {
            secret: res
                .get("secret")
                .and_then(Value::as_str)
                .context("响应缺少两步验证密钥")?
                .to_string(),
            otpauth_url: res
                .get("otpauthUrl")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string(),
            expires_at: res
                .get("expiresAt")
                .and_then(Value::as_str)
                .unwrap_or("")
                .to_string(),
        })
    }

    pub async fn mfa_enable(&self, password: &str, code: &str) -> Result<PanelStateSnapshot> {
        ensure!(self.cfg.is_pxboard(), "当前面板不支持原生两步验证");
        ensure!(!password.is_empty(), "请输入当前登录密码");
        ensure!(code.trim().len() == 6, "请输入 6 位动态验证码");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        pxboard_rpc(
            &base_url,
            &self.cfg.user_agent,
            &auth_data,
            "core",
            "account.mfaEnable",
            json!({ "password": password, "code": code.trim() }),
        )
        .await?;
        self.sync_all().await
    }

    pub async fn mfa_disable(&self, password: &str, code: &str) -> Result<PanelStateSnapshot> {
        ensure!(self.cfg.is_pxboard(), "当前面板不支持原生两步验证");
        ensure!(!password.is_empty(), "请输入当前登录密码");
        ensure!(code.trim().len() == 6, "请输入 6 位动态验证码");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        pxboard_rpc(
            &base_url,
            &self.cfg.user_agent,
            &auth_data,
            "core",
            "account.mfaDisable",
            json!({ "password": password, "code": code.trim() }),
        )
        .await?;
        self.sync_all().await
    }

    pub async fn bind_oauth(&self, provider_id: &str) -> Result<PanelStateSnapshot> {
        ensure!(self.cfg.is_pxboard(), "当前面板不支持此绑定流程");
        let (auth_data, base_url, cancel) = {
            let mut g = self.inner.lock().expect("panel lock poisoned");
            g.bind_cancel.store(true, Ordering::Relaxed);
            let next_cancel = Arc::new(AtomicBool::new(false));
            g.bind_cancel = next_cancel.clone();
            g.snapshot.oauth_bind_pending = true;
            (
                g.session.auth_data.clone(),
                g.snapshot.base_url.clone(),
                next_cancel,
            )
        };
        let res = pxboard::bind_oauth_flow(&self.cfg, &base_url, &auth_data, provider_id, cancel).await;
        {
            let mut g = self.inner.lock().expect("panel lock poisoned");
            g.snapshot.oauth_bind_pending = false;
        }
        res?;
        self.sync_all().await
    }

    pub fn cancel_bind_oauth(&self) -> PanelStateSnapshot {
        let mut g = self.inner.lock().expect("panel lock poisoned");
        g.bind_cancel.store(true, Ordering::Relaxed);
        g.snapshot.oauth_bind_pending = false;
        g.snapshot.clone()
    }

    pub async fn unbind_oauth(
        &self,
        binding_id: &str,
        password: &str,
        code: &str,
    ) -> Result<PanelStateSnapshot> {
        ensure!(self.cfg.is_pxboard(), "当前面板不支持此解绑流程");
        ensure!(!binding_id.trim().is_empty(), "缺少绑定记录 ID");
        let (auth_data, base_url) = {
            let g = self.inner.lock().expect("panel lock poisoned");
            (g.session.auth_data.clone(), g.snapshot.base_url.clone())
        };
        let mut payload = json!({ "id": binding_id.trim() });
        if !password.is_empty() {
            payload["password"] = Value::String(password.to_string());
        }
        if !code.trim().is_empty() {
            payload["code"] = Value::String(code.trim().to_string());
        }
        pxboard_rpc(
            &base_url,
            &self.cfg.user_agent,
            &auth_data,
            "oauth",
            "bindings.delete",
            payload,
        )
        .await?;
        self.sync_all().await
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
            "request_password_reset" => {
                let email = params.get("email").and_then(Value::as_str).unwrap_or("");
                let msg = self.request_password_reset(email).await?;
                Ok(json!({ "message": msg }))
            }
            "confirm_password_reset" => {
                let token = params.get("token").and_then(Value::as_str).unwrap_or("");
                let password = params.get("password").and_then(Value::as_str).unwrap_or("");
                self.confirm_password_reset(token, password).await?;
                Ok(json!({ "reset": true }))
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
                let code = params.get("code").and_then(Value::as_str);
                let snap = self.change_password(old_pw, new_pw, code).await?;
                Ok(json!({ "changed": true, "snapshot": snap }))
            }
            "recharge_wallet" => {
                let amount = params.get("amount_cents").and_then(Value::as_i64).unwrap_or(0);
                let channel_id = params.get("channel_id").and_then(Value::as_str).unwrap_or("");
                let (redirect, snap) = self.recharge_wallet(amount, channel_id).await?;
                Ok(json!({ "redirect_url": redirect, "snapshot": snap }))
            }
            "mfa_setup" => Ok(serde_json::to_value(self.mfa_setup().await?)?),
            "mfa_enable" => {
                let pw = params.get("password").and_then(Value::as_str).unwrap_or("");
                let code = params.get("code").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(self.mfa_enable(pw, code).await?)?)
            }
            "mfa_disable" => {
                let pw = params.get("password").and_then(Value::as_str).unwrap_or("");
                let code = params.get("code").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(self.mfa_disable(pw, code).await?)?)
            }
            "bind_oauth" => {
                let provider_id = params.get("provider_id").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(self.bind_oauth(provider_id).await?)?)
            }
            "cancel_bind_oauth" => Ok(serde_json::to_value(self.cancel_bind_oauth())?),
            "unbind_oauth" => {
                let id = params.get("id").and_then(Value::as_str).unwrap_or("");
                let pw = params.get("password").and_then(Value::as_str).unwrap_or("");
                let code = params.get("code").and_then(Value::as_str).unwrap_or("");
                Ok(serde_json::to_value(self.unbind_oauth(id, pw, code).await?)?)
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
            github_repo: "MoeclubM/XBClient".into(),
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
        assert!(snap.registration_enabled);
        assert!(!snap.email_verify_required);
        assert_eq!(
            extract_reset_token("https://panel.example.com/reset?token=abc123xyz&foo=1"),
            "abc123xyz"
        );
        assert_eq!(extract_reset_token("  raw-token-99  "), "raw-token-99");
        assert_eq!(
            extract_referral_code("https://panel.example.com/register?ref=user-77"),
            "user-77"
        );
        assert_eq!(extract_referral_code("  user-88  "), "user-88");
        assert_eq!(pxboard::normalize_portal_path("/plans"), "/user/plans");
        assert_eq!(pxboard::normalize_portal_path("/user/orders"), "/user/orders");
        assert_eq!(pxboard::normalize_portal_path("/admin"), "/user");
        let rpc_err: anyhow::Error = PxBoardRpcError {
            status: 401,
            code: "mfa_required".into(),
            message: "请输入动态验证码".into(),
        }
        .into();
        assert_eq!(
            rpc_err.downcast_ref::<PxBoardRpcError>().map(|e| e.code.as_str()),
            Some("mfa_required")
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
