//! Tauri príkazy (DLD kap. 3.1).
use crate::AppState;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, State};
use therasuite_net::config::{self, ConfigPaths};
use therasuite_net::live::{self, LiveEventMsg, LiveSink, LiveStateMsg};
use therasuite_net::{HttpRequest, HttpResponse, NetError};
use tokio::sync::watch;

pub const ALLOW_HTTP: bool = cfg!(debug_assertions);

#[tauri::command]
pub async fn http_request(
    state: State<'_, AppState>,
    request: HttpRequest,
) -> Result<HttpResponse, NetError> {
    state.net.request(request).await
}

struct TauriSink(AppHandle);

impl LiveSink for TauriSink {
    fn event(&self, e: LiveEventMsg) {
        let _ = self.0.emit("live://event", e);
    }
    fn state(&self, s: LiveStateMsg) {
        let _ = self.0.emit("live://state", s);
    }
}

fn stop_live(state: &AppState) {
    if let Some(tx) = state.live.lock().unwrap().take() {
        let _ = tx.send(true);
    }
}

#[tauri::command]
pub fn live_connect(app: AppHandle, state: State<'_, AppState>) {
    stop_live(&state);
    let (tx, rx) = watch::channel(false);
    *state.live.lock().unwrap() = Some(tx);
    let sink: Arc<dyn LiveSink> = Arc::new(TauriSink(app));
    tauri::async_runtime::spawn(live::run(state.net.clone(), sink, rx));
}

#[tauri::command]
pub fn live_disconnect(state: State<'_, AppState>) {
    stop_live(&state);
}

#[tauri::command]
pub fn config_get(state: State<'_, AppState>) -> Option<String> {
    config::resolve_server_url(&state.paths)
}

/// Overí adresu (`GET /actuator/health`), uloží ju a prepne jadro na nový server.
#[tauri::command]
pub async fn config_set_server_url(state: State<'_, AppState>, url: String) -> Result<(), NetError> {
    let server = config::verify_server(&url, ALLOW_HTTP).await?;
    config::save_server_url(&state.paths, url.trim())?;
    stop_live(&state);
    state.net.set_server(Some(server))
}

#[tauri::command]
pub fn config_device_id(state: State<'_, AppState>) -> Result<String, NetError> {
    config::device_id(&state.paths)
}

#[tauri::command]
pub fn app_version(app: AppHandle) -> String {
    app.package_info().version.to_string()
}

pub fn initial_server(paths: &ConfigPaths) -> Option<therasuite_net::url_util::Url> {
    let raw = config::resolve_server_url(paths)?;
    match therasuite_net::url_util::parse_server_url(&raw, ALLOW_HTTP) {
        Ok(u) => Some(u),
        Err(e) => {
            log::warn!("Nakonfigurovaná adresa servera je neplatná: {}", e.message);
            None
        }
    }
}
