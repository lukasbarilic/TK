//! Doplnenie `X-XSRF-TOKEN` (DLD kap. 3.2).
use percent_encoding::percent_decode_str;

pub const COOKIE_NAME: &str = "XSRF-TOKEN";
pub const HEADER_NAME: &str = "X-XSRF-TOKEN";

pub fn is_mutating(method: &str) -> bool {
    matches!(method.to_ascii_uppercase().as_str(), "POST" | "PUT" | "PATCH" | "DELETE")
}

/// Z hlavičky `Cookie` (`a=b; XSRF-TOKEN=...`) vytiahne hodnotu tokenu.
pub fn token_from_cookie_header(cookie_header: &str) -> Option<String> {
    cookie_header.split(';').find_map(|part| {
        let (k, v) = part.trim().split_once('=')?;
        (k == COOKIE_NAME && !v.is_empty())
            .then(|| percent_decode_str(v).decode_utf8_lossy().into_owned())
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nacita_token_z_cookie() {
        assert_eq!(
            token_from_cookie_header("JSESSIONID=abc; XSRF-TOKEN=tok%3D1; x=y").as_deref(),
            Some("tok=1")
        );
    }

    #[test]
    fn bez_tokenu() {
        assert_eq!(token_from_cookie_header("JSESSIONID=abc"), None);
        assert_eq!(token_from_cookie_header("XSRF-TOKEN="), None);
        assert_eq!(token_from_cookie_header("X-XSRF-TOKEN=a"), None);
    }

    #[test]
    fn len_zapisove_metody() {
        for m in ["POST", "put", "PATCH", "DELETE"] {
            assert!(is_mutating(m));
        }
        for m in ["GET", "HEAD", "OPTIONS"] {
            assert!(!is_mutating(m));
        }
    }
}
