#![cfg(target_os = "linux")]

use adw::prelude::*;
use gtk::prelude::*;
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;
use xbclient_panel::{PanelStateSnapshot, XbPanelClient};

pub struct AuthView {
    pub root: gtk::ScrolledWindow,
    app_title: gtk::Label,
    mode_box: gtk::Box,
    login_toggle: gtk::ToggleButton,
    reg_toggle: gtk::ToggleButton,
    reset_toggle: gtk::ToggleButton,
    form_group: adw::PreferencesGroup,
    password_row: adw::PasswordEntryRow,
    mfa_row: adw::EntryRow,
    code_row: adw::EntryRow,
    invite_row: adw::EntryRow,
    reset_token_row: adw::EntryRow,
    reset_pw_row: adw::PasswordEntryRow,
    submit_btn: gtk::Button,
    browser_box: gtk::Box,
    msg_label: gtk::Label,
    mode: Rc<RefCell<String>>,
    last_provider_sig: RefCell<String>,
    panel: Arc<XbPanelClient>,
    rt: Handle,
}

impl AuthView {
    pub fn new(panel: Arc<XbPanelClient>, rt: Handle) -> Rc<Self> {
        let scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .vexpand(true)
            .build();
        let clamp = adw::Clamp::builder()
            .maximum_size(400)
            .margin_top(28)
            .margin_bottom(28)
            .margin_start(18)
            .margin_end(18)
            .build();
        let outer = gtk::Box::new(gtk::Orientation::Vertical, 14);
        outer.set_valign(gtk::Align::Center);

        let snap0 = panel.snapshot();
        let app_title = gtk::Label::new(Some(&snap0.app_name));
        app_title.add_css_class("title-2");
        app_title.set_halign(gtk::Align::Center);

        let mode_box = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        mode_box.add_css_class("linked");
        mode_box.set_halign(gtk::Align::Fill);
        let login_toggle = gtk::ToggleButton::with_label("登录");
        let reg_toggle = gtk::ToggleButton::with_label("注册");
        let reset_toggle = gtk::ToggleButton::with_label("找回密码");
        reg_toggle.set_group(Some(&login_toggle));
        reset_toggle.set_group(Some(&login_toggle));
        login_toggle.set_active(true);
        login_toggle.set_hexpand(true);
        reg_toggle.set_hexpand(true);
        reset_toggle.set_hexpand(true);
        reset_toggle.set_visible(false);
        mode_box.append(&login_toggle);
        mode_box.append(&reg_toggle);
        mode_box.append(&reset_toggle);

        let form_group = adw::PreferencesGroup::new();
        let email_row = adw::EntryRow::builder().title("邮箱").build();
        let password_row = adw::PasswordEntryRow::builder().title("密码").build();
        let mfa_row = adw::EntryRow::builder().title("两步验证码（6 位）").build();
        mfa_row.set_visible(false);
        let code_row = adw::EntryRow::builder().title("邮箱验证码").build();
        let send_code_btn = gtk::Button::with_label("发送验证码");
        send_code_btn.add_css_class("flat");
        send_code_btn.set_valign(gtk::Align::Center);
        code_row.add_suffix(&send_code_btn);
        code_row.set_visible(false);
        let invite_row = adw::EntryRow::builder().title("邀请码（选填）").build();
        invite_row.set_visible(false);
        let reset_token_row = adw::EntryRow::builder().title("邮件重置码或链接").build();
        let send_reset_btn = gtk::Button::with_label("发送邮件");
        send_reset_btn.add_css_class("flat");
        send_reset_btn.set_valign(gtk::Align::Center);
        reset_token_row.add_suffix(&send_reset_btn);
        reset_token_row.set_visible(false);
        let reset_pw_row = adw::PasswordEntryRow::builder().title("新密码（至少 10 位）").build();
        reset_pw_row.set_visible(false);

        form_group.add(&email_row);
        form_group.add(&password_row);
        form_group.add(&mfa_row);
        form_group.add(&code_row);
        form_group.add(&invite_row);
        form_group.add(&reset_token_row);
        form_group.add(&reset_pw_row);

        let submit_btn = gtk::Button::with_label("登录");
        submit_btn.add_css_class("suggested-action");
        submit_btn.add_css_class("pill");
        submit_btn.set_size_request(180, 42);
        submit_btn.set_halign(gtk::Align::Fill);

        let browser_box = gtk::Box::new(gtk::Orientation::Vertical, 8);

        let msg_label = gtk::Label::new(None);
        msg_label.set_wrap(true);
        msg_label.set_xalign(0.0);
        msg_label.set_visible(false);

        outer.append(&app_title);
        outer.append(&mode_box);
        outer.append(&form_group);
        outer.append(&submit_btn);
        outer.append(&browser_box);
        outer.append(&msg_label);
        clamp.set_child(Some(&outer));
        scroll.set_child(Some(&clamp));

        let mode = Rc::new(RefCell::new("login".to_string()));
        let view = Rc::new(Self {
            root: scroll,
            app_title,
            mode_box,
            login_toggle: login_toggle.clone(),
            reg_toggle: reg_toggle.clone(),
            reset_toggle: reset_toggle.clone(),
            form_group,
            password_row: password_row.clone(),
            mfa_row: mfa_row.clone(),
            code_row: code_row.clone(),
            invite_row: invite_row.clone(),
            reset_token_row: reset_token_row.clone(),
            reset_pw_row: reset_pw_row.clone(),
            submit_btn: submit_btn.clone(),
            browser_box,
            msg_label: msg_label.clone(),
            mode: mode.clone(),
            last_provider_sig: RefCell::new(String::new()),
            panel: panel.clone(),
            rt: rt.clone(),
        });

        {
            let v = view.clone();
            login_toggle.connect_toggled(move |btn| {
                if btn.is_active() {
                    *v.mode.borrow_mut() = "login".to_string();
                    v.submit_btn.set_label("登录");
                    v.apply_snapshot(&v.panel.snapshot());
                }
            });
        }
        {
            let v = view.clone();
            reg_toggle.connect_toggled(move |btn| {
                if btn.is_active() {
                    *v.mode.borrow_mut() = "register".to_string();
                    v.submit_btn.set_label("注册并登录");
                    v.apply_snapshot(&v.panel.snapshot());
                }
            });
        }
        {
            let v = view.clone();
            reset_toggle.connect_toggled(move |btn| {
                if btn.is_active() {
                    *v.mode.borrow_mut() = "reset".to_string();
                    v.submit_btn.set_label("确认重置密码");
                    v.apply_snapshot(&v.panel.snapshot());
                }
            });
        }
        {
            let p = panel.clone();
            let handle = rt.clone();
            let erow = email_row.clone();
            let mlbl = msg_label.clone();
            send_code_btn.connect_clicked(move |btn| {
                let email = erow.text().trim().to_string();
                if email.is_empty() {
                    return;
                }
                btn.set_sensitive(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                handle.spawn(async move {
                    let _ = tx.send(p.send_email_code(&email).await.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let mlbl_c = mlbl.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(())) => {
                        btn_c.set_sensitive(true);
                        mlbl_c.remove_css_class("error");
                        mlbl_c.set_text("验证码已发送，请查收邮箱。");
                        mlbl_c.set_visible(true);
                        gtk::glib::ControlFlow::Break
                    }
                    Ok(Err(err)) => {
                        btn_c.set_sensitive(true);
                        mlbl_c.add_css_class("error");
                        mlbl_c.set_text(&err);
                        mlbl_c.set_visible(true);
                        gtk::glib::ControlFlow::Break
                    }
                    Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
                    Err(_) => {
                        btn_c.set_sensitive(true);
                        gtk::glib::ControlFlow::Break
                    }
                });
            });
        }
        {
            let p = panel.clone();
            let handle = rt.clone();
            let erow = email_row.clone();
            let mlbl = msg_label.clone();
            send_reset_btn.connect_clicked(move |btn| {
                let email = erow.text().trim().to_string();
                if email.is_empty() {
                    return;
                }
                btn.set_sensitive(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                handle.spawn(async move {
                    let _ = tx.send(p.request_password_reset(&email).await.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let mlbl_c = mlbl.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(msg)) => {
                        btn_c.set_sensitive(true);
                        mlbl_c.remove_css_class("error");
                        mlbl_c.set_text(&msg);
                        mlbl_c.set_visible(true);
                        gtk::glib::ControlFlow::Break
                    }
                    Ok(Err(err)) => {
                        btn_c.set_sensitive(true);
                        mlbl_c.add_css_class("error");
                        mlbl_c.set_text(&err);
                        mlbl_c.set_visible(true);
                        gtk::glib::ControlFlow::Break
                    }
                    Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
                    Err(_) => {
                        btn_c.set_sensitive(true);
                        gtk::glib::ControlFlow::Break
                    }
                });
            });
        }
        {
            let p = panel;
            let handle = rt;
            let cur_mode = mode;
            let erow = email_row;
            let prow = password_row;
            let mrow = mfa_row;
            let crow = code_row;
            let irow = invite_row;
            let rtrow = reset_token_row;
            let rprow = reset_pw_row;
            let ltoggle = login_toggle;
            let mlbl = msg_label;
            let view_c = view.clone();
            submit_btn.connect_clicked(move |btn| {
                let m = cur_mode.borrow().clone();
                if m == "reset" {
                    let tok = rtrow.text().trim().to_string();
                    let new_pw = rprow.text().to_string();
                    if tok.is_empty() || new_pw.len() < 10 {
                        return;
                    }
                    btn.set_sensitive(false);
                    mlbl.set_visible(false);
                    let p = p.clone();
                    let (tx, rx) = std::sync::mpsc::channel();
                    handle.spawn(async move {
                        let _ = tx.send(p.confirm_password_reset(&tok, &new_pw).await.map_err(|e| format!("{e:#}")));
                    });
                    let btn_c = btn.clone();
                    let mlbl_c = mlbl.clone();
                    let rtrow_c = rtrow.clone();
                    let rprow_c = rprow.clone();
                    let lt_c = ltoggle.clone();
                    gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                        Ok(Ok(())) => {
                            btn_c.set_sensitive(true);
                            rtrow_c.set_text("");
                            rprow_c.set_text("");
                            lt_c.set_active(true);
                            mlbl_c.remove_css_class("error");
                            mlbl_c.set_text("密码已重置，请使用新密码登录。");
                            mlbl_c.set_visible(true);
                            gtk::glib::ControlFlow::Break
                        }
                        Ok(Err(err)) => {
                            btn_c.set_sensitive(true);
                            mlbl_c.add_css_class("error");
                            mlbl_c.set_text(&err);
                            mlbl_c.set_visible(true);
                            gtk::glib::ControlFlow::Break
                        }
                        Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
                        Err(_) => {
                            btn_c.set_sensitive(true);
                            gtk::glib::ControlFlow::Break
                        }
                    });
                    return;
                }

                let email = erow.text().trim().to_string();
                let pw = prow.text().to_string();
                if email.is_empty() || pw.is_empty() {
                    return;
                }
                let mfa = mrow.text().trim().to_string();
                let code = crow.text().trim().to_string();
                let invite = irow.text().trim().to_string();
                let is_reg = m == "register";
                btn.set_sensitive(false);
                mlbl.set_visible(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                handle.spawn(async move {
                    let res = if is_reg {
                        p.register_account(&email, &pw, &invite, &code).await
                    } else {
                        let code_opt = (!mfa.is_empty()).then_some(mfa.as_str());
                        p.login_password(&email, &pw, code_opt).await
                    };
                    let _ = tx.send(res.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let mlbl_c = mlbl.clone();
                let p_c = p.clone();
                let v_c = view_c.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(_)) => {
                        btn_c.set_sensitive(true);
                        gtk::glib::ControlFlow::Break
                    }
                    Ok(Err(err)) => {
                        btn_c.set_sensitive(true);
                        v_c.apply_snapshot(&p_c.snapshot());
                        mlbl_c.add_css_class("error");
                        mlbl_c.set_text(&err);
                        mlbl_c.set_visible(true);
                        gtk::glib::ControlFlow::Break
                    }
                    Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
                    Err(_) => {
                        btn_c.set_sensitive(true);
                        gtk::glib::ControlFlow::Break
                    }
                });
            });
        }

        view
    }

    pub fn apply_snapshot(&self, snap: &PanelStateSnapshot) {
        self.app_title.set_text(if snap.app_name.is_empty() { "XBClient" } else { &snap.app_name });
        let is_px = snap.panel_type == "pxboard";
        let mut m = self.mode.borrow().clone();
        if m == "register" && !snap.registration_enabled {
            self.login_toggle.set_active(true);
            m = "login".to_string();
        }
        if m == "reset" && !snap.password_reset_enabled {
            self.login_toggle.set_active(true);
            m = "login".to_string();
        }
        self.mode_box
            .set_visible(snap.registration_enabled || snap.password_reset_enabled);
        self.reg_toggle.set_visible(snap.registration_enabled);
        self.reset_toggle.set_visible(snap.password_reset_enabled);

        let is_login = m == "login";
        let is_reg = m == "register";
        let is_reset = m == "reset";
        let challenge_blocks = is_px
            && ((is_login && snap.login_challenge_required)
                || (is_reg && snap.register_challenge_required)
                || (is_reset && snap.reset_challenge_required));

        self.form_group.set_visible(!challenge_blocks);
        self.submit_btn.set_visible(!challenge_blocks);
        self.password_row.set_visible(!is_reset);
        self.password_row
            .set_title(if is_reg && is_px { "密码（至少 10 位）" } else { "密码" });
        self.mfa_row.set_visible(is_login && snap.mfa_required);
        self.code_row
            .set_visible(is_reg && snap.email_verify_required && !is_px);
        self.invite_row
            .set_title(if is_px { "推广码（选填）" } else { "邀请码（选填）" });
        self.invite_row.set_visible(is_reg);
        self.reset_token_row.set_visible(is_reset);
        self.reset_pw_row.set_visible(is_reset);

        let sig = format!(
            "{m}:{is_px}:{}:{}:{}:{}:{}",
            snap.login_challenge_required,
            snap.register_challenge_required,
            snap.reset_challenge_required,
            snap.browser_login_pending,
            snap.oauth_providers
                .iter()
                .map(|p| format!("{}:{}", p.id, p.label))
                .collect::<Vec<_>>()
                .join(",")
        );
        if *self.last_provider_sig.borrow() != sig {
            *self.last_provider_sig.borrow_mut() = sig;
            while let Some(c) = self.browser_box.first_child() {
                self.browser_box.remove(&c);
            }
            if snap.browser_login_pending {
                let cancel_btn = gtk::Button::with_label("取消浏览器授权");
                let p = self.panel.clone();
                cancel_btn.connect_clicked(move |_| {
                    p.cancel_browser_login();
                });
                self.browser_box.append(&cancel_btn);
            } else if is_reset {
                if is_px && snap.reset_challenge_required && !snap.base_url.is_empty() {
                    let reset_web_btn = gtk::Button::with_label("在浏览器中找回密码");
                    reset_web_btn.add_css_class("suggested-action");
                    let p = self.panel.clone();
                    let rt = self.rt.clone();
                    let target = format!("{}/forgot", snap.base_url);
                    reset_web_btn.connect_clicked(move |_| {
                        let p = p.clone();
                        let u = target.clone();
                        rt.spawn(async move {
                            let _ = p.dispatch_json("open_url", serde_json::json!({ "url": u })).await;
                        });
                    });
                    self.browser_box.append(&reset_web_btn);
                }
            } else {
                if is_px {
                    let lbl = if is_reg && snap.register_challenge_required {
                        "在浏览器中完成注册与登录"
                    } else if challenge_blocks {
                        "浏览器登录"
                    } else {
                        "浏览器授权登录"
                    };
                    let web_btn = gtk::Button::with_label(lbl);
                    if challenge_blocks {
                        web_btn.add_css_class("suggested-action");
                    }
                    let p = self.panel.clone();
                    let rt = self.rt.clone();
                    let mlbl = self.msg_label.clone();
                    web_btn.connect_clicked(move |_| {
                        spawn_browser_auth(p.clone(), rt.clone(), None, mlbl.clone());
                    });
                    self.browser_box.append(&web_btn);
                }
                for prov in &snap.oauth_providers {
                    let btn = gtk::Button::with_label(&format!("{} 登录", prov.label));
                    let pid = prov.id.clone();
                    let p = self.panel.clone();
                    let rt = self.rt.clone();
                    let mlbl = self.msg_label.clone();
                    btn.connect_clicked(move |_| {
                        spawn_browser_auth(p.clone(), rt.clone(), Some(pid.clone()), mlbl.clone());
                    });
                    self.browser_box.append(&btn);
                }
            }
        }
    }
}

fn spawn_browser_auth(
    panel: Arc<XbPanelClient>,
    rt: Handle,
    provider: Option<String>,
    msg_label: gtk::Label,
) {
    msg_label.remove_css_class("error");
    msg_label.set_text("请在已打开的系统浏览器中完成确认。");
    msg_label.set_visible(true);
    let (tx, rx) = std::sync::mpsc::channel();
    rt.spawn(async move {
        let res = panel.browser_login(provider.as_deref()).await.map_err(|e| format!("{e:#}"));
        let _ = tx.send(res);
    });
    gtk::glib::timeout_add_local(Duration::from_millis(200), move || match rx.try_recv() {
        Ok(Ok(_)) => gtk::glib::ControlFlow::Break,
        Ok(Err(err)) => {
            msg_label.add_css_class("error");
            msg_label.set_text(&err);
            msg_label.set_visible(true);
            gtk::glib::ControlFlow::Break
        }
        Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
        Err(_) => gtk::glib::ControlFlow::Break,
    });
}
