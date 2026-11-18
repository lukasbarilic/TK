mod commands;

use std::sync::{Arc, Mutex};
use tauri_plugin_log::{RotationStrategy, Target, TargetKind};
use therasuite_net::config::ConfigPaths;
use therasuite_net::NetCore;
use tokio::sync::watch;

pub struct AppState {
    pub net: Arc<NetCore>,
    pub paths: ConfigPaths,
    pub live: Mutex<Option<watch::Sender<bool>>>,
}

fn log_dir() -> std::path::PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(|| std::path::PathBuf::from("."))
        .join("TheraSuite")
        .join("logs")
}

pub fn run() {
    let net = Arc::new(NetCore::new().expect("inicializácia HTTP klienta zlyhala"));

    tauri::Builder::default()
        .plugin(
            tauri_plugin_log::Builder::new()
                .clear_targets()
                .target(Target::new(TargetKind::Folder {
                    path: log_dir(),
                    file_name: Some("therasuite".into()),
                }))
                .max_file_size(2_000_000)
                .rotation_strategy(RotationStrategy::KeepSome(5))
                .level(log::LevelFilter::Info)
                .level_for("reqwest", log::LevelFilter::Warn)
                .level_for("hyper_util", log::LevelFilter::Warn)
                .build(),
        )
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState {
            net: net.clone(),
            paths: ConfigPaths::from_env(),
            live: Mutex::new(None),
        })
        .setup(move |app| {
            use tauri::Manager;
            let state = app.state::<AppState>();
            if let Some(server) = commands::initial_server(&state.paths) {
                state.net.set_server(Some(server)).ok();
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::http_request,
            commands::live_connect,
            commands::live_disconnect,
            commands::config_get,
            commands::config_set_server_url,
            commands::config_device_id,
            commands::app_version,
        ])
        .run(tauri::generate_context!())
        .expect("chyba pri spustení aplikácie");
}
