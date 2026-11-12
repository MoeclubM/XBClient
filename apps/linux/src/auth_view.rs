#![cfg(target_os = "linux")]

use adw::prelude::*;
use gtk::prelude::*;
use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;
use xbclient_panel::{PanelStateSnapshot, XbPanelClient};

pub struct AuthView {
    pub root: gtk::ScrolledWindow,
    app_title: gtk::Label,
    code_row: adw::EntryRow,
    invite_row: adw::EntryRow,
    submit_btn: gtk::Button,
    browser_box: gtk::Box,
    msg_label: gtk::Label,
    register_mode: Rc<Cell<bool>>,
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
        reg_toggle.set_group(Some(&login_toggle));
        login_toggle.set_active(true);
        login_toggle.set_hexpand(true);
        reg_toggle.set_hexpand(true);
        mode_box.append(&login_toggle);
        mode_box.append(&reg_toggle);

        let form_group = adw::PreferencesGroup::new();
        let email_row = adw::EntryRow::builder().title("邮箱").build();
        let password_row = adw::PasswordEntryRow::builder().title("密码").build();
        let code_row = adw::EntryRow::builder().title("邮箱验证码").build();
        let send_code_btn = gtk::Button::with_label("发送验证码");
        send_code_btn.add_css_class("flat");
        send_code_btn.set_valign(gtk::Align::Center);
        code_row.add_suffix(&send_code_btn);
        code_row.set_visible(false);
        let invite_row = adw::EntryRow::builder().title("邀请码（选填）").build();
        invite_row.set_visible(false);

        form_group.add(&email_row);
        form_group.add(&password_row);
        form_group.add(&code_row);
        form_group.add(&invite_row);

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

        let register_mode = Rc::new(Cell::new(false));
        let view = Rc::new(Self {
            root: scroll,
            app_title,
            code_row: code_row.clone(),
            invite_row: invite_row.clone(),
            submit_btn: submit_btn.clone(),
            browser_box,
            msg_label: msg_label.clone(),
            register_mode: register_mode.clone(),
            last_provider_sig: RefCell::new(String::new()),
            panel: panel.clone(),
            rt: rt.clone(),
        });

        {
            let v = view.clone();
            login_toggle.connect_toggled(move |btn| {
                if btn.is_active() {
                    v.register_mode.set(false);
                    v.submit_btn.set_label("登录");
                    v.apply_snapshot(&v.panel.snapshot());
                }
            });
        }
        {
            let v = view.clone();
            reg_toggle.connect_toggled(move |btn| {
                if btn.is_active() {
                    v.register_mode.set(true);
                    v.submit_btn.set_label("注册并登录");
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
            let p = panel;
            let handle = rt;
            let reg = register_mode;
            let erow = email_row;
            let prow = password_row;
            let crow = code_row;
            let irow = invite_row;
            let mlbl = msg_label;
            submit_btn.connect_clicked(move |btn| {
                let email = erow.text().trim().to_string();
                let pw = prow.text().to_string();
                if email.is_empty() || pw.is_empty() {
                    return;
                }
                let code = crow.text().trim().to_string();
                let invite = irow.text().trim().to_string();
                let is_reg = reg.get();
                btn.set_sensitive(false);
                mlbl.set_visible(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                handle.spawn(async move {
                    let res = if is_reg {
                        p.register_account(&email, &pw, &invite, &code).await
                    } else {
                        p.login_password(&email, &pw, None).await
                    };
                    let _ = tx.send(res.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let mlbl_c = mlbl.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(_)) => {
                        btn_c.set_sensitive(true);
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

        view
    }

    pub fn apply_snapshot(&self, snap: &PanelStateSnapshot) {
        self.app_title.set_text(if snap.app_name.is_empty() { "XBClient" } else { &snap.app_name });
        let is_px = snap.panel_type == "pxboard";
        let is_reg = self.register_mode.get();
        self.code_row.set_visible(is_reg && (snap.email_verify_required || is_px));
        self.invite_row.set_visible(is_reg);

        let sig = format!(
            "{is_px}:{}:{}",
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
            } else {
                if is_px {
                    let web_btn = gtk::Button::with_label("浏览器授权登录");
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
