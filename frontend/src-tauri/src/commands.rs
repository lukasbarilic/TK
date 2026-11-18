//! Tauri príkazy (DLD kap. 3.1).
use crate::AppState;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, State};
use therasuite_net::config::{self, ConfigPaths};
use therasuite_net::live::{self, LiveEventMsg, LiveSink, LiveStateMsg};
use therasuite_net::{HttpRequest, HttpResponse, NetError};
use therasuite_vault::{EntryMeta, UnlockResult, Vault, VaultError, VaultInfo};
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

// ---- Trezor (DLD kap. 3.1 a 4) -------------------------------------------------------------
// DEK sa nikdy nevracia; príkazy so zápisom/čítaním dát zlyhajú s chybou `locked`.
// Argon2id je náročný, preto beží mimo hlavného vlákna.

fn server_key(state: &AppState) -> String {
    config::resolve_server_url(&state.paths).unwrap_or_default()
}

async fn blocking<T: Send + 'static>(
    state: &State<'_, AppState>,
    f: impl FnOnce(Arc<Vault>) -> Result<T, VaultError> + Send + 'static,
) -> Result<T, VaultError> {
    let vault = state.vault.clone();
    tauri::async_runtime::spawn_blocking(move || f(vault))
        .await
        .map_err(|e| VaultError::storage(format!("Úloha trezora zlyhala: {e}")))?
}

#[tauri::command]
pub fn vault_exists(state: State<'_, AppState>, username: String) -> bool {
    state.vault.exists(&server_key(&state), &username)
}

#[tauri::command]
pub async fn vault_create(state: State<'_, AppState>, username: String, password: String) -> Result<(), VaultError> {
    let server = server_key(&state);
    blocking(&state, move |v| v.create(&server, &username, &password)).await
}

#[tauri::command]
pub async fn vault_unlock(
    state: State<'_, AppState>,
    username: String,
    password: String,
) -> Result<UnlockResult, VaultError> {
    let server = server_key(&state);
    blocking(&state, move |v| v.unlock(&server, &username, &password)).await
}

#[tauri::command]
pub async fn vault_rewrap(
    state: State<'_, AppState>,
    old_password: String,
    new_password: String,
) -> Result<(), VaultError> {
    blocking(&state, move |v| v.rewrap(&old_password, &new_password)).await
}

#[tauri::command]
pub fn vault_lock(state: State<'_, AppState>) {
    state.vault.lock();
}

#[tauri::command]
pub fn vault_wipe(state: State<'_, AppState>, username: String) -> Result<(), VaultError> {
    state.vault.wipe(&server_key(&state), &username)
}

#[tauri::command]
pub fn vault_put(
    state: State<'_, AppState>,
    dataset: String,
    key: String,
    value: serde_json::Value,
    meta: Option<EntryMeta>,
) -> Result<(), VaultError> {
    state.vault.put(&dataset, &key, &value, meta)
}

#[derive(serde::Serialize)]
pub struct VaultEntry {
    value: serde_json::Value,
    meta: EntryMeta,
}

#[tauri::command]
pub fn vault_get(state: State<'_, AppState>, dataset: String, key: String) -> Result<Option<VaultEntry>, VaultError> {
    Ok(state.vault.get(&dataset, &key)?.map(|(value, meta)| VaultEntry { value, meta }))
}

#[tauri::command]
pub fn vault_list(state: State<'_, AppState>, dataset: String) -> Result<Vec<String>, VaultError> {
    state.vault.list(&dataset)
}

#[tauri::command]
pub fn vault_remove(state: State<'_, AppState>, dataset: String, keys: Vec<String>) -> Result<(), VaultError> {
    state.vault.remove(&dataset, &keys)
}

#[tauri::command]
pub fn vault_set_sync_state(
    state: State<'_, AppState>,
    last_full_sync_at: String,
    max_age_days: Option<i64>,
) -> Result<(), VaultError> {
    state.vault.set_sync_state(&last_full_sync_at, max_age_days)
}

#[tauri::command]
pub fn vault_info(state: State<'_, AppState>) -> Result<VaultInfo, VaultError> {
    state.vault.info()
}
