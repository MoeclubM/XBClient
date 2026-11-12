#![cfg(target_os = "linux")]

use crate::account_view::{AccountView, build_copy_row};
use adw::prelude::*;
use std::collections::HashSet;
use std::rc::Rc;
use std::time::Duration;
use xbclient_panel::PanelStateSnapshot;

impl AccountView {
    pub(crate) fn append_mfa_section(self: &Rc<Self>, snap: &PanelStateSnapshot) {
        if snap.mfa_enabled {
            *self.mfa_setup.borrow_mut() = None;
            let grp = adw::PreferencesGroup::builder()
                .title("两步验证")
                .description("已启用 · 登录和敏感操作需输入 6 位动态验证码")
                .build();
            let pw_row = adw::PasswordEntryRow::builder().title("当前登录密码").build();
            let code_row = adw::EntryRow::builder().title("6 位动态验证码").build();
            let disable_btn = gtk::Button::with_label("关闭两步验证");
            disable_btn.add_css_class("destructive-action");
            disable_btn.set_valign(gtk::Align::Center);
            code_row.add_suffix(&disable_btn);
            grp.add(&pw_row);
            grp.add(&code_row);

            let p = self.panel.clone();
            let h = self.rt.clone();
            let flbl = self.feedback_label.clone();
            let view_c = self.clone();
            disable_btn.connect_clicked(move |btn| {
                let pw = pw_row.text().to_string();
                let code = code_row.text().trim().to_string();
                if pw.is_empty() || code.len() != 6 {
                    return;
                }
                btn.set_sensitive(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                h.spawn(async move {
                    let _ = tx.send(p.mfa_disable(&pw, &code).await.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let flbl_c = flbl.clone();
                let v_c = view_c.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(next_snap)) => {
                        btn_c.set_sensitive(true);
                        flbl_c.remove_css_class("error");
                        flbl_c.set_text("已关闭两步验证。");
                        flbl_c.set_visible(true);
                        v_c.apply_snapshot(&next_snap);
                        gtk::glib::ControlFlow::Break
                    }
                    Ok(Err(err)) => {
                        btn_c.set_sensitive(true);
                        flbl_c.add_css_class("error");
                        flbl_c.set_text(&err);
                        flbl_c.set_visible(true);
                        gtk::glib::ControlFlow::Break
                    }
                    Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
                    Err(_) => {
                        btn_c.set_sensitive(true);
                        gtk::glib::ControlFlow::Break
                    }
                });
            });
            self.security_box.append(&grp);
            return;
        }

        if let Some(setup) = self.mfa_setup.borrow().clone() {
            let grp = adw::PreferencesGroup::builder()
                .title("两步验证")
                .description("在验证器中添加密钥或导入链接，再输入生成的 6 位验证码完成启用")
                .build();
            grp.add(&build_copy_row("验证器密钥", &setup.secret));
            if !setup.otpauth_url.is_empty() {
                grp.add(&build_copy_row("导入链接", &setup.otpauth_url));
            }
            let pw_row = adw::PasswordEntryRow::builder().title("当前登录密码").build();
            let code_row = adw::EntryRow::builder().title("6 位动态验证码").build();
            let cancel_btn = gtk::Button::with_label("取消");
            cancel_btn.add_css_class("flat");
            cancel_btn.set_valign(gtk::Align::Center);
            let confirm_btn = gtk::Button::with_label("确认启用");
            confirm_btn.add_css_class("suggested-action");
            confirm_btn.set_valign(gtk::Align::Center);
            code_row.add_suffix(&cancel_btn);
            code_row.add_suffix(&confirm_btn);
            grp.add(&pw_row);
            grp.add(&code_row);

            {
                let setup_holder = self.mfa_setup.clone();
                let view_c = self.clone();
                let p = self.panel.clone();
                cancel_btn.connect_clicked(move |_| {
                    *setup_holder.borrow_mut() = None;
                    *view_c.last_sig.borrow_mut() = String::new();
                    view_c.apply_snapshot(&p.snapshot());
                });
            }
            {
                let p = self.panel.clone();
                let h = self.rt.clone();
                let flbl = self.feedback_label.clone();
                let setup_holder = self.mfa_setup.clone();
                let view_c = self.clone();
                confirm_btn.connect_clicked(move |btn| {
                    let pw = pw_row.text().to_string();
                    let code = code_row.text().trim().to_string();
                    if pw.is_empty() || code.len() != 6 {
                        return;
                    }
                    btn.set_sensitive(false);
                    let p = p.clone();
                    let (tx, rx) = std::sync::mpsc::channel();
                    h.spawn(async move {
                        let _ = tx.send(p.mfa_enable(&pw, &code).await.map_err(|e| format!("{e:#}")));
                    });
                    let btn_c = btn.clone();
                    let flbl_c = flbl.clone();
                    let holder_c = setup_holder.clone();
                    let v_c = view_c.clone();
                    gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                        Ok(Ok(next_snap)) => {
                            btn_c.set_sensitive(true);
                            *holder_c.borrow_mut() = None;
                            *v_c.last_sig.borrow_mut() = String::new();
                            flbl_c.remove_css_class("error");
                            flbl_c.set_text("已启用两步验证。");
                            flbl_c.set_visible(true);
                            v_c.apply_snapshot(&next_snap);
                            gtk::glib::ControlFlow::Break
                        }
                        Ok(Err(err)) => {
                            btn_c.set_sensitive(true);
                            flbl_c.add_css_class("error");
                            flbl_c.set_text(&err);
                            flbl_c.set_visible(true);
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
            self.security_box.append(&grp);
            return;
        }

        let grp = adw::PreferencesGroup::builder()
            .title("两步验证")
            .description("未启用 · 使用身份验证器生成 6 位动态验证码")
            .build();
        let setup_row = adw::ActionRow::builder()
            .title("验证器动态口令（TOTP）")
            .build();
        setup_row.set_use_markup(false);
        let setup_btn = gtk::Button::with_label("设置两步验证");
        setup_btn.add_css_class("suggested-action");
        setup_btn.set_valign(gtk::Align::Center);
        setup_row.add_suffix(&setup_btn);
        grp.add(&setup_row);

        let p = self.panel.clone();
        let h = self.rt.clone();
        let flbl = self.feedback_label.clone();
        let setup_holder = self.mfa_setup.clone();
        let view_c = self.clone();
        setup_btn.connect_clicked(move |btn| {
            btn.set_sensitive(false);
            let p_c = p.clone();
            let (tx, rx) = std::sync::mpsc::channel();
            h.spawn(async move {
                let _ = tx.send(p_c.mfa_setup().await.map_err(|e| format!("{e:#}")));
            });
            let btn_c = btn.clone();
            let flbl_c = flbl.clone();
            let holder_c = setup_holder.clone();
            let v_c = view_c.clone();
            let p_snap = p.clone();
            gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                Ok(Ok(info)) => {
                    btn_c.set_sensitive(true);
                    *holder_c.borrow_mut() = Some(info);
                    *v_c.last_sig.borrow_mut() = String::new();
                    v_c.apply_snapshot(&p_snap.snapshot());
                    gtk::glib::ControlFlow::Break
                }
                Ok(Err(err)) => {
                    btn_c.set_sensitive(true);
                    flbl_c.add_css_class("error");
                    flbl_c.set_text(&err);
                    flbl_c.set_visible(true);
                    gtk::glib::ControlFlow::Break
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
                Err(_) => {
                    btn_c.set_sensitive(true);
                    gtk::glib::ControlFlow::Break
                }
            });
        });
        self.security_box.append(&grp);
    }

    pub(crate) fn append_oauth_section(self: &Rc<Self>, snap: &PanelStateSnapshot) {
        let grp = adw::PreferencesGroup::builder()
            .title("第三方账号绑定")
            .build();
        let bound_ids: HashSet<&str> = snap
            .oauth_bindings
            .iter()
            .map(|b| b.provider_id.as_str())
            .collect();

        let mut unbind_pw_row: Option<adw::PasswordEntryRow> = None;
        let mut unbind_code_row: Option<adw::EntryRow> = None;
        if snap.oauth_bindings.iter().any(|b| b.requires_password) {
            let pw_r = adw::PasswordEntryRow::builder()
                .title("解绑最后一个登录方式时需输入当前密码")
                .build();
            grp.add(&pw_r);
            unbind_pw_row = Some(pw_r);
            if snap.mfa_enabled {
                let code_r = adw::EntryRow::builder().title("6 位动态验证码").build();
                grp.add(&code_r);
                unbind_code_row = Some(code_r);
            }
        }

        for b in &snap.oauth_bindings {
            let title = if b.provider_name.is_empty() {
                &b.provider_id
            } else {
                &b.provider_name
            };
            let subtitle = if b.display_name.is_empty() {
                "已绑定"
            } else {
                &b.display_name
            };
            let row = adw::ActionRow::builder()
                .title(title)
                .subtitle(subtitle)
                .build();
            row.set_use_markup(false);
            let unbind_btn = gtk::Button::with_label("解除绑定");
            unbind_btn.add_css_class("flat");
            unbind_btn.set_valign(gtk::Align::Center);
            row.add_suffix(&unbind_btn);
            grp.add(&row);

            let bid = b.id.clone();
            let req_pw = b.requires_password;
            let pw_r = unbind_pw_row.clone();
            let code_r = unbind_code_row.clone();
            let p = self.panel.clone();
            let h = self.rt.clone();
            let flbl = self.feedback_label.clone();
            let view_c = self.clone();
            unbind_btn.connect_clicked(move |btn| {
                let pw = pw_r.as_ref().map(|r| r.text().to_string()).unwrap_or_default();
                let code = code_r
                    .as_ref()
                    .map(|r| r.text().trim().to_string())
                    .unwrap_or_default();
                if req_pw && pw.is_empty() {
                    flbl.add_css_class("error");
                    flbl.set_text("解除最后一个登录绑定前请输入当前登录密码。");
                    flbl.set_visible(true);
                    return;
                }
                btn.set_sensitive(false);
                let p = p.clone();
                let bid = bid.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                h.spawn(async move {
                    let _ = tx.send(
                        p.unbind_oauth(&bid, &pw, &code)
                            .await
                            .map_err(|e| format!("{e:#}")),
                    );
                });
                let btn_c = btn.clone();
                let flbl_c = flbl.clone();
                let v_c = view_c.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(next_snap)) => {
                        btn_c.set_sensitive(true);
                        flbl_c.remove_css_class("error");
                        flbl_c.set_text("已解除第三方账号绑定。");
                        flbl_c.set_visible(true);
                        v_c.apply_snapshot(&next_snap);
                        gtk::glib::ControlFlow::Break
                    }
                    Ok(Err(err)) => {
                        btn_c.set_sensitive(true);
                        flbl_c.add_css_class("error");
                        flbl_c.set_text(&err);
                        flbl_c.set_visible(true);
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

        if snap.oauth_bind_pending {
            let pending_row = adw::ActionRow::builder()
                .title("正在等待浏览器完成授权")
                .build();
            pending_row.set_use_markup(false);
            let cancel_btn = gtk::Button::with_label("取消绑定");
            cancel_btn.set_valign(gtk::Align::Center);
            let p = self.panel.clone();
            let view_c = self.clone();
            cancel_btn.connect_clicked(move |_| {
                let next_snap = p.cancel_bind_oauth();
                view_c.apply_snapshot(&next_snap);
            });
            pending_row.add_suffix(&cancel_btn);
            grp.add(&pending_row);
            self.security_box.append(&grp);
            return;
        }

        for prov in snap
            .oauth_providers
            .iter()
            .filter(|p| !bound_ids.contains(p.id.as_str()))
        {
            let row = adw::ActionRow::builder()
                .title(&prov.label)
                .subtitle("未绑定")
                .build();
            row.set_use_markup(false);
            let bind_btn = gtk::Button::with_label("绑定");
            bind_btn.add_css_class("suggested-action");
            bind_btn.set_valign(gtk::Align::Center);
            row.add_suffix(&bind_btn);
            grp.add(&row);

            let pid = prov.id.clone();
            let p = self.panel.clone();
            let h = self.rt.clone();
            let flbl = self.feedback_label.clone();
            let view_c = self.clone();
            bind_btn.connect_clicked(move |btn| {
                btn.set_sensitive(false);
                flbl.remove_css_class("error");
                flbl.set_text("请在已打开的系统浏览器中完成第三方账号授权。");
                flbl.set_visible(true);
                let p_c = p.clone();
                let pid = pid.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                h.spawn(async move {
                    let _ = tx.send(p_c.bind_oauth(&pid).await.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let flbl_c = flbl.clone();
                let v_c = view_c.clone();
                let p_snap = p.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(200), move || match rx.try_recv() {
                    Ok(Ok(next_snap)) => {
                        btn_c.set_sensitive(true);
                        flbl_c.remove_css_class("error");
                        flbl_c.set_text("第三方账号已绑定。");
                        flbl_c.set_visible(true);
                        v_c.apply_snapshot(&next_snap);
                        gtk::glib::ControlFlow::Break
                    }
                    Ok(Err(err)) => {
                        btn_c.set_sensitive(true);
                        flbl_c.add_css_class("error");
                        flbl_c.set_text(&err);
                        flbl_c.set_visible(true);
                        v_c.apply_snapshot(&p_snap.snapshot());
                        gtk::glib::ControlFlow::Break
                    }
                    Err(std::sync::mpsc::TryRecvError::Empty) => {
                        v_c.apply_snapshot(&p_snap.snapshot());
                        gtk::glib::ControlFlow::Continue
                    }
                    Err(_) => {
                        btn_c.set_sensitive(true);
                        gtk::glib::ControlFlow::Break
                    }
                });
            });
        }
        self.security_box.append(&grp);
    }
}
