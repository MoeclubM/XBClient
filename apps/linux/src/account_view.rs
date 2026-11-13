#![cfg(target_os = "linux")]

use adw::prelude::*;
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;
use xbclient_panel::{
    MfaSetupInfo, PanelStateSnapshot, TicketMessage, XbPanelClient, format_money_cents,
};

pub struct AccountView {
    pub root: gtk::Box,
    stack: gtk::Stack,
    email_row: adw::ActionRow,
    portal_btn: gtk::Button,
    balance_row: adw::ActionRow,
    sub_group: adw::PreferencesGroup,
    billing_nav: adw::ActionRow,
    invite_nav: adw::ActionRow,
    tickets_nav: adw::ActionRow,
    security_nav: adw::ActionRow,
    billing_title: gtk::Label,
    recharge_group: adw::PreferencesGroup,
    recharge_channel_dropdown: gtk::DropDown,
    recharge_channel_ids: Rc<RefCell<Vec<String>>>,
    redeem_group: adw::PreferencesGroup,
    pub(crate) feedback_label: gtk::Label,
    invite_box: gtk::Box,
    tickets_box: gtk::Box,
    ticket_detail_box: gtk::Box,
    new_ticket_group: adw::PreferencesGroup,
    pw_mfa_row: adw::EntryRow,
    new_pw_row: adw::PasswordEntryRow,
    pub(crate) security_box: gtk::Box,
    pub(crate) mfa_setup: Rc<RefCell<Option<MfaSetupInfo>>>,
    pub(crate) last_sig: RefCell<String>,
    pub(crate) panel: Arc<XbPanelClient>,
    pub(crate) rt: Handle,
}

fn page_shell(content: &gtk::Box) -> gtk::ScrolledWindow {
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vexpand(true)
        .build();
    let clamp = adw::Clamp::builder()
        .maximum_size(760)
        .margin_top(12)
        .margin_bottom(20)
        .margin_start(14)
        .margin_end(14)
        .build();
    clamp.set_child(Some(content));
    scroll.set_child(Some(&clamp));
    scroll
}

fn subpage(stack: &gtk::Stack, name: &str, title: &str, feedback: &gtk::Label) -> (gtk::Box, gtk::Label) {
    let content = gtk::Box::new(gtk::Orientation::Vertical, 12);
    let bar = gtk::Box::new(gtk::Orientation::Horizontal, 8);
    let back_btn = gtk::Button::from_icon_name("go-previous-symbolic");
    back_btn.add_css_class("flat");
    let title_lbl = gtk::Label::new(Some(title));
    title_lbl.add_css_class("heading");
    bar.append(&back_btn);
    bar.append(&title_lbl);
    content.append(&bar);
    {
        let st = stack.clone();
        let fb = feedback.clone();
        back_btn.connect_clicked(move |_| {
            fb.set_visible(false);
            st.set_visible_child_name("root");
        });
    }
    stack.add_named(&page_shell(&content), Some(name));
    (content, title_lbl)
}

fn nav_row(stack: &gtk::Stack, group: &adw::PreferencesGroup, title: &str, target: &'static str, feedback: &gtk::Label) -> adw::ActionRow {
    let row = adw::ActionRow::builder().title(title).activatable(true).build();
    row.set_use_markup(false);
    row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
    let st = stack.clone();
    let fb = feedback.clone();
    row.connect_activated(move |_| {
        fb.set_visible(false);
        st.set_visible_child_name(target);
    });
    group.add(&row);
    row
}

impl AccountView {
    pub fn new(panel: Arc<XbPanelClient>, rt: Handle) -> Rc<Self> {
        let stack = gtk::Stack::new();
        stack.set_transition_type(gtk::StackTransitionType::SlideLeftRight);
        let outer = gtk::Box::new(gtk::Orientation::Vertical, 12);

        let feedback_label = gtk::Label::new(None);
        feedback_label.set_wrap(true);
        feedback_label.set_xalign(0.0);
        feedback_label.set_visible(false);
        feedback_label.set_margin_top(8);
        feedback_label.set_margin_start(18);
        feedback_label.set_margin_end(18);

        // Account overview
        let acc_group = adw::PreferencesGroup::builder().title("账户信息").build();
        let email_row = adw::ActionRow::new();
        email_row.set_use_markup(false);
        let sync_btn = gtk::Button::with_label("同步");
        sync_btn.add_css_class("flat");
        sync_btn.set_valign(gtk::Align::Center);
        let portal_btn = gtk::Button::with_label("网页中心");
        portal_btn.add_css_class("flat");
        portal_btn.set_valign(gtk::Align::Center);
        let logout_btn = gtk::Button::with_label("退出");
        logout_btn.add_css_class("destructive-action");
        logout_btn.set_valign(gtk::Align::Center);
        email_row.add_suffix(&sync_btn);
        email_row.add_suffix(&portal_btn);
        email_row.add_suffix(&logout_btn);

        let balance_row = adw::ActionRow::builder().title("资金与佣金").build();
        balance_row.set_use_markup(false);
        acc_group.add(&email_row);
        acc_group.add(&balance_row);

        let sub_group = adw::PreferencesGroup::new();
        sub_group.set_visible(false);
        let sub_row = adw::ActionRow::builder().title("订阅地址").build();
        let sub_copy_btn = gtk::Button::with_label("复制");
        sub_copy_btn.add_css_class("flat");
        sub_copy_btn.set_valign(gtk::Align::Center);
        sub_row.add_suffix(&sub_copy_btn);
        sub_group.add(&sub_row);
        {
            let p = panel.clone();
            sub_copy_btn.connect_clicked(move |_| {
                if let Some(display) = gtk::gdk::Display::default() {
                    display.clipboard().set_text(&p.snapshot().subscribe_url);
                }
            });
        }

        let nav_group = adw::PreferencesGroup::new();
        let billing_nav = nav_row(&stack, &nav_group, "充值与兑换", "billing", &feedback_label);
        let invite_nav = nav_row(&stack, &nav_group, "邀请返利", "invite", &feedback_label);
        let tickets_nav = nav_row(&stack, &nav_group, "工单", "tickets", &feedback_label);
        let security_nav = nav_row(&stack, &nav_group, "账户安全", "security", &feedback_label);

        let recharge_group = adw::PreferencesGroup::builder().title("余额充值").build();
        recharge_group.set_visible(false);
        let recharge_amount_row = adw::EntryRow::builder().title("充值金额（如 20）").build();
        let recharge_ch_row = adw::ActionRow::builder().title("支付渠道").build();
        let recharge_channel_dropdown = gtk::DropDown::from_strings(&[]);
        recharge_channel_dropdown.set_valign(gtk::Align::Center);
        let recharge_btn = gtk::Button::with_label("充值");
        recharge_btn.add_css_class("suggested-action");
        recharge_btn.set_valign(gtk::Align::Center);
        recharge_ch_row.add_suffix(&recharge_channel_dropdown);
        recharge_ch_row.add_suffix(&recharge_btn);
        recharge_group.add(&recharge_amount_row);
        recharge_group.add(&recharge_ch_row);
        let recharge_channel_ids: Rc<RefCell<Vec<String>>> = Rc::new(RefCell::new(Vec::new()));

        // Redeem group
        let redeem_group = adw::PreferencesGroup::builder().title("兑换码").build();
        let redeem_row = adw::EntryRow::builder().title("礼品卡 / 兑换码").build();
        let redeem_btn = gtk::Button::with_label("兑换");
        redeem_btn.add_css_class("suggested-action");
        redeem_btn.set_valign(gtk::Align::Center);
        redeem_row.add_suffix(&redeem_btn);
        redeem_group.add(&redeem_row);

        let invite_box = gtk::Box::new(gtk::Orientation::Vertical, 12);
        let tickets_box = gtk::Box::new(gtk::Orientation::Vertical, 12);
        let ticket_detail_box = gtk::Box::new(gtk::Orientation::Vertical, 8);

        // New ticket group
        let new_ticket_group = adw::PreferencesGroup::builder().title("新建工单").build();
        let subj_row = adw::EntryRow::builder().title("工单主题").build();
        let body_row = adw::EntryRow::builder().title("问题描述").build();
        let submit_ticket_btn = gtk::Button::with_label("提交");
        submit_ticket_btn.add_css_class("suggested-action");
        submit_ticket_btn.set_valign(gtk::Align::Center);
        body_row.add_suffix(&submit_ticket_btn);
        new_ticket_group.add(&subj_row);
        new_ticket_group.add(&body_row);

        // Password change
        let pw_group = adw::PreferencesGroup::builder().title("修改密码").build();
        let old_pw_row = adw::PasswordEntryRow::builder().title("当前密码").build();
        let pw_mfa_row = adw::EntryRow::builder()
            .title("动态验证码（已启用时填写）")
            .build();
        pw_mfa_row.set_visible(false);
        let new_pw_row = adw::PasswordEntryRow::builder().title("新密码").build();
        let save_pw_btn = gtk::Button::with_label("更新");
        save_pw_btn.set_valign(gtk::Align::Center);
        new_pw_row.add_suffix(&save_pw_btn);
        pw_group.add(&old_pw_row);
        pw_group.add(&pw_mfa_row);
        pw_group.add(&new_pw_row);

        let security_box = gtk::Box::new(gtk::Orientation::Vertical, 12);

        outer.append(&acc_group);
        outer.append(&sub_group);
        outer.append(&nav_group);
        stack.add_named(&page_shell(&outer), Some("root"));

        let (billing_page, billing_title) = subpage(&stack, "billing", "充值与兑换", &feedback_label);
        billing_page.append(&recharge_group);
        billing_page.append(&redeem_group);
        let (invite_page, _) = subpage(&stack, "invite", "邀请返利", &feedback_label);
        invite_page.append(&invite_box);
        let (tickets_page, _) = subpage(&stack, "tickets", "工单", &feedback_label);
        tickets_page.append(&new_ticket_group);
        tickets_page.append(&tickets_box);
        tickets_page.append(&ticket_detail_box);
        let (security_page, _) = subpage(&stack, "security", "账户安全", &feedback_label);
        security_page.append(&pw_group);
        security_page.append(&security_box);
        stack.set_visible_child_name("root");
        let root = gtk::Box::new(gtk::Orientation::Vertical, 0);
        root.append(&feedback_label);
        root.append(&stack);

        {
            let p = panel.clone();
            let h = rt.clone();
            sync_btn.connect_clicked(move |_| {
                let p = p.clone();
                h.spawn(async move {
                    let _ = p.sync_all().await;
                });
            });
        }
        {
            let p = panel.clone();
            let h = rt.clone();
            portal_btn.connect_clicked(move |_| {
                let p = p.clone();
                h.spawn(async move {
                    let _ = p.open_portal("/user").await;
                });
            });
        }
        {
            let p = panel.clone();
            let h = rt.clone();
            logout_btn.connect_clicked(move |_| {
                let p = p.clone();
                h.spawn(async move {
                    let _ = p.logout().await;
                });
            });
        }
        {
            let p = panel.clone();
            let h = rt.clone();
            let amt_row = recharge_amount_row;
            let dd = recharge_channel_dropdown.clone();
            let ch_ids = recharge_channel_ids.clone();
            let flbl = feedback_label.clone();
            recharge_btn.connect_clicked(move |btn| {
                let raw_amt = amt_row.text().trim().to_string();
                let Ok(amount) = raw_amt.parse::<f64>() else {
                    flbl.add_css_class("error");
                    flbl.set_text("请输入有效的充值金额（最低 1.00）。");
                    flbl.set_visible(true);
                    return;
                };
                if amount < 1.0 {
                    flbl.add_css_class("error");
                    flbl.set_text("充值金额最低 1.00。");
                    flbl.set_visible(true);
                    return;
                }
                let idx = dd.selected() as usize;
                let Some(ch_id) = ch_ids.borrow().get(idx).cloned() else {
                    return;
                };
                let cents = (amount * 100.0).round() as i64;
                btn.set_sensitive(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                h.spawn(async move {
                    let _ = tx.send(
                        p.recharge_wallet(cents, &ch_id)
                            .await
                            .map_err(|e| format!("{e:#}")),
                    );
                });
                let btn_c = btn.clone();
                let amt_c = amt_row.clone();
                let flbl_c = flbl.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(_)) => {
                        btn_c.set_sensitive(true);
                        amt_c.set_text("");
                        flbl_c.remove_css_class("error");
                        flbl_c.set_text("已打开外部收银台，完成支付后请点击同步。");
                        flbl_c.set_visible(true);
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
        {
            let p = panel.clone();
            let h = rt.clone();
            let rrow = redeem_row;
            let flbl = feedback_label.clone();
            redeem_btn.connect_clicked(move |btn| {
                let code = rrow.text().trim().to_string();
                if code.is_empty() {
                    return;
                }
                btn.set_sensitive(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                h.spawn(async move {
                    let _ = tx.send(p.redeem_code(&code).await.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let rrow_c = rrow.clone();
                let flbl_c = flbl.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok((msg, _))) => {
                        btn_c.set_sensitive(true);
                        rrow_c.set_text("");
                        flbl_c.remove_css_class("error");
                        flbl_c.set_text(&msg);
                        flbl_c.set_visible(true);
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
        {
            let p = panel.clone();
            let h = rt.clone();
            let srow = subj_row;
            let brow = body_row;
            let flbl = feedback_label.clone();
            submit_ticket_btn.connect_clicked(move |btn| {
                let subj = srow.text().trim().to_string();
                let body = brow.text().trim().to_string();
                if subj.is_empty() || body.is_empty() {
                    return;
                }
                btn.set_sensitive(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                h.spawn(async move {
                    let _ = tx.send(p.create_ticket(&subj, &body).await.map_err(|e| format!("{e:#}")));
                });
                let btn_c = btn.clone();
                let srow_c = srow.clone();
                let brow_c = brow.clone();
                let flbl_c = flbl.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(_)) => {
                        btn_c.set_sensitive(true);
                        srow_c.set_text("");
                        brow_c.set_text("");
                        flbl_c.remove_css_class("error");
                        flbl_c.set_text("工单已提交。");
                        flbl_c.set_visible(true);
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
        {
            let p = panel.clone();
            let h = rt.clone();
            let orow = old_pw_row;
            let mrow = pw_mfa_row.clone();
            let nrow = new_pw_row.clone();
            let flbl = feedback_label.clone();
            save_pw_btn.connect_clicked(move |btn| {
                let old_pw = orow.text().to_string();
                let mfa_code = mrow.text().trim().to_string();
                let new_pw = nrow.text().to_string();
                if old_pw.is_empty() || new_pw.is_empty() {
                    return;
                }
                btn.set_sensitive(false);
                let p = p.clone();
                let (tx, rx) = std::sync::mpsc::channel();
                h.spawn(async move {
                    let code_opt = (!mfa_code.is_empty()).then_some(mfa_code.as_str());
                    let _ = tx.send(
                        p.change_password(&old_pw, &new_pw, code_opt)
                            .await
                            .map_err(|e| format!("{e:#}")),
                    );
                });
                let btn_c = btn.clone();
                let orow_c = orow.clone();
                let mrow_c = mrow.clone();
                let nrow_c = nrow.clone();
                let flbl_c = flbl.clone();
                gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                    Ok(Ok(_)) => {
                        btn_c.set_sensitive(true);
                        orow_c.set_text("");
                        mrow_c.set_text("");
                        nrow_c.set_text("");
                        flbl_c.remove_css_class("error");
                        flbl_c.set_text("密码已更新。");
                        flbl_c.set_visible(true);
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

        Rc::new(Self {
            root,
            stack,
            email_row,
            portal_btn,
            balance_row,
            sub_group,
            billing_nav,
            invite_nav,
            tickets_nav,
            security_nav,
            billing_title,
            recharge_group,
            recharge_channel_dropdown,
            recharge_channel_ids,
            redeem_group,
            feedback_label,
            invite_box,
            tickets_box,
            ticket_detail_box,
            new_ticket_group,
            pw_mfa_row,
            new_pw_row,
            security_box,
            mfa_setup: Rc::new(RefCell::new(None)),
            last_sig: RefCell::new(String::new()),
            panel,
            rt,
        })
    }

    pub fn reset_to_root(&self) {
        self.feedback_label.set_visible(false);
        self.stack.set_visible_child_name("root");
    }

    pub fn apply_snapshot(self: &Rc<Self>, snap: &PanelStateSnapshot) {
        let is_px = snap.panel_type == "pxboard";
        self.portal_btn.set_visible(snap.payment_enabled);
        self.new_pw_row
            .set_title(if is_px { "新密码（至少 10 位）" } else { "新密码" });
        self.pw_mfa_row.set_visible(is_px && snap.mfa_enabled);

        let has_ext = |name: &str| snap.extensions.iter().any(|e| e == name);
        let show_redeem = !is_px || has_ext("redeem");
        let show_invite = !is_px || has_ext("affiliate");
        let show_tickets = !is_px || has_ext("support");
        let show_recharge =
            is_px && snap.payment_enabled && has_ext("commerce") && !snap.payment_channels.is_empty();
        let show_oauth = is_px
            && has_ext("oauth")
            && (!snap.oauth_providers.is_empty() || !snap.oauth_bindings.is_empty());

        self.redeem_group.set_visible(show_redeem);
        self.recharge_group.set_visible(show_recharge);
        self.new_ticket_group.set_visible(show_tickets);
        self.billing_nav.set_visible(show_redeem || show_recharge);
        self.invite_nav.set_visible(show_invite);
        self.tickets_nav.set_visible(show_tickets);
        let billing_title = match (show_recharge, show_redeem) {
            (true, true) => "充值与兑换",
            (true, false) => "余额充值",
            _ => "兑换码",
        };
        self.billing_nav.set_title(billing_title);
        self.billing_title.set_text(billing_title);
        let money = |v| format_money_cents(v, &snap.currency_symbol, &snap.currency_unit);
        self.billing_nav.set_subtitle(&format!("余额 {}", money(snap.balance)));
        self.invite_nav.set_subtitle(&if snap.invite_commission_rate > 0 {
            format!("返利 {}% · 佣金 {}", snap.invite_commission_rate, money(snap.commission_balance))
        } else {
            format!("佣金 {}", money(snap.commission_balance))
        });
        let open_tickets = snap.tickets.iter().filter(|t| !t.closed).count();
        self.tickets_nav.set_subtitle(&if open_tickets > 0 {
            format!("{open_tickets} 个处理中")
        } else {
            "提交问题与查看回复".to_string()
        });
        let mut security_parts = vec!["修改密码"];
        if is_px {
            security_parts.push(if snap.mfa_enabled { "两步验证已开启" } else { "两步验证未开启" });
        }
        if show_oauth {
            security_parts.push("第三方登录");
        }
        self.security_nav.set_subtitle(&security_parts.join(" · "));
        self.sub_group.set_visible(!snap.subscribe_url.is_empty());
        if !show_tickets {
            while let Some(c) = self.ticket_detail_box.first_child() {
                self.ticket_detail_box.remove(&c);
            }
        }

        self.email_row.set_title(if snap.email.is_empty() {
            &snap.app_name
        } else {
            &snap.email
        });
        self.email_row.set_subtitle(if snap.subscription_summary.is_empty() {
            "暂无有效套餐"
        } else {
            &snap.subscription_summary
        });
        self.balance_row.set_subtitle(&format!(
            "余额 {} · 佣金 {}",
            format_money_cents(snap.balance, &snap.currency_symbol, &snap.currency_unit),
            format_money_cents(snap.commission_balance, &snap.currency_symbol, &snap.currency_unit),
        ));

        let mfa_secret = self
            .mfa_setup
            .borrow()
            .as_ref()
            .map(|s| s.secret.clone())
            .unwrap_or_default();
        let sig = format!(
            "{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
            snap.panel_type,
            snap.payment_enabled,
            snap.subscribe_url,
            snap.mfa_enabled,
            mfa_secret,
            snap.oauth_bind_pending,
            snap.extensions.join(","),
            snap.payment_channels
                .iter()
                .map(|c| format!("{}:{}", c.id, c.name))
                .collect::<Vec<_>>()
                .join(","),
            snap.oauth_providers
                .iter()
                .map(|p| format!("{}:{}", p.id, p.label))
                .collect::<Vec<_>>()
                .join(","),
            snap.oauth_bindings
                .iter()
                .map(|b| format!("{}:{}", b.id, b.requires_password))
                .collect::<Vec<_>>()
                .join(","),
            snap.referral_url,
            snap.invite_commission_rate,
            snap.invite_codes.join(","),
            snap.tickets
                .iter()
                .map(|t| format!("{}:{}:{}", t.id, t.status_text, t.messages.len()))
                .collect::<Vec<_>>()
                .join(","),
        );
        if *self.last_sig.borrow() == sig {
            return;
        }
        *self.last_sig.borrow_mut() = sig;

        // Recharge channels
        if show_recharge {
            let ids: Vec<String> = snap.payment_channels.iter().map(|c| c.id.clone()).collect();
            let names: Vec<&str> = snap.payment_channels.iter().map(|c| c.name.as_str()).collect();
            *self.recharge_channel_ids.borrow_mut() = ids;
            self.recharge_channel_dropdown
                .set_model(Some(&gtk::StringList::new(&names)));
            self.recharge_channel_dropdown.set_selected(0);
        }

        for container in [&self.invite_box, &self.tickets_box] {
            while let Some(c) = container.first_child() {
                container.remove(&c);
            }
        }

        // Invite section
        if show_invite {
            let invite_desc = if snap.invite_commission_rate > 0 {
                format!("返利比例 {}%", snap.invite_commission_rate)
            } else {
                String::new()
            };
            let invite_group = adw::PreferencesGroup::builder().description(&invite_desc).build();
            if !snap.referral_url.is_empty() {
                invite_group.add(&build_copy_row("推广链接", &snap.referral_url));
            }
            for code in &snap.invite_codes {
                let link = if snap.base_url.is_empty() {
                    code.clone()
                } else {
                    format!("{}/#/register?code={code}", snap.base_url)
                };
                invite_group.add(&build_copy_row(code, &link));
            }
            if !is_px {
                let gen_btn = gtk::Button::with_label("生成邀请码");
                gen_btn.add_css_class("flat");
                let p = self.panel.clone();
                let h = self.rt.clone();
                gen_btn.connect_clicked(move |_| {
                    let p = p.clone();
                    h.spawn(async move {
                        let _ = p.generate_invite().await;
                    });
                });
                invite_group.set_header_suffix(Some(&gen_btn));
            }
            self.invite_box.append(&invite_group);
        }

        // Tickets list
        if show_tickets && !snap.tickets.is_empty() {
            let t_group = adw::PreferencesGroup::builder().title("工单记录").build();
            for ticket in &snap.tickets {
                let row = adw::ActionRow::builder()
                    .title(&ticket.subject)
                    .subtitle(&ticket.status_text)
                    .build();
                row.set_use_markup(false);
                let view_btn = gtk::Button::with_label("查看");
                view_btn.add_css_class("flat");
                view_btn.set_valign(gtk::Align::Center);
                let tid = ticket.id.clone();
                let tsubj = ticket.subject.clone();
                let closed = ticket.closed;
                let inline_msgs = ticket.messages.clone();
                let p = self.panel.clone();
                let h = self.rt.clone();
                let detail_box = self.ticket_detail_box.clone();
                view_btn.connect_clicked(move |_| {
                    show_ticket_detail(
                        &detail_box,
                        p.clone(),
                        h.clone(),
                        tid.clone(),
                        tsubj.clone(),
                        closed,
                        is_px,
                        inline_msgs.clone(),
                    );
                });
                row.add_suffix(&view_btn);
                t_group.add(&row);
            }
            self.tickets_box.append(&t_group);
        }

        // Native 2FA & OAuth Bindings (PxBoard)
        while let Some(c) = self.security_box.first_child() {
            self.security_box.remove(&c);
        }
        if is_px {
            self.append_mfa_section(snap);
        }
        if show_oauth {
            self.append_oauth_section(snap);
        }
    }
}

pub(crate) fn build_copy_row(title: &str, value: &str) -> adw::ActionRow {
    let row = adw::ActionRow::builder()
        .use_markup(false)
        .title(title)
        .subtitle(value)
        .build();
    let copy_btn = gtk::Button::with_label("复制");
    copy_btn.add_css_class("flat");
    copy_btn.set_valign(gtk::Align::Center);
    let val = value.to_string();
    copy_btn.connect_clicked(move |_| {
        if let Some(display) = gtk::gdk::Display::default() {
            display.clipboard().set_text(&val);
        }
    });
    row.add_suffix(&copy_btn);
    row
}

#[allow(clippy::too_many_arguments)]
fn show_ticket_detail(
    detail_box: &gtk::Box,
    panel: Arc<XbPanelClient>,
    rt: Handle,
    ticket_id: String,
    subject: String,
    closed: bool,
    is_px: bool,
    inline_msgs: Vec<TicketMessage>,
) {
    while let Some(c) = detail_box.first_child() {
        detail_box.remove(&c);
    }
    let grp = adw::PreferencesGroup::builder()
        .title(&format!("工单详情：{subject}"))
        .build();
    if !closed {
        let close_btn = gtk::Button::with_label("关闭工单");
        close_btn.add_css_class("flat");
        let p = panel.clone();
        let h = rt.clone();
        let tid = ticket_id.clone();
        let dbox = detail_box.clone();
        close_btn.connect_clicked(move |_| {
            let p = p.clone();
            let tid = tid.clone();
            h.spawn(async move {
                let _ = p.close_ticket(&tid).await;
            });
            while let Some(c) = dbox.first_child() {
                dbox.remove(&c);
            }
        });
        grp.set_header_suffix(Some(&close_btn));
    }

    let msgs_holder = adw::PreferencesGroup::new();
    let render_msgs = |holder: &adw::PreferencesGroup, msgs: &[TicketMessage]| {
        for m in msgs {
            let r = adw::ActionRow::builder()
                .use_markup(false)
                .title(&m.author)
                .subtitle(&m.message)
                .build();
            holder.add(&r);
        }
    };
    if is_px {
        render_msgs(&msgs_holder, &inline_msgs);
    } else {
        let (tx, rx) = std::sync::mpsc::channel();
        let p = panel.clone();
        let tid = ticket_id.clone();
        rt.spawn(async move {
            let res = p
                .dispatch_json("fetch_ticket_messages", serde_json::json!({ "id": tid }))
                .await
                .and_then(|v| serde_json::from_value::<Vec<TicketMessage>>(v).map_err(anyhow::Error::from));
            let _ = tx.send(res);
        });
        let holder_c = msgs_holder.clone();
        gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
            Ok(Ok(msgs)) => {
                for m in &msgs {
                    let r = adw::ActionRow::builder()
                        .use_markup(false)
                        .title(&m.author)
                        .subtitle(&m.message)
                        .build();
                    holder_c.add(&r);
                }
                gtk::glib::ControlFlow::Break
            }
            Ok(Err(_)) | Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                gtk::glib::ControlFlow::Break
            }
            Err(std::sync::mpsc::TryRecvError::Empty) => gtk::glib::ControlFlow::Continue,
        });
    }

    detail_box.append(&grp);
    detail_box.append(&msgs_holder);

    if !closed {
        let reply_grp = adw::PreferencesGroup::new();
        let reply_row = adw::EntryRow::builder().title("回复内容").build();
        let send_btn = gtk::Button::with_label("发送");
        send_btn.add_css_class("suggested-action");
        send_btn.set_valign(gtk::Align::Center);
        let p = panel;
        let h = rt;
        let tid = ticket_id;
        let rrow = reply_row.clone();
        send_btn.connect_clicked(move |_| {
            let text = rrow.text().trim().to_string();
            if text.is_empty() {
                return;
            }
            rrow.set_text("");
            let p = p.clone();
            let tid = tid.clone();
            h.spawn(async move {
                let _ = p.reply_ticket(&tid, &text).await;
            });
        });
        reply_row.add_suffix(&send_btn);
        reply_grp.add(&reply_row);
        detail_box.append(&reply_grp);
    }
}
