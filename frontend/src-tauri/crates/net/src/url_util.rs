//! Skladanie URL a kontrola povolených ciest (DLD kap. 3.2).
use crate::types::NetError;
use serde_json::Value;
use std::collections::BTreeMap;
pub use url::Url;

/// Overí a normalizuje adresu servera. `http://` je povolené len ak `allow_http`
/// (v aplikácii `cfg!(debug_assertions)`).
pub fn parse_server_url(raw: &str, allow_http: bool) -> Result<Url, NetError> {
    let url = Url::parse(raw.trim()).map_err(|_| NetError::config("Neplatná adresa servera"))?;
    match url.scheme() {
        "https" => {}
        "http" if allow_http => {}
        _ => return Err(NetError::config("Adresa servera musí začínať https://")),
    }
    if url.host_str().is_none() || url.query().is_some() || url.fragment().is_some() {
        return Err(NetError::config("Neplatná adresa servera"));
    }
    Ok(url)
}

pub fn is_allowed_path(path: &str) -> bool {
    if path.contains(['?', '#', '\\']) || path.contains("//") {
        return false;
    }
    if path.split('/').any(|seg| seg == ".." || seg == "." || seg.eq_ignore_ascii_case("%2e%2e")) {
        return false;
    }
    path.starts_with("/api/") || path.starts_with("/updates/") || path == "/actuator/health"
}

pub fn build_url(
    server: &Url,
    path: &str,
    query: &BTreeMap<String, Value>,
) -> Result<Url, NetError> {
    if !is_allowed_path(path) {
        return Err(NetError::config(format!("Nepovolená cesta: {path}")));
    }
    let mut url = server.clone();
    let base = server.path().trim_end_matches('/');
    url.set_path(&format!("{base}{path}"));
    url.set_query(None);
    {
        let mut pairs = url.query_pairs_mut();
        for (key, value) in query {
            match value {
                Value::Null => {}
                Value::Array(items) => {
                    for item in items {
                        pairs.append_pair(key, &scalar(item));
                    }
                }
                other => {
                    pairs.append_pair(key, &scalar(other));
                }
            }
        }
    }
    if url.query() == Some("") {
        url.set_query(None);
    }
    Ok(url)
}

fn scalar(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn server() -> Url {
        parse_server_url("https://therasuite.example.invalid", false).unwrap()
    }

    #[test]
    fn skladanie_url_s_query_a_poliami() {
        let mut q = BTreeMap::new();
        q.insert("a".to_string(), json!(1));
        q.insert("b".to_string(), json!(["u", "v"]));
        q.insert("c".to_string(), Value::Null);
        q.insert("d".to_string(), json!(true));
        let url = build_url(&server(), "/api/x", &q).unwrap();
        assert_eq!(url.as_str(), "https://therasuite.example.invalid/api/x?a=1&b=u&b=v&d=true");
    }

    #[test]
    fn zachova_predponu_cesty_servera() {
        let s = parse_server_url("https://host.example.invalid/ts/", false).unwrap();
        let url = build_url(&s, "/api/me", &BTreeMap::new()).unwrap();
        assert_eq!(url.as_str(), "https://host.example.invalid/ts/api/me");
    }

    #[test]
    fn odmietne_nepovolene_cesty() {
        for p in [
            "/etc/passwd",
            "/actuator/env",
            "/api/../admin",
            "/api//x",
            "/api/x?y=1",
            "api/x",
            "//evil.invalid/api/x",
        ] {
            let err = build_url(&server(), p, &BTreeMap::new()).unwrap_err();
            assert_eq!(err.kind, crate::NetErrorKind::Config, "{p}");
        }
    }

    #[test]
    fn povoli_health_a_updates() {
        assert!(build_url(&server(), "/actuator/health", &BTreeMap::new()).is_ok());
        assert!(build_url(&server(), "/updates/latest.json", &BTreeMap::new()).is_ok());
    }

    #[test]
    fn https_povinne_mimo_debug() {
        assert!(parse_server_url("http://localhost:8080", false).is_err());
        assert!(parse_server_url("http://localhost:8080", true).is_ok());
        assert!(parse_server_url("ftp://x.invalid", true).is_err());
        assert!(parse_server_url("https://x.invalid/?a=1", false).is_err());
    }
}
