#![cfg(target_os = "linux")]

use crate::helpers::plain_text;
use adw::prelude::*;
use gtk::prelude::*;
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;
use xbclient_panel::{PanelStateSnapshot, XbPanelClient, format_money_cents};

pub struct PlansView {
    pub root: gtk::ScrolledWindow,
    balance_row: adw::ActionRow,
    portal_btn: gtk::Button,
    px_opts_group: adw::PreferencesGroup,
    coupon_row: adw::EntryRow,
    ch_row: adw::ActionRow,
    channel_dropdown: gtk::DropDown,
    channel_ids: Rc<RefCell<Vec<String>>>,
    feedback_label: gtk::Label,
    plans_box: gtk::Box,
    last_sig: RefCell<String>,
    panel: Arc<XbPanelClient>,
    rt: Handle,
}

impl PlansView {
    pub fn new(panel: Arc<XbPanelClient>, rt: Handle) -> Rc<Self> {
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
        let outer = gtk::Box::new(gtk::Orientation::Vertical, 12);

        let top_group = adw::PreferencesGroup::new();
        let balance_row = adw::ActionRow::builder().title("账户余额").build();
        let portal_btn = gtk::Button::with_label("网页商店");
        portal_btn.add_css_class("flat");
        portal_btn.set_valign(gtk::Align::Center);
        let refresh_btn = gtk::Button::with_label("刷新");
        refresh_btn.add_css_class("flat");
        refresh_btn.set_valign(gtk::Align::Center);
        balance_row.add_suffix(&portal_btn);
        balance_row.add_suffix(&refresh_btn);
        top_group.add(&balance_row);

        let px_opts_group = adw::PreferencesGroup::new();
        px_opts_group.set_visible(false);
        let coupon_row = adw::EntryRow::builder().title("优惠码（选填）").build();
        let ch_row = adw::ActionRow::builder().title("支付方式").build();
        let channel_dropdown = gtk::DropDown::from_strings(&["余额支付"]);
        channel_dropdown.set_valign(gtk::Align::Center);
        ch_row.add_suffix(&channel_dropdown);
        px_opts_group.add(&coupon_row);
        px_opts_group.add(&ch_row);

        let feedback_label = gtk::Label::new(None);
        feedback_label.set_wrap(true);
        feedback_label.set_xalign(0.0);
        feedback_label.set_visible(false);

        let plans_box = gtk::Box::new(gtk::Orientation::Vertical, 12);

        outer.append(&top_group);
        outer.append(&px_opts_group);
        outer.append(&feedback_label);
        outer.append(&plans_box);
        clamp.set_child(Some(&outer));
        scroll.set_child(Some(&clamp));

        {
            let p = panel.clone();
            let handle = rt.clone();
            portal_btn.connect_clicked(move |_| {
                let p = p.clone();
                handle.spawn(async move {
                    let _ = p.open_portal("/plans").await;
                });
            });
        }
        {
            let p = panel.clone();
            let handle = rt.clone();
            refresh_btn.connect_clicked(move |_| {
                let p = p.clone();
                handle.spawn(async move {
                    let _ = p.sync_all().await;
                });
            });
        }

        Rc::new(Self {
            root: scroll,
            balance_row,
            portal_btn,
            px_opts_group,
            coupon_row,
            ch_row,
            channel_dropdown,
            channel_ids: Rc::new(RefCell::new(vec!["wallet".to_string()])),
            feedback_label,
            plans_box,
            last_sig: RefCell::new(String::new()),
            panel,
            rt,
        })
    }

    pub fn apply_snapshot(&self, snap: &PanelStateSnapshot) {
        let is_px = snap.panel_type == "pxboard";
        self.balance_row.set_subtitle(&format_money_cents(
            snap.balance,
            &snap.currency_symbol,
            &snap.currency_unit,
        ));
        self.portal_btn.set_visible(is_px && snap.payment_enabled);

        let sig = format!(
            "{is_px}:{}:{}:{}:{}:{}",
            snap.payment_enabled,
            snap.currency_symbol,
            snap.currency_unit,
            snap.payment_channels
                .iter()
                .map(|c| format!("{}:{}", c.id, c.name))
                .collect::<Vec<_>>()
                .join(","),
            snap.plans
                .iter()
                .map(|p| format!("{}:{}:{}", p.id, p.name, p.periods.len()))
                .collect::<Vec<_>>()
                .join(";")
        );
        if *self.last_sig.borrow() == sig {
            return;
        }
        *self.last_sig.borrow_mut() = sig;

        let show_channels = snap.payment_enabled && !snap.payment_channels.is_empty();
        self.px_opts_group.set_visible(!snap.plans.is_empty());
        self.ch_row.set_visible(show_channels);
        let mut ids = vec!["wallet".to_string()];
        let mut labels = vec!["余额支付".to_string()];
        if show_channels {
            for ch in &snap.payment_channels {
                ids.push(ch.id.clone());
                labels.push(ch.name.clone());
            }
        }
        *self.channel_ids.borrow_mut() = ids;
        let str_refs: Vec<&str> = labels.iter().map(String::as_str).collect();
        self.channel_dropdown
            .set_model(Some(&gtk::StringList::new(&str_refs)));
        self.channel_dropdown.set_selected(0);

        while let Some(c) = self.plans_box.first_child() {
            self.plans_box.remove(&c);
        }
        if snap.plans.is_empty() {
            let empty = adw::StatusPage::builder()
                .icon_name("emblem-documents-symbolic")
                .title("暂无可购买套餐")
                .build();
            self.plans_box.append(&empty);
            return;
        }

        for plan in &snap.plans {
            let mut desc = plain_text(&plan.description);
            if !plan.quota_text.is_empty() {
                desc = if desc.is_empty() {
                    plan.quota_text.clone()
                } else {
                    format!("{} · {desc}", plan.quota_text)
                };
            }
            let grp = adw::PreferencesGroup::builder()
                .title(&plan.name)
                .description(&desc)
                .build();
            for per in &plan.periods {
                let price_str = format_money_cents(
                    per.price_cents,
                    &snap.currency_symbol,
                    &snap.currency_unit,
                );
                let row = adw::ActionRow::builder()
                    .title(&per.label)
                    .subtitle(&price_str)
                    .build();
                let buy_btn = gtk::Button::with_label("购买");
                buy_btn.add_css_class("suggested-action");
                buy_btn.set_valign(gtk::Align::Center);
                let plan_id = plan.id.clone();
                let period_id = per.id.clone();
                let coupon_row = self.coupon_row.clone();
                let dropdown = self.channel_dropdown.clone();
                let ch_ids = self.channel_ids.clone();
                let p = self.panel.clone();
                let rt = self.rt.clone();
                let flbl = self.feedback_label.clone();
                buy_btn.connect_clicked(move |btn| {
                    btn.set_sensitive(false);
                    let coupon = coupon_row.text().trim().to_string();
                    let ch_id = if show_channels {
                        let idx = dropdown.selected() as usize;
                        ch_ids.borrow().get(idx).cloned().unwrap_or_default()
                    } else {
                        "wallet".to_string()
                    };
                    let p = p.clone();
                    let pid = plan_id.clone();
                    let per_id = period_id.clone();
                    let (tx, rx) = std::sync::mpsc::channel();
                    rt.spawn(async move {
                        let res = p
                            .purchase_plan(&pid, &per_id, &coupon, &ch_id)
                            .await
                            .map_err(|e| format!("{e:#}"));
                        let _ = tx.send(res);
                    });
                    let btn_c = btn.clone();
                    let flbl_c = flbl.clone();
                    gtk::glib::timeout_add_local(Duration::from_millis(120), move || match rx.try_recv() {
                        Ok(Ok((redirect, _))) => {
                            btn_c.set_sensitive(true);
                            flbl_c.remove_css_class("error");
                            flbl_c.set_text(if redirect.is_some() {
                                "已打开外部收银台，完成支付后请点击刷新。"
                            } else {
                                "套餐已开通并同步订阅。"
                            });
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
                row.add_suffix(&buy_btn);
                grp.add(&row);
            }
            self.plans_box.append(&grp);
        }
    }
}
