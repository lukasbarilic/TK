//! Konfigurácia (DLD kap. 3.3): %APPDATA% → %ProgramData% → nič.
use crate::http::{build_client, map_error};
use crate::types::{NetError, NetErrorKind};
use crate::url_util::{build_url, parse_server_url};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

#[derive(Debug, Default, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub device_name: Option<String>,
}

pub struct ConfigPaths {
    /// `%APPDATA%\TheraSuite` (zapisuje používateľ)
    pub user_dir: PathBuf,
    /// `%ProgramData%\TheraSuite` (zapisuje IT), len na čítanie
    pub machine_dir: Option<PathBuf>,
}

impl ConfigPaths {
    pub fn from_env() -> Self {
        let user_base = std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config")))
            .unwrap_or_else(|| PathBuf::from("."));
        Self {
            user_dir: user_base.join("TheraSuite"),
            machine_dir: std::env::var_os("ProgramData").map(|p| PathBuf::from(p).join("TheraSuite")),
        }
    }
}

fn read_config(path: &Path) -> Option<AppConfig> {
    serde_json::from_slice(&std::fs::read(path).ok()?).ok()
}

/// Adresa servera: súbor používateľa má prednosť pred súborom IT.
pub fn resolve_server_url(paths: &ConfigPaths) -> Option<String> {
    let non_empty = |c: AppConfig| c.server_url.filter(|u| !u.trim().is_empty());
    read_config(&paths.user_dir.join("config.json")).and_then(non_empty).or_else(|| {
        paths
            .machine_dir
            .as_ref()
            .and_then(|d| read_config(&d.join("config.json")))
            .and_then(non_empty)
    })
}

pub fn save_server_url(paths: &ConfigPaths, url: &str) -> Result<(), NetError> {
    let file = paths.user_dir.join("config.json");
    let mut cfg = read_config(&file).unwrap_or_default();
    cfg.server_url = Some(url.to_string());
    let io = |e: std::io::Error| NetError::config(format!("Konfiguráciu sa nepodarilo uložiť: {e}"));
    std::fs::create_dir_all(&paths.user_dir).map_err(io)?;
    std::fs::write(&file, serde_json::to_vec_pretty(&cfg).unwrap()).map_err(io)
}

/// Náhodné UUID vytvorené pri prvom spustení, uložené v `%APPDATA%`.
pub fn device_id(paths: &ConfigPaths) -> Result<String, NetError> {
    let file = paths.user_dir.join("device-id");
    if let Ok(existing) = std::fs::read_to_string(&file) {
        let existing = existing.trim();
        if uuid::Uuid::parse_str(existing).is_ok() {
            return Ok(existing.to_string());
        }
    }
    let id = uuid::Uuid::new_v4().to_string();
    let io = |e: std::io::Error| NetError::config(format!("Identifikátor zariadenia sa nepodarilo uložiť: {e}"));
    std::fs::create_dir_all(&paths.user_dir).map_err(io)?;
    std::fs::write(&file, &id).map_err(io)?;
    Ok(id)
}

/// Overenie adresy: `GET /actuator/health` musí vrátiť 2xx.
pub async fn verify_server(raw: &str, allow_http: bool) -> Result<url::Url, NetError> {
    let server = parse_server_url(raw, allow_http)?;
    let url = build_url(&server, "/actuator/health", &BTreeMap::new())?;
    let client = build_client(None)?;
    let res = client.get(url).timeout(Duration::from_secs(5)).send().await.map_err(map_error)?;
    if !res.status().is_success() {
        return Err(NetError::new(
            NetErrorKind::Network,
            format!("Server odpovedal stavom {}", res.status().as_u16()),
        ));
    }
    Ok(server)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn paths(dir: &Path) -> ConfigPaths {
        ConfigPaths { user_dir: dir.join("user"), machine_dir: Some(dir.join("machine")) }
    }

    fn write(dir: &Path, json: &str) {
        std::fs::create_dir_all(dir).unwrap();
        std::fs::write(dir.join("config.json"), json).unwrap();
    }

    #[test]
    fn poradie_user_potom_machine_potom_nic() {
        let tmp = tempfile::tempdir().unwrap();
        let p = paths(tmp.path());
        assert_eq!(resolve_server_url(&p), None);
        write(&tmp.path().join("machine"), r#"{"serverUrl":"https://it.example.invalid"}"#);
        assert_eq!(resolve_server_url(&p).as_deref(), Some("https://it.example.invalid"));
        write(&tmp.path().join("user"), r#"{"serverUrl":"https://user.example.invalid"}"#);
        assert_eq!(resolve_server_url(&p).as_deref(), Some("https://user.example.invalid"));
    }

    #[test]
    fn ulozenie_zachova_device_name_a_device_id_je_stabilne() {
        let tmp = tempfile::tempdir().unwrap();
        let p = paths(tmp.path());
        write(&tmp.path().join("user"), r#"{"deviceName":"Recepcia-1"}"#);
        save_server_url(&p, "https://x.example.invalid").unwrap();
        let cfg = read_config(&p.user_dir.join("config.json")).unwrap();
        assert_eq!(cfg.device_name.as_deref(), Some("Recepcia-1"));
        assert_eq!(cfg.server_url.as_deref(), Some("https://x.example.invalid"));
        let a = device_id(&p).unwrap();
        assert_eq!(a, device_id(&p).unwrap());
    }
}
