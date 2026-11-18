mod commands;

use std::sync::{Arc, Mutex};
use tauri_plugin_log::{RotationStrategy, Target, TargetKind};
use therasuite_net::config::ConfigPaths;
use therasuite_net::NetCore;
use therasuite_vault::Vault;
use tokio::sync::watch;

pub struct AppState {
    pub net: Arc<NetCore>,
    pub paths: ConfigPaths,
    pub vault: Arc<Vault>,
    pub live: Mutex<Option<watch::Sender<bool>>>,
}

fn data_root() -> std::path::PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(std::path::PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| std::path::PathBuf::from(h).join(".local/share")))
        .unwrap_or_else(|| std::path::PathBuf::from("."))
        .join("TheraSuite")
}

fn log_dir() -> std::path::PathBuf {
    data_root().join("logs")
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
            vault: Arc::new(Vault::new(data_root())),
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
            commands::vault_exists,
            commands::vault_create,
            commands::vault_unlock,
            commands::vault_rewrap,
            commands::vault_lock,
            commands::vault_wipe,
            commands::vault_put,
            commands::vault_get,
            commands::vault_list,
            commands::vault_remove,
            commands::vault_info,
            commands::vault_set_sync_state,
        ])
        .run(tauri::generate_context!())
        .expect("chyba pri spustení aplikácie");
}
