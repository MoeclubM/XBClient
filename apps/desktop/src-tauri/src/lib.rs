mod commands;
mod subscription;
mod system_proxy;
#[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
mod tray;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = rustls::crypto::ring::default_provider().install_default();

    let builder = tauri::Builder::default().plugin(tauri_plugin_store::Builder::default().build());

    #[cfg(any(target_os = "windows", target_os = "macos", target_os = "linux"))]
    let builder = builder
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            None,
        ))
        .setup(|app| {
            tray::install(app.handle())?;
            Ok(())
        })
        .on_window_event(|window, event| {
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
            commands::system_proxy_set,
            commands::system_proxy_clear,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
