//! Šifrovaný trezor (DLD kap. 4). Zámerne bez závislosti na Tauri.
//!
//! - `vault.db` (SQLite, WAL) v priečinku `<root>/vaults/<hex(sha256(server\nusername))>/`
//! - KEK = Argon2id(heslo, salt), DEK = 32 náhodných bajtov zabalených pod KEK (AES-256-GCM)
//! - záznamy: AES-256-GCM(DEK, json), AAD = `dataset || 0x00 || key`
//! - DEK žije len v pamäti (`Zeroizing`) a nikdy sa nevracia volajúcemu.
use aes_gcm::aead::rand_core::RngCore;
use aes_gcm::aead::{Aead, OsRng, Payload};
use aes_gcm::{Aes256Gcm, Key, KeyInit, Nonce};
use argon2::{Algorithm, Argon2, Params, Version};
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use zeroize::Zeroizing;

const FORMAT_VERSION: i64 = 1;
const DB_FILE: &str = "vault.db";
const DEK_AAD: &[u8] = b"therasuite-dek";

/// Parametre Argon2id (DLD 4.1: m = 64 MiB, t = 3, p = 1).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct KdfParams {
    pub m_kib: u32,
    pub t: u32,
    pub p: u32,
}

impl KdfParams {
    pub const DEFAULT: KdfParams = KdfParams { m_kib: 65_536, t: 3, p: 1 };
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum UnlockResult {
    Ok,
    WrongPassword,
    Expired,
    Missing,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum VaultErrorKind {
    Locked,
    Throttled,
    WrongPassword,
    Missing,
    Crypto,
    Storage,
}

/// Chyba príkazu `vault_*`; do JS ide ako `{ kind, message, until? }`.
#[derive(Debug, Clone, Serialize)]
pub struct VaultError {
    pub kind: VaultErrorKind,
    pub message: String,
    /// ISO čas, do kedy je odomykanie zablokované (len `throttled`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub until: Option<String>,
}

impl VaultError {
    fn new(kind: VaultErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into(), until: None }
    }
    fn locked() -> Self {
        Self::new(VaultErrorKind::Locked, "Trezor je zamknutý.")
    }
    pub fn storage(message: impl Into<String>) -> Self {
        Self::new(VaultErrorKind::Storage, message)
    }
    fn crypto() -> Self {
        Self::new(VaultErrorKind::Crypto, "Dešifrovanie zlyhalo.")
    }
}

impl std::fmt::Display for VaultError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{:?}: {}", self.kind, self.message)
    }
}
impl std::error::Error for VaultError {}

impl From<rusqlite::Error> for VaultError {
    fn from(e: rusqlite::Error) -> Self {
        Self::new(VaultErrorKind::Storage, format!("Chyba úložiska: {e}"))
    }
}
impl From<std::io::Error> for VaultError {
    fn from(e: std::io::Error) -> Self {
        Self::new(VaultErrorKind::Storage, format!("Chyba disku: {e}"))
    }
}
impl From<serde_json::Error> for VaultError {
    fn from(e: serde_json::Error) -> Self {
        Self::new(VaultErrorKind::Storage, format!("Neplatný JSON: {e}"))
    }
}

pub type Result<T> = std::result::Result<T, VaultError>;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct EntryMeta {
    #[serde(default)]
    pub etag: Option<String>,
    pub synced_at: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct VaultInfo {
    pub username: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_full_sync_at: Option<String>,
    pub size_bytes: u64,
}

struct Session {
    username: String,
    dir: PathBuf,
    conn: Connection,
    dek: Zeroizing<[u8; 32]>,
}

pub struct Vault {
    root: PathBuf,
    kdf: KdfParams,
    session: Mutex<Option<Session>>,
}

fn now() -> DateTime<Utc> {
    Utc::now()
}
fn iso(t: DateTime<Utc>) -> String {
    t.to_rfc3339_opts(SecondsFormat::Secs, true)
}

/// Názov priečinka trezora: hex(sha256(serverUrl + "\n" + username.lower())).
pub fn vault_dir_name(server_url: &str, username: &str) -> String {
    let mut h = Sha256::new();
    h.update(server_url.as_bytes());
    h.update(b"\n");
    h.update(username.to_lowercase().as_bytes());
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

fn derive_kek(password: &str, salt: &[u8], kdf: KdfParams) -> Result<Zeroizing<[u8; 32]>> {
    let params = Params::new(kdf.m_kib, kdf.t, kdf.p, Some(32))
        .map_err(|e| VaultError::new(VaultErrorKind::Crypto, format!("Neplatné parametre KDF: {e}")))?;
    let mut out = Zeroizing::new([0u8; 32]);
    Argon2::new(Algorithm::Argon2id, Version::V0x13, params)
        .hash_password_into(password.as_bytes(), salt, out.as_mut())
        .map_err(|e| VaultError::new(VaultErrorKind::Crypto, format!("KDF zlyhala: {e}")))?;
    Ok(out)
}

fn seal(key: &[u8; 32], aad: &[u8], plain: &[u8]) -> Result<([u8; 12], Vec<u8>)> {
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let ct = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key))
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: plain, aad: aad })
        .map_err(|_| VaultError::crypto())?;
    Ok((nonce, ct))
}

fn open(key: &[u8; 32], nonce: &[u8], aad: &[u8], ct: &[u8]) -> Result<Zeroizing<Vec<u8>>> {
    if nonce.len() != 12 {
        return Err(VaultError::crypto());
    }
    Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key))
        .decrypt(Nonce::from_slice(nonce), Payload { msg: ct, aad })
        .map(Zeroizing::new)
        .map_err(|_| VaultError::crypto())
}

fn entry_aad(dataset: &str, key: &str) -> Vec<u8> {
    let mut a = Vec::with_capacity(dataset.len() + key.len() + 1);
    a.extend_from_slice(dataset.as_bytes());
    a.push(0);
    a.extend_from_slice(key.as_bytes());
    a
}

fn open_db(path: &Path) -> Result<Connection> {
    let conn = Connection::open(path)?;
    conn.query_row("PRAGMA journal_mode=WAL", [], |_| Ok(()))?;
    Ok(conn)
}

struct Header {
    kdf: KdfParams,
    salt: Vec<u8>,
    dek_nonce: Vec<u8>,
    dek_wrapped: Vec<u8>,
    last_full_sync_at: Option<String>,
    max_age_days: Option<i64>,
    failed_unlocks: i64,
    next_unlock_after: Option<String>,
}

fn read_header(conn: &Connection) -> Result<Option<Header>> {
    Ok(conn
        .query_row(
            "SELECT kdf_m_kib, kdf_t, kdf_p, kdf_salt, dek_nonce, dek_wrapped, last_full_sync_at,
                    max_age_days, failed_unlocks, next_unlock_after FROM header WHERE id = 1",
            [],
            |r| {
                Ok(Header {
                    kdf: KdfParams { m_kib: r.get(0)?, t: r.get(1)?, p: r.get(2)? },
                    salt: r.get(3)?,
                    dek_nonce: r.get(4)?,
                    dek_wrapped: r.get(5)?,
                    last_full_sync_at: r.get(6)?,
                    max_age_days: r.get(7)?,
                    failed_unlocks: r.get(8)?,
                    next_unlock_after: r.get(9)?,
                })
            },
        )
        .optional()?)
}

fn unwrap_dek(h: &Header, password: &str) -> Result<Option<Zeroizing<[u8; 32]>>> {
    let kek = derive_kek(password, &h.salt, h.kdf)?;
    match open(&kek, &h.dek_nonce, DEK_AAD, &h.dek_wrapped) {
        Ok(v) => {
            let mut dek = Zeroizing::new([0u8; 32]);
            if v.len() != 32 {
                return Err(VaultError::crypto());
            }
            dek.copy_from_slice(&v);
            Ok(Some(dek))
        }
        Err(_) => Ok(None),
    }
}

/// Čakanie po `failed` neúspešných pokusoch: po 5. 30 s, potom 1, 2, 5, 15 min (ďalej 15 min).
pub fn throttle_delay(failed: i64) -> Option<Duration> {
    match failed {
        ..=4 => None,
        5 => Some(Duration::seconds(30)),
        6 => Some(Duration::minutes(1)),
        7 => Some(Duration::minutes(2)),
        8 => Some(Duration::minutes(5)),
        _ => Some(Duration::minutes(15)),
    }
}

impl Vault {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self::with_kdf(root, KdfParams::DEFAULT)
    }

    /// Len pre testy: lacnejšie parametre KDF pre novovytvorené trezory.
    pub fn with_kdf(root: impl Into<PathBuf>, kdf: KdfParams) -> Self {
        Self { root: root.into(), kdf, session: Mutex::new(None) }
    }

    fn dir(&self, server_url: &str, username: &str) -> PathBuf {
        self.root.join("vaults").join(vault_dir_name(server_url, username))
    }

    fn lock_guard(&self) -> std::sync::MutexGuard<'_, Option<Session>> {
        self.session.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn with_session<T>(&self, f: impl FnOnce(&mut Session) -> Result<T>) -> Result<T> {
        let mut g = self.lock_guard();
        f(g.as_mut().ok_or_else(VaultError::locked)?)
    }

    pub fn exists(&self, server_url: &str, username: &str) -> bool {
        self.dir(server_url, username).join(DB_FILE).is_file()
    }

    /// Zmaže existujúci trezor, vytvorí nový a nechá ho odomknutý.
    pub fn create(&self, server_url: &str, username: &str, password: &str) -> Result<()> {
        let mut g = self.lock_guard();
        *g = None;
        let dir = self.dir(server_url, username);
        remove_dir(&dir)?;
        std::fs::create_dir_all(&dir)?;

        let mut salt = [0u8; 16];
        OsRng.fill_bytes(&mut salt);
        let mut dek = Zeroizing::new([0u8; 32]);
        OsRng.fill_bytes(dek.as_mut());
        let kek = derive_kek(password, &salt, self.kdf)?;
        let (nonce, wrapped) = seal(&kek, DEK_AAD, dek.as_ref())?;

        let conn = open_db(&dir.join(DB_FILE))?;
        conn.execute_batch(
            "CREATE TABLE header (
               id INTEGER PRIMARY KEY CHECK (id = 1),
               format_version INTEGER NOT NULL,
               username TEXT NOT NULL,
               kdf_salt BLOB NOT NULL,
               kdf_m_kib INTEGER NOT NULL,
               kdf_t INTEGER NOT NULL,
               kdf_p INTEGER NOT NULL,
               dek_nonce BLOB NOT NULL,
               dek_wrapped BLOB NOT NULL,
               created_at TEXT NOT NULL,
               last_full_sync_at TEXT,
               max_age_days INTEGER,
               failed_unlocks INTEGER NOT NULL DEFAULT 0,
               next_unlock_after TEXT
             );
             CREATE TABLE entry (
               dataset TEXT NOT NULL,
               key TEXT NOT NULL,
               nonce BLOB NOT NULL,
               ciphertext BLOB NOT NULL,
               etag TEXT,
               synced_at TEXT NOT NULL,
               PRIMARY KEY (dataset, key)
             );",
        )?;
        conn.execute(
            "INSERT INTO header (id, format_version, username, kdf_salt, kdf_m_kib, kdf_t, kdf_p,
                                 dek_nonce, dek_wrapped, created_at)
             VALUES (1, ?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![
                FORMAT_VERSION, username, salt, self.kdf.m_kib, self.kdf.t, self.kdf.p,
                nonce, wrapped, iso(now())
            ],
        )?;
        *g = Some(Session { username: username.to_string(), dir, conn, dek });
        Ok(())
    }

    pub fn unlock(&self, server_url: &str, username: &str, password: &str) -> Result<UnlockResult> {
        let mut g = self.lock_guard();
        *g = None;
        let dir = self.dir(server_url, username);
        let path = dir.join(DB_FILE);
        if !path.is_file() {
            return Ok(UnlockResult::Missing);
        }
        let conn = open_db(&path)?;
        let Some(h) = read_header(&conn)? else { return Ok(UnlockResult::Missing) };

        if let Some(until) = h.next_unlock_after.as_deref().and_then(|s| DateTime::parse_from_rfc3339(s).ok()) {
            if until.with_timezone(&Utc) > now() {
                return Err(VaultError {
                    kind: VaultErrorKind::Throttled,
                    message: "Príliš veľa nesprávnych pokusov.".into(),
                    until: Some(until.with_timezone(&Utc).to_rfc3339_opts(SecondsFormat::Secs, true)),
                });
            }
        }

        let Some(dek) = unwrap_dek(&h, password)? else {
            let failed = h.failed_unlocks + 1;
            let next = throttle_delay(failed).map(|d| iso(now() + d));
            conn.execute(
                "UPDATE header SET failed_unlocks = ?1, next_unlock_after = ?2 WHERE id = 1",
                params![failed, next],
            )?;
            return Ok(UnlockResult::WrongPassword);
        };

        if let (Some(days), Some(last)) = (h.max_age_days, h.last_full_sync_at.as_deref()) {
            if let Ok(last) = DateTime::parse_from_rfc3339(last) {
                if last.with_timezone(&Utc) + Duration::days(days) < now() {
                    drop(conn);
                    remove_dir(&dir)?;
                    return Ok(UnlockResult::Expired);
                }
            }
        }

        conn.execute("UPDATE header SET failed_unlocks = 0, next_unlock_after = NULL WHERE id = 1", [])?;
        *g = Some(Session { username: username.to_string(), dir, conn, dek });
        Ok(UnlockResult::Ok)
    }

    /// Zabalí ten istý DEK pod KEK z nového hesla a nového saltu; dáta sa neprešifrovávajú.
    pub fn rewrap(&self, old_password: &str, new_password: &str) -> Result<()> {
        self.with_session(|s| {
            let h = read_header(&s.conn)?.ok_or_else(|| VaultError::new(VaultErrorKind::Missing, "Trezor nemá hlavičku."))?;
            if unwrap_dek(&h, old_password)?.is_none() {
                return Err(VaultError::new(VaultErrorKind::WrongPassword, "Nesprávne súčasné heslo."));
            }
            let mut salt = [0u8; 16];
            OsRng.fill_bytes(&mut salt);
            let kek = derive_kek(new_password, &salt, h.kdf)?;
            let (nonce, wrapped) = seal(&kek, DEK_AAD, s.dek.as_ref())?;
            s.conn.execute(
                "UPDATE header SET kdf_salt = ?1, dek_nonce = ?2, dek_wrapped = ?3,
                                   failed_unlocks = 0, next_unlock_after = NULL WHERE id = 1",
                params![salt, nonce, wrapped],
            )?;
            Ok(())
        })
    }

    /// Zamkne trezor; DEK sa vynuluje (`Zeroizing`) a spojenie sa zatvorí.
    pub fn lock(&self) {
        *self.lock_guard() = None;
    }

    pub fn is_unlocked(&self) -> bool {
        self.lock_guard().is_some()
    }

    pub fn wipe(&self, server_url: &str, username: &str) -> Result<()> {
        let mut g = self.lock_guard();
        *g = None;
        remove_dir(&self.dir(server_url, username))
    }

    pub fn put(&self, dataset: &str, key: &str, value: &Value, meta: Option<EntryMeta>) -> Result<()> {
        let meta = meta.unwrap_or_else(|| EntryMeta { etag: None, synced_at: iso(now()) });
        self.with_session(|s| {
            let json = Zeroizing::new(serde_json::to_vec(value)?);
            let (nonce, ct) = seal(&s.dek, &entry_aad(dataset, key), &json)?;
            let tx = s.conn.transaction()?;
            tx.execute(
                "INSERT INTO entry (dataset, key, nonce, ciphertext, etag, synced_at)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                 ON CONFLICT(dataset, key) DO UPDATE SET nonce = excluded.nonce,
                   ciphertext = excluded.ciphertext, etag = excluded.etag, synced_at = excluded.synced_at",
                params![dataset, key, nonce, ct, meta.etag, meta.synced_at],
            )?;
            tx.commit()?;
            Ok(())
        })
    }

    pub fn get(&self, dataset: &str, key: &str) -> Result<Option<(Value, EntryMeta)>> {
        self.with_session(|s| {
            let row = s
                .conn
                .query_row(
                    "SELECT nonce, ciphertext, etag, synced_at FROM entry WHERE dataset = ?1 AND key = ?2",
                    params![dataset, key],
                    |r| Ok((r.get::<_, Vec<u8>>(0)?, r.get::<_, Vec<u8>>(1)?, r.get(2)?, r.get(3)?)),
                )
                .optional()?;
            let Some((nonce, ct, etag, synced_at)) = row else { return Ok(None) };
            let plain = open(&s.dek, &nonce, &entry_aad(dataset, key), &ct)?;
            Ok(Some((serde_json::from_slice(&plain)?, EntryMeta { etag, synced_at })))
        })
    }

    pub fn list(&self, dataset: &str) -> Result<Vec<String>> {
        self.with_session(|s| {
            let mut st = s.conn.prepare("SELECT key FROM entry WHERE dataset = ?1 ORDER BY key")?;
            let keys = st.query_map(params![dataset], |r| r.get(0))?.collect::<std::result::Result<Vec<String>, _>>()?;
            Ok(keys)
        })
    }

    pub fn remove(&self, dataset: &str, keys: &[String]) -> Result<()> {
        self.with_session(|s| {
            let tx = s.conn.transaction()?;
            for k in keys {
                tx.execute("DELETE FROM entry WHERE dataset = ?1 AND key = ?2", params![dataset, k])?;
            }
            tx.commit()?;
            Ok(())
        })
    }

    pub fn info(&self) -> Result<VaultInfo> {
        self.with_session(|s| {
            let h = read_header(&s.conn)?.ok_or_else(|| VaultError::new(VaultErrorKind::Missing, "Trezor nemá hlavičku."))?;
            let size_bytes = std::fs::metadata(s.dir.join(DB_FILE)).map(|m| m.len()).unwrap_or(0);
            Ok(VaultInfo { username: s.username.clone(), last_full_sync_at: h.last_full_sync_at, size_bytes })
        })
    }

    /// Ukladá čas poslednej úplnej synchronizácie a `offline.maxAgeDays` (volá synchronizácia, F05).
    pub fn set_sync_state(&self, last_full_sync_at: &str, max_age_days: Option<i64>) -> Result<()> {
        self.with_session(|s| {
            s.conn.execute(
                "UPDATE header SET last_full_sync_at = ?1, max_age_days = ?2 WHERE id = 1",
                params![last_full_sync_at, max_age_days],
            )?;
            Ok(())
        })
    }
}

fn remove_dir(dir: &Path) -> Result<()> {
    match std::fs::remove_dir_all(dir) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const SRV: &str = "https://server.example.invalid";
    const FAST: KdfParams = KdfParams { m_kib: 64, t: 1, p: 1 };

    fn vault(dir: &tempfile::TempDir) -> Vault {
        Vault::with_kdf(dir.path(), FAST)
    }

    #[test]
    fn default_kdf_matches_design() {
        assert_eq!(KdfParams::DEFAULT, KdfParams { m_kib: 65_536, t: 3, p: 1 });
    }

    #[test]
    fn create_and_unlock() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        assert!(!v.exists(SRV, "Jana.Demo"));
        v.create(SRV, "jana.demo", "heslo-1").unwrap();
        v.put("plan", "2026-11-18", &json!({"a": 1}), None).unwrap();
        v.lock();
        assert!(v.exists(SRV, "JANA.demo"), "meno sa porovnáva bez ohľadu na veľkosť písmen");
        assert_eq!(v.unlock(SRV, "jana.demo", "heslo-1").unwrap(), UnlockResult::Ok);
        let (val, meta) = v.get("plan", "2026-11-18").unwrap().unwrap();
        assert_eq!(val, json!({"a": 1}));
        assert!(!meta.synced_at.is_empty());
        assert_eq!(v.list("plan").unwrap(), vec!["2026-11-18"]);
        v.remove("plan", &["2026-11-18".to_string()]).unwrap();
        assert!(v.get("plan", "2026-11-18").unwrap().is_none());
        assert_eq!(v.info().unwrap().username, "jana.demo");
    }

    #[test]
    fn missing_vault() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(vault(&d).unlock(SRV, "nikto", "x").unwrap(), UnlockResult::Missing);
    }

    #[test]
    fn data_access_while_locked_fails() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "p").unwrap();
        v.lock();
        for r in [
            v.put("a", "b", &json!(1), None).err(),
            v.get("a", "b").err(),
            v.list("a").err(),
            v.remove("a", &[]).err(),
            v.info().err(),
            v.rewrap("p", "q").err(),
        ] {
            assert_eq!(r.expect("musí zlyhať").kind, VaultErrorKind::Locked);
        }
    }

    #[test]
    fn wrong_password_keeps_locked() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "spravne").unwrap();
        v.lock();
        assert_eq!(v.unlock(SRV, "u", "zle").unwrap(), UnlockResult::WrongPassword);
        assert!(!v.is_unlocked());
        assert_eq!(v.get("a", "b").unwrap_err().kind, VaultErrorKind::Locked);
    }

    #[test]
    fn rewrap_changes_password_and_keeps_data() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "stare").unwrap();
        v.put("ds", "k", &json!("hodnota"), None).unwrap();
        assert_eq!(v.rewrap("nespravne", "nove").unwrap_err().kind, VaultErrorKind::WrongPassword);
        v.rewrap("stare", "nove").unwrap();
        v.lock();
        assert_eq!(v.unlock(SRV, "u", "stare").unwrap(), UnlockResult::WrongPassword);
        assert_eq!(v.unlock(SRV, "u", "nove").unwrap(), UnlockResult::Ok);
        assert_eq!(v.get("ds", "k").unwrap().unwrap().0, json!("hodnota"));
    }

    #[test]
    fn aad_binds_ciphertext_to_dataset_and_key() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "p").unwrap();
        v.put("ds", "a", &json!("A"), None).unwrap();
        v.put("ds", "b", &json!("B"), None).unwrap();
        v.with_session(|s| {
            // prehodenie kľúčov medzi záznamami
            s.conn.execute("UPDATE entry SET key = 'tmp' WHERE key = 'a'", [])?;
            s.conn.execute("UPDATE entry SET key = 'a' WHERE key = 'b'", [])?;
            s.conn.execute("UPDATE entry SET key = 'b' WHERE key = 'tmp'", [])?;
            Ok(())
        })
        .unwrap();
        assert_eq!(v.get("ds", "a").unwrap_err().kind, VaultErrorKind::Crypto);
        assert_eq!(v.get("ds", "b").unwrap_err().kind, VaultErrorKind::Crypto);
    }

    #[test]
    fn nonce_is_new_on_every_write() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "p").unwrap();
        let read = |v: &Vault| {
            v.with_session(|s| Ok(s.conn.query_row("SELECT nonce FROM entry", [], |r| r.get::<_, Vec<u8>>(0))?)).unwrap()
        };
        v.put("ds", "k", &json!(1), None).unwrap();
        let n1 = read(&v);
        v.put("ds", "k", &json!(1), None).unwrap();
        assert_ne!(n1, read(&v));
    }

    #[test]
    fn throttling_after_five_failures() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "spravne").unwrap();
        v.lock();
        for _ in 0..5 {
            assert_eq!(v.unlock(SRV, "u", "zle").unwrap(), UnlockResult::WrongPassword);
        }
        let err = v.unlock(SRV, "u", "spravne").unwrap_err();
        assert_eq!(err.kind, VaultErrorKind::Throttled);
        assert!(err.until.is_some());
        assert!(!v.is_unlocked());
    }

    #[test]
    fn throttle_schedule() {
        assert_eq!(throttle_delay(4), None);
        assert_eq!(throttle_delay(5), Some(Duration::seconds(30)));
        assert_eq!(throttle_delay(6), Some(Duration::minutes(1)));
        assert_eq!(throttle_delay(7), Some(Duration::minutes(2)));
        assert_eq!(throttle_delay(8), Some(Duration::minutes(5)));
        assert_eq!(throttle_delay(9), Some(Duration::minutes(15)));
        assert_eq!(throttle_delay(40), Some(Duration::minutes(15)));
    }

    #[test]
    fn successful_unlock_resets_counter() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "p").unwrap();
        v.lock();
        for _ in 0..3 {
            v.unlock(SRV, "u", "zle").unwrap();
        }
        assert_eq!(v.unlock(SRV, "u", "p").unwrap(), UnlockResult::Ok);
        v.lock();
        for _ in 0..4 {
            v.unlock(SRV, "u", "zle").unwrap();
        }
        assert_eq!(v.unlock(SRV, "u", "p").unwrap(), UnlockResult::Ok, "počítadlo sa vynulovalo");
    }

    #[test]
    fn expired_vault_is_deleted() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "p").unwrap();
        let old = iso(now() - Duration::days(10));
        v.set_sync_state(&old, Some(7)).unwrap();
        v.lock();
        assert_eq!(v.unlock(SRV, "u", "p").unwrap(), UnlockResult::Expired);
        assert!(!v.exists(SRV, "u"));
        assert_eq!(v.unlock(SRV, "u", "p").unwrap(), UnlockResult::Missing);
    }

    #[test]
    fn fresh_sync_is_not_expired() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "p").unwrap();
        v.set_sync_state(&iso(now() - Duration::days(1)), Some(7)).unwrap();
        v.lock();
        assert_eq!(v.unlock(SRV, "u", "p").unwrap(), UnlockResult::Ok);
        assert!(v.info().unwrap().last_full_sync_at.is_some());
    }

    #[test]
    fn create_replaces_old_vault_and_wipe_deletes() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "stare").unwrap();
        v.put("ds", "k", &json!(1), None).unwrap();
        v.create(SRV, "u", "nove").unwrap();
        assert!(v.get("ds", "k").unwrap().is_none());
        v.wipe(SRV, "u").unwrap();
        assert!(!v.exists(SRV, "u"));
        assert!(!v.is_unlocked());
        v.wipe(SRV, "u").unwrap(); // idempotentné
    }

    #[test]
    fn vaults_are_separate_per_user_and_server() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "a", "p").unwrap();
        assert!(!v.exists(SRV, "b"));
        assert!(!v.exists("https://iny.example.invalid", "a"));
        assert_ne!(vault_dir_name(SRV, "a"), vault_dir_name(SRV, "b"));
    }

    #[test]
    fn files_contain_no_plaintext() {
        let d = tempfile::tempdir().unwrap();
        let v = vault(&d);
        v.create(SRV, "u", "p").unwrap();
        let secret = "TAJNY-RETAZEC-PACIENT-XYZ";
        v.put("ds", "k", &json!({ "meno": secret }), None).unwrap();
        // zachytí aj obsah ešte nezapísaný do hlavného súboru (WAL)
        let mut found_db = false;
        let mut total = 0;
        for e in std::fs::read_dir(d.path().join("vaults").join(vault_dir_name(SRV, "u"))).unwrap() {
            let e = e.unwrap();
            let bytes = std::fs::read(e.path()).unwrap();
            total += bytes.len();
            assert!(
                !bytes.windows(secret.len()).any(|w| w == secret.as_bytes()),
                "{:?} obsahuje otvorený text",
                e.file_name()
            );
            found_db |= e.file_name() == DB_FILE;
        }
        assert!(found_db && total > 0);
        v.lock();
        // po zamknutí (checkpoint) znovu skontroluj hlavný súbor
        let bytes = std::fs::read(d.path().join("vaults").join(vault_dir_name(SRV, "u")).join(DB_FILE)).unwrap();
        assert!(!bytes.windows(secret.len()).any(|w| w == secret.as_bytes()));
    }
}
