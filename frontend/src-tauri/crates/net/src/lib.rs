//! Sieťové jadro TheraSuite bez závislosti na Tauri (testovateľné aj na Linuxe).
pub mod config;
pub mod csrf;
pub mod http;
pub mod live;
pub mod sse;
pub mod types;
pub mod url_util;

pub use http::NetCore;
pub use types::{HttpRequest, HttpResponse, NetError, NetErrorKind};
