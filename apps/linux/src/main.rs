#[cfg(target_os = "linux")]
mod account_security;
#[cfg(target_os = "linux")]
mod account_view;
#[cfg(target_os = "linux")]
mod auth_view;
#[cfg(target_os = "linux")]
mod helpers;
#[cfg(target_os = "linux")]
mod plans_view;

#[cfg(not(target_os = "linux"))]
fn main() {
    eprintln!("xbclient-linux is only built on Linux Wayland/X11 targets");
}

#[cfg(target_os = "linux")]
fn main() -> anyhow::Result<()> {
    use crate::account_view::AccountView;
    use crate::auth_view::AuthView;
    use crate::helpers::{describe_block_reason, plain_text};
    use crate::plans_view::PlansView;
    use adw::prelude::*;
    use neto_core::{NetoController, init_rustls_provider};
    use neto_platform_linux::{
        LinuxWaylandPlatformHooks, WaylandTrayAction, WaylandTrayHandle, WaylandTrayState,
        prefer_wayland_backend,
    };
    use neto_ui_gtk::{
        connections_view::ConnectionsView, home_view::HomeView, install_neto_css,
        nodes_view::NodesView, rules_view::RulesView, settings_view::SettingsView,
    };
    use std::path::PathBuf;
    use std::rc::Rc;
    use std::time::Duration;
    use xbclient_panel::{BuildConfig, XbPanelClient, format_money_cents};

    init_rustls_provider();
    prefer_wayland_backend();

    let rt = tokio::runtime::Runtime::new()?;
    let handle = rt.handle().clone();

    let cfg = BuildConfig::from_compile_env();
    let app_title = if cfg.app_name.trim().is_empty() {
        "XBClient".to_string()
    } else {
        cfg.app_name.clone()
    };
    let app_id = "io.github.moeclubm.XBClient";
    let data_dir = std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(|| PathBuf::from("/tmp"))
        .join("xbclient");

    let hooks = LinuxWaylandPlatformHooks::new(app_id, &app_title);
    let controller = NetoController::new(&data_dir, &cfg.user_agent, Some(hooks))?;
    let panel = XbPanelClient::new(&data_dir, controller.clone(), cfg.clone())?;
    {
        let p = panel.clone();
        handle.spawn(async move {
            let _ = p.init_or_sync().await;
        });
    }

    let start_minimized = std::env::args().any(|a| a == "--minimized");
    let app = adw::Application::builder().application_id(app_id).build();
    let ctrl_activate = controller.clone();
    let panel_activate = panel.clone();
    let handle_activate = handle.clone();
    let cfg_activate = cfg;
    let title_activate = app_title;

    app.connect_activate(move |app| {
        install_neto_css();

        let root_stack = gtk::Stack::new();
        root_stack.set_transition_type(gtk::StackTransitionType::Crossfade);

        let auth_view = AuthView::new(panel_activate.clone(), handle_activate.clone());
        root_stack.add_named(&auth_view.root, Some("auth"));

        let view_stack = adw::ViewStack::new();
        let stack_for_nodes = view_stack.clone();
        let open_nodes: Rc<dyn Fn()> = Rc::new(move || {
            stack_for_nodes.set_visible_child_name("nodes");
        });
        let stack_for_conns = view_stack.clone();
        let open_connections: Rc<dyn Fn()> = Rc::new(move || {
            stack_for_conns.set_visible_child_name("connections");
        });

        let blocked_status = adw::StatusPage::builder()
            .icon_name("dialog-warning-symbolic")
            .title("未开通套餐")
            .build();
        let blocked_btn = gtk::Button::with_label("开通套餐");
        blocked_btn.add_css_class("suggested-action");
        blocked_btn.add_css_class("pill");
        blocked_btn.set_halign(gtk::Align::Center);
        {
            let vs = view_stack.clone();
            let p = panel_activate.clone();
            blocked_btn.connect_clicked(move |_| {
                let s = p.snapshot();
                let show_plans = s.panel_type != "pxboard" || s.extensions.iter().any(|e| e == "commerce");
                vs.set_visible_child_name(if show_plans { "plans" } else { "account" });
            });
        }
        blocked_status.set_child(Some(&blocked_btn));

        let home_view = HomeView::new(
            ctrl_activate.clone(),
            handle_activate.clone(),
            open_nodes,
        );
        home_view.set_on_open_connections(open_connections);
        home_view.set_show_profiles_summary(false);
        home_view.set_blocked_widget(Some(&blocked_status));
        let dash_account_row = adw::ActionRow::builder().activatable(true).build();
        dash_account_row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        {
            let vs = view_stack.clone();
            let p = panel_activate.clone();
            dash_account_row.connect_activated(move |_| {
                let s = p.snapshot();
                let show_plans = s.panel_type != "pxboard" || s.extensions.iter().any(|e| e == "commerce");
                vs.set_visible_child_name(if show_plans { "plans" } else { "account" });
            });
        }
        let dash_account_group = adw::PreferencesGroup::new();
        dash_account_group.add(&dash_account_row);
        home_view.set_extra_header(Some(&dash_account_group));

        let nodes_view = NodesView::new(ctrl_activate.clone(), handle_activate.clone());
        let connections_view = ConnectionsView::new(ctrl_activate.clone(), handle_activate.clone());
        let rules_view = RulesView::new(ctrl_activate.clone(), handle_activate.clone());
        let plans_view = PlansView::new(panel_activate.clone(), handle_activate.clone());
        let account_view = AccountView::new(panel_activate.clone(), handle_activate.clone());
        let settings_view = SettingsView::new(ctrl_activate.clone());
        settings_view.configure_updater(
            &cfg_activate.github_repo,
            option_env!("NETO_APP_VERSION").unwrap_or(env!("CARGO_PKG_VERSION")),
        );

        let links = [
            ("官方网站", cfg_activate.website_url.clone()),
            ("服务协议", cfg_activate.user_agreement_url.clone()),
            ("隐私政策", cfg_activate.privacy_policy_url.clone()),
        ];
        if links.iter().any(|(_, u)| !u.trim().is_empty()) {
            let links_group = adw::PreferencesGroup::builder().title("站点与条款").build();
            for (lbl, url) in links {
                if url.trim().is_empty() {
                    continue;
                }
                let row = adw::ActionRow::builder().title(lbl).activatable(true).build();
                row.add_suffix(&gtk::Image::from_icon_name("adw-external-link-symbolic"));
                let p = panel_activate.clone();
                let h = handle_activate.clone();
                row.connect_activated(move |_| {
                    let p = p.clone();
                    let u = url.clone();
                    h.spawn(async move {
                        let _ = p.dispatch_json("open_url", serde_json::json!({ "url": u })).await;
                    });
                });
                links_group.add(&row);
            }
            settings_view.add_group(&links_group);
        }

        view_stack.add_titled_with_icon(
            &home_view.root,
            Some("home"),
            "主页",
            "go-home-symbolic",
        );
        view_stack.add_titled_with_icon(
            &nodes_view.root,
            Some("nodes"),
            "节点",
            "network-server-symbolic",
        );
        view_stack.add_titled_with_icon(
            &connections_view.root,
            Some("connections"),
            "会话",
            "network-transmit-receive-symbolic",
        );
        view_stack.add_titled_with_icon(
            &rules_view.root,
            Some("rules"),
            "规则",
            "view-list-symbolic",
        );
        let plans_page = view_stack.add_titled_with_icon(
            &plans_view.root,
            Some("plans"),
            "套餐",
            "emblem-documents-symbolic",
        );
        view_stack.add_titled_with_icon(
            &account_view.root,
            Some("account"),
            "我的",
            "avatar-default-symbolic",
        );
        view_stack.add_titled_with_icon(
            &settings_view.root,
            Some("settings"),
            "设置",
            "emblem-system-symbolic",
        );

        let header_title = gtk::Label::new(None);
        header_title.add_css_class("title");
        let header = adw::HeaderBar::builder()
            .title_widget(&header_title)
            .build();
        header.add_css_class("flat");
        let notice_btn = gtk::Button::from_icon_name("mail-unread-symbolic");
        notice_btn.set_tooltip_text(Some("公告"));
        notice_btn.set_visible(false);
        header.pack_end(&notice_btn);
        {
            let p = panel_activate.clone();
            notice_btn.connect_clicked(move |btn| {
                let list = gtk::Box::new(gtk::Orientation::Vertical, 12);
                list.set_margin_top(12);
                list.set_margin_bottom(12);
                list.set_margin_start(12);
                list.set_margin_end(12);
                for notice in &p.snapshot().notices {
                    let title = gtk::Label::new(Some(&notice.title));
                    title.add_css_class("heading");
                    title.set_wrap(true);
                    title.set_xalign(0.0);
                    let body = gtk::Label::new(Some(&plain_text(&notice.content)));
                    body.set_wrap(true);
                    body.set_xalign(0.0);
                    body.set_selectable(true);
                    list.append(&title);
                    list.append(&body);
                }
                let scroll = gtk::ScrolledWindow::builder()
                    .hscrollbar_policy(gtk::PolicyType::Never)
                    .min_content_height(320)
                    .max_content_height(460)
                    .propagate_natural_height(true)
                    .child(&list)
                    .build();
                let dialog = adw::AlertDialog::builder().heading("公告").build();
                dialog.set_extra_child(Some(&scroll));
                dialog.add_response("close", "关闭");
                dialog.present(btn.root().as_ref());
            });
        }

        let bottom_bar = adw::ViewSwitcherBar::builder()
            .stack(&view_stack)
            .reveal(true)
            .build();

        let toolbar_view = adw::ToolbarView::new();
        toolbar_view.add_top_bar(&header);
        toolbar_view.set_content(Some(&view_stack));
        toolbar_view.add_bottom_bar(&bottom_bar);
        root_stack.add_named(&toolbar_view, Some("shell"));

        {
            let title_lbl = header_title.clone();
            let sv = settings_view.clone();
            let av = account_view.clone();
            view_stack.connect_visible_child_name_notify(move |s| {
                let name = s.visible_child_name();
                if name.as_deref() == Some("settings") {
                    sv.reset_to_root();
                } else if name.as_deref() == Some("account") {
                    av.reset_to_root();
                }
                let text = match name.as_deref() {
                    Some("home") | None => "",
                    Some("nodes") => "节点",
                    Some("connections") => "会话",
                    Some("rules") => "规则",
                    Some("plans") => "套餐",
                    Some("account") => "我的",
                    Some("settings") => "设置",
                    _ => "",
                };
                title_lbl.set_text(text);
            });
        }

        let window = adw::ApplicationWindow::builder()
            .application(app)
            .title(&title_activate)
            .default_width(860)
            .default_height(620)
            .content(&root_stack)
            .build();

        window.connect_close_request(|win| {
            win.set_visible(false);
            gtk::glib::Propagation::Stop
        });

        let snap0 = ctrl_activate.snapshot();
        let psnap0 = panel_activate.snapshot();
        root_stack.set_visible_child_name(if psnap0.logged_in { "shell" } else { "auth" });
        auth_view.apply_snapshot(&psnap0);
        home_view.apply_snapshot(&snap0);
        nodes_view.apply_snapshot(&snap0);
        connections_view.apply_snapshot(&snap0);
        rules_view.apply_snapshot(&snap0);
        plans_view.apply_snapshot(&psnap0);
        account_view.apply_snapshot(&psnap0);
        settings_view.apply_snapshot(&snap0);

        let (tray_event_tx, tray_event_rx) = std::sync::mpsc::channel::<WaylandTrayAction>();
        let tray_holder: Rc<std::cell::RefCell<Option<WaylandTrayHandle>>> =
            Rc::new(std::cell::RefCell::new(None));
        {
            let init_state = WaylandTrayState {
                app_id: app_id.to_string(),
                app_name: title_activate.clone(),
                connected: snap0.connection.connected,
                active_node: snap0.connection.active_node_name.clone(),
                routing_mode: snap0.settings.routing_mode,
                system_proxy_enabled: snap0.settings.system_proxy_enabled,
                tun_enabled: snap0.settings.tun_enabled,
            };
            let (ready_tx, ready_rx) = std::sync::mpsc::channel();
            handle_activate.spawn(async move {
                if let Ok((tray_handle, mut rx)) = WaylandTrayHandle::spawn(init_state).await {
                    let _ = ready_tx.send(tray_handle);
                    while let Some(action) = rx.recv().await {
                        let _ = tray_event_tx.send(action);
                    }
                }
            });
            let holder = tray_holder.clone();
            gtk::glib::timeout_add_local(Duration::from_millis(250), move || {
                if let Ok(th) = ready_rx.try_recv() {
                    *holder.borrow_mut() = Some(th);
                    return gtk::glib::ControlFlow::Break;
                }
                gtk::glib::ControlFlow::Continue
            });
        }

        {
            let ctrl = ctrl_activate.clone();
            let pclient = panel_activate.clone();
            let rt_h = handle_activate.clone();
            let win = window.clone();
            let app_c = app.clone();
            let holder = tray_holder;
            let app_name_tray = title_activate.clone();
            gtk::glib::timeout_add_local(Duration::from_millis(800), move || {
                while let Ok(act) = tray_event_rx.try_recv() {
                    match act {
                        WaylandTrayAction::ShowWindow => {
                            win.set_visible(true);
                            win.present();
                        }
                        WaylandTrayAction::ToggleConnect => {
                            let ps = pclient.snapshot();
                            if ps.logged_in && ps.block_reason.is_empty() {
                                let c = ctrl.clone();
                                rt_h.spawn(async move {
                                    let _ = c.toggle_connect().await;
                                });
                            }
                        }
                        WaylandTrayAction::SetRoutingMode(m) => {
                            let c = ctrl.clone();
                            rt_h.spawn(async move {
                                let _ = c.set_routing_mode(m).await;
                            });
                        }
                        WaylandTrayAction::ToggleSystemProxy => {
                            let next = !ctrl.snapshot().settings.system_proxy_enabled;
                            let _ = ctrl.set_system_proxy_enabled(next);
                        }
                        WaylandTrayAction::ToggleTun => {
                            let next = !ctrl.snapshot().settings.tun_enabled;
                            let c = ctrl.clone();
                            rt_h.spawn(async move {
                                let _ = c.set_tun_enabled(next).await;
                            });
                        }
                        WaylandTrayAction::Quit => {
                            let c = ctrl.clone();
                            let _ = rt_h.block_on(c.disconnect());
                            app_c.quit();
                            return gtk::glib::ControlFlow::Break;
                        }
                    }
                }

                let psnap = pclient.snapshot();
                root_stack.set_visible_child_name(if psnap.logged_in { "shell" } else { "auth" });
                auth_view.apply_snapshot(&psnap);

                if psnap.logged_in {
                    notice_btn.set_visible(!psnap.notices.is_empty());
                    let show_plans = psnap.panel_type != "pxboard"
                        || psnap.extensions.iter().any(|e| e == "commerce");
                    plans_page.set_visible(show_plans);
                    if !psnap.block_reason.is_empty() {
                        let (btitle, bdesc) = describe_block_reason(&psnap.block_reason);
                        blocked_status.set_title(btitle);
                        blocked_status.set_description(Some(bdesc));
                        home_view.set_blocked(true);
                    } else {
                        home_view.set_blocked(false);
                    }

                    dash_account_row.set_title(if psnap.email.is_empty() {
                        &psnap.app_name
                    } else {
                        &psnap.email
                    });
                    let bal = format_money_cents(
                        psnap.balance,
                        &psnap.currency_symbol,
                        &psnap.currency_unit,
                    );
                    let sub = if psnap.subscription_summary.is_empty() {
                        format!("余额 {bal}")
                    } else {
                        format!("{} · 余额 {bal}", psnap.subscription_summary)
                    };
                    dash_account_row.set_subtitle(&sub);

                    plans_view.apply_snapshot(&psnap);
                    account_view.apply_snapshot(&psnap);
                }

                let snap = ctrl.snapshot();
                home_view.apply_snapshot(&snap);
                nodes_view.apply_snapshot(&snap);
                connections_view.apply_snapshot(&snap);
                rules_view.apply_snapshot(&snap);
                settings_view.apply_snapshot(&snap);

                if let Some(th) = holder.borrow().clone() {
                    let tstate = WaylandTrayState {
                        app_id: app_id.to_string(),
                        app_name: app_name_tray.clone(),
                        connected: snap.connection.connected,
                        active_node: snap.connection.active_node_name,
                        routing_mode: snap.settings.routing_mode,
                        system_proxy_enabled: snap.settings.system_proxy_enabled,
                        tun_enabled: snap.settings.tun_enabled,
                    };
                    rt_h.spawn(async move {
                        th.update(tstate).await;
                    });
                }
                gtk::glib::ControlFlow::Continue
            });
        }

        if !start_minimized {
            window.present();
        }
    });

    app.run_with_args::<&str>(&[]);
    let _ = rt.block_on(controller.disconnect());
    let _ = rt.block_on(neto_core::shutdown_vpns());
    Ok(())
}
