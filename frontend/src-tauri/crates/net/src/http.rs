//! HTTP klient: reqwest + overenie certifikátu cez úložisko OS + cookie jar v pamäti.
use crate::csrf;
use crate::types::{HttpRequest, HttpResponse, NetError, NetErrorKind};
use crate::url_util::build_url;
use rustls_platform_verifier::BuilderVerifierExt;
use reqwest::cookie::{CookieStore, Jar};
use reqwest::{Client, Method};
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use url::Url;

const DEFAULT_TIMEOUT_MS: u64 = 15_000;

#[derive(Clone)]
struct State {
    server: Option<Url>,
    jar: Arc<Jar>,
    client: Client,
}

pub struct NetCore {
    state: Mutex<State>,
}

pub(crate) fn build_client(jar: Option<Arc<Jar>>) -> Result<Client, NetError> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    // Certifikáty z úložiska OS (na Windows aj interná CA zákazníka).
    let tls = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| NetError::new(NetErrorKind::Tls, e.to_string()))?
        .with_platform_verifier()
        .with_no_client_auth();
    let mut builder = Client::builder()
        .use_preconfigured_tls(tls)
        .connect_timeout(Duration::from_secs(10))
        .redirect(reqwest::redirect::Policy::none());
    if let Some(jar) = jar {
        builder = builder.cookie_provider(jar);
    }
    builder.build().map_err(|e| NetError::new(NetErrorKind::Config, e.to_string()))
}

impl NetCore {
    pub fn new() -> Result<Self, NetError> {
        let jar = Arc::new(Jar::default());
        let client = build_client(Some(jar.clone()))?;
        Ok(Self { state: Mutex::new(State { server: None, jar, client }) })
    }

    /// Nastaví server; relácia (cookie jar) sa pri zmene adresy zahodí.
    pub fn set_server(&self, server: Option<Url>) -> Result<(), NetError> {
        let jar = Arc::new(Jar::default());
        let client = build_client(Some(jar.clone()))?;
        *self.state.lock().unwrap() = State { server, jar, client };
        Ok(())
    }

    pub fn server(&self) -> Option<Url> {
        self.state.lock().unwrap().server.clone()
    }

    pub(crate) fn client_and_server(&self) -> Result<(Client, Url), NetError> {
        let st = self.state.lock().unwrap().clone();
        let server = st.server.ok_or_else(|| NetError::config("Adresa servera nie je nastavená"))?;
        Ok((st.client, server))
    }

    fn xsrf_token(&self, server: &Url) -> Option<String> {
        let st = self.state.lock().unwrap();
        let header = st.jar.cookies(server)?;
        csrf::token_from_cookie_header(header.to_str().ok()?)
    }

    pub async fn request(&self, req: HttpRequest) -> Result<HttpResponse, NetError> {
        let started = Instant::now();
        let method = req.method.to_ascii_uppercase();
        let result = self.send(&req, &method).await;
        // Log: metóda, cesta bez query, status, trvanie. Nikdy telo.
        let ms = started.elapsed().as_millis();
        match &result {
            Ok(r) => log::info!("{method} {} -> {} ({ms} ms)", req.path, r.status),
            Err(e) => log::warn!("{method} {} -> chyba {:?} ({ms} ms)", req.path, e.kind),
        }
        result
    }

    async fn send(&self, req: &HttpRequest, method: &str) -> Result<HttpResponse, NetError> {
        let (client, server) = self.client_and_server()?;
        let url = build_url(&server, &req.path, &req.query)?;
        let http_method = Method::from_bytes(method.as_bytes())
            .ok()
            .filter(|m| matches!(m.as_str(), "GET" | "POST" | "PUT" | "PATCH" | "DELETE"))
            .ok_or_else(|| NetError::config(format!("Nepovolená metóda: {method}")))?;
        let timeout = Duration::from_millis(req.timeout_ms.unwrap_or(DEFAULT_TIMEOUT_MS));

        let mut builder = client.request(http_method, url).timeout(timeout);
        for (k, v) in &req.headers {
            let lk = k.to_ascii_lowercase();
            if matches!(lk.as_str(), "cookie" | "host" | "x-xsrf-token") {
                continue;
            }
            builder = builder.header(k, v);
        }
        let bytes_mode = req.accept.as_deref() == Some("bytes");
        builder = builder.header("Accept", if bytes_mode { "*/*" } else { "application/json" });
        if let Some(body) = &req.body {
            builder = builder.json(body);
        }
        if csrf::is_mutating(method) {
            let mut token = self.xsrf_token(&server);
            if token.is_none() {
                // Cookie ešte nie je – backend ju pošle pri GET /api/me.
                let me = build_url(&server, "/api/me", &BTreeMap::new())?;
                let _ = client.get(me).timeout(timeout).send().await;
                token = self.xsrf_token(&server);
            }
            if let Some(t) = token {
                builder = builder.header(csrf::HEADER_NAME, t);
            }
        }

        let res = builder.send().await.map_err(map_error)?;
        let status = res.status().as_u16();
        let mut headers: BTreeMap<String, String> = BTreeMap::new();
        for (name, value) in res.headers() {
            let value = String::from_utf8_lossy(value.as_bytes()).into_owned();
            headers
                .entry(name.as_str().to_ascii_lowercase())
                .and_modify(|e| {
                    e.push_str(", ");
                    e.push_str(&value);
                })
                .or_insert(value);
        }
        let body = res.bytes().await.map_err(map_error)?;
        let mut out = HttpResponse { status, headers, json: None, bytes: None };
        if bytes_mode {
            out.bytes = Some(body.to_vec());
        } else if !body.is_empty() {
            out.json = serde_json::from_slice(&body).ok();
        }
        Ok(out)
    }
}

pub(crate) fn map_error(e: reqwest::Error) -> NetError {
    if e.is_timeout() {
        return NetError::new(NetErrorKind::Timeout, "Vypršal čas požiadavky");
    }
    let mut chain = e.to_string();
    let mut src = std::error::Error::source(&e);
    while let Some(s) = src {
        chain.push_str(": ");
        chain.push_str(&s.to_string());
        src = s.source();
    }
    let lower = chain.to_ascii_lowercase();
    let kind = if lower.contains("certificate") || lower.contains("tls") || lower.contains("handshake") {
        NetErrorKind::Tls
    } else {
        NetErrorKind::Network
    };
    NetError::new(kind, chain)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    /// Minimálny HTTP server: odpovede podľa poradia, zachytáva surové požiadavky.
    async fn spawn(responses: Vec<&'static str>) -> (Url, Arc<Mutex<Vec<String>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let seen = Arc::new(Mutex::new(Vec::new()));
        let seen2 = seen.clone();
        tokio::spawn(async move {
            for resp in responses {
                let (mut sock, _) = listener.accept().await.unwrap();
                let mut buf = vec![0u8; 8192];
                let n = sock.read(&mut buf).await.unwrap();
                seen2.lock().unwrap().push(String::from_utf8_lossy(&buf[..n]).into_owned());
                sock.write_all(resp.as_bytes()).await.unwrap();
                sock.shutdown().await.ok();
            }
        });
        (Url::parse(&format!("http://127.0.0.1:{port}")).unwrap(), seen)
    }

    fn req(method: &str, path: &str) -> HttpRequest {
        HttpRequest {
            method: method.into(),
            path: path.into(),
            query: BTreeMap::new(),
            body: None,
            headers: BTreeMap::new(),
            accept: None,
            timeout_ms: Some(3000),
        }
    }

    #[tokio::test]
    async fn doplni_csrf_hlavicku_z_cookie() {
        let (url, seen) = spawn(vec![
            "HTTP/1.1 200 OK\r\nSet-Cookie: XSRF-TOKEN=abc123; Path=/\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
        ])
        .await;
        let core = NetCore::new().unwrap();
        core.set_server(Some(url)).unwrap();
        let res = core.request(req("POST", "/api/x")).await.unwrap();
        assert_eq!(res.status, 200);
        let seen = seen.lock().unwrap();
        assert!(seen[0].starts_with("GET /api/me "), "najprv GET /api/me: {}", seen[0]);
        assert!(seen[1].starts_with("POST /api/x "));
        assert!(seen[1].to_ascii_lowercase().contains("x-xsrf-token: abc123"));
    }

    #[tokio::test]
    async fn get_nema_csrf_hlavicku_a_odmieta_cestu() {
        let (url, seen) =
            spawn(vec!["HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"]).await;
        let core = NetCore::new().unwrap();
        core.set_server(Some(url)).unwrap();
        core.request(req("GET", "/api/me")).await.unwrap();
        assert!(!seen.lock().unwrap()[0].to_ascii_lowercase().contains("x-xsrf-token"));
        let err = core.request(req("GET", "/etc/passwd")).await.unwrap_err();
        assert_eq!(err.kind, NetErrorKind::Config);
    }

    #[tokio::test]
    async fn bez_servera_je_chyba_config() {
        let core = NetCore::new().unwrap();
        let err = core.request(req("GET", "/api/me")).await.unwrap_err();
        assert_eq!(err.kind, NetErrorKind::Config);
    }
}
