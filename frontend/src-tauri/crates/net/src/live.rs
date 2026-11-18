//! SSE spojenie `GET /api/events` s opätovným pripojením (DLD kap. 3.2).
use crate::http::{map_error, NetCore};
use crate::sse::{reconnect_delay, SseParser};
use crate::url_util::build_url;
use futures_util::StreamExt;
use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeMap;
use std::sync::Arc;
use tokio::sync::watch;

#[derive(Debug, Clone, Serialize)]
pub struct LiveEventMsg {
    #[serde(rename = "type")]
    pub kind: String,
    pub data: Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct LiveStateMsg {
    pub state: &'static str, // connecting | open | closed
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<&'static str>,
}

pub trait LiveSink: Send + Sync + 'static {
    fn event(&self, e: LiveEventMsg);
    fn state(&self, s: LiveStateMsg);
}

fn state(sink: &dyn LiveSink, state: &'static str, reason: Option<&'static str>) {
    sink.state(LiveStateMsg { state, reason });
}

/// Beží, kým nepríde `stop` alebo odpoveď 401.
pub async fn run(core: Arc<NetCore>, sink: Arc<dyn LiveSink>, mut stop: watch::Receiver<bool>) {
    let mut attempt = 0usize;
    loop {
        state(sink.as_ref(), "connecting", None);
        let outcome = tokio::select! {
            _ = stop.changed() => Outcome::Stopped,
            o = connect_once(&core, sink.as_ref(), &mut attempt) => o,
        };
        match outcome {
            Outcome::Stopped => {
                state(sink.as_ref(), "closed", None);
                return;
            }
            Outcome::Unauthorized => {
                state(sink.as_ref(), "closed", Some("unauthorized"));
                return;
            }
            Outcome::Config => {
                state(sink.as_ref(), "closed", Some("config"));
                return;
            }
            Outcome::Retry => {}
        }
        state(sink.as_ref(), "connecting", None);
        let delay = reconnect_delay(attempt);
        attempt += 1;
        tokio::select! {
            _ = stop.changed() => {
                state(sink.as_ref(), "closed", None);
                return;
            }
            _ = tokio::time::sleep(delay) => {}
        }
    }
}

enum Outcome {
    Stopped,
    Unauthorized,
    Config,
    Retry,
}

async fn connect_once(core: &NetCore, sink: &dyn LiveSink, attempt: &mut usize) -> Outcome {
    let (client, server) = match core.client_and_server() {
        Ok(v) => v,
        Err(_) => return Outcome::Config,
    };
    let url = match build_url(&server, "/api/events", &BTreeMap::new()) {
        Ok(u) => u,
        Err(_) => return Outcome::Config,
    };
    let res = match client
        .get(url)
        .header("Accept", "text/event-stream")
        .header("Cache-Control", "no-cache")
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => {
            log::warn!("GET /api/events -> chyba {:?}", map_error(e).kind);
            return Outcome::Retry;
        }
    };
    let status = res.status();
    log::info!("GET /api/events -> {}", status.as_u16());
    if status.as_u16() == 401 {
        return Outcome::Unauthorized;
    }
    if !status.is_success() {
        return Outcome::Retry;
    }
    *attempt = 0;
    state(sink, "open", None);
    let mut parser = SseParser::new();
    let mut stream = res.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let Ok(chunk) = chunk else { return Outcome::Retry };
        for ev in parser.feed(&chunk) {
            let data = serde_json::from_str(&ev.data).unwrap_or(Value::String(ev.data));
            sink.event(LiveEventMsg { kind: ev.event, data, id: ev.id });
        }
    }
    Outcome::Retry
}
