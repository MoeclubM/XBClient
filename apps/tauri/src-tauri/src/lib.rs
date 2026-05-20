mod commands;
mod subscription;
mod system_proxy;
#[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
mod tray;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = rustls::crypto::ring::default_provider().install_default();

    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_store::Builder::default().build())
        .plugin(tauri_plugin_xbclient_mobile::init())
        .plugin(tauri_plugin_opener::init());

    #[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
    let builder = builder.plugin(tauri_plugin_autostart::init(
        tauri_plugin_autostart::MacosLauncher::LaunchAgent,
        None,
    ));

    let builder = builder.setup(|app| {
        let handle = app.handle().clone();
        aerion_core::set_log_callback(move |level, message| {
            let _ = handle.emit("aerion-log", (level, message));
        });
        let handle = app.handle().clone();
        aerion_core::set_event_callback(move |_, json| {
            let _ = handle.emit("aerion-event", json);
        });

        #[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
        {
            tray::install(app.handle())?;
        }
        Ok(())
    });

    #[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
    let builder = builder.on_window_event(|window, event| {
        if let tauri::WindowEvent::CloseRequested { api, .. } = event {
            let _ = window.hide();
            api.prevent_close();
        }
    });

    builder
        .invoke_handler(tauri::generate_handler![
            commands::runtime_capabilities,
            commands::resolve_node_host,
            commands::xboard_request,
            commands::subscription_fetch,
            commands::aerion_test_node,
            commands::aerion_start_socks,
            commands::aerion_stop,
            commands::admob_show_rewarded,
            commands::admob_show_app_open,
            commands::system_proxy_set,
            commands::system_proxy_clear,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
