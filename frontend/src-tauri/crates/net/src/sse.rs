//! Parser Server-Sent Events a oneskorenia opätovného pripojenia (DLD kap. 3.2).
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SseEvent {
    pub event: String,
    pub data: String,
    pub id: Option<String>,
}

#[derive(Default)]
pub struct SseParser {
    line: Vec<u8>,
    skip_lf: bool,
    started: bool,
    event: Option<String>,
    data: Vec<String>,
    id: Option<String>,
}

impl SseParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Spracuje ďalší kus bajtov a vráti dokončené udalosti.
    pub fn feed(&mut self, chunk: &[u8]) -> Vec<SseEvent> {
        let mut out = Vec::new();
        for &b in chunk {
            if self.skip_lf {
                self.skip_lf = false;
                if b == b'\n' {
                    continue;
                }
            }
            match b {
                b'\r' => {
                    self.skip_lf = true;
                    self.end_line(&mut out);
                }
                b'\n' => self.end_line(&mut out),
                _ => self.line.push(b),
            }
        }
        out
    }

    fn end_line(&mut self, out: &mut Vec<SseEvent>) {
        let mut raw = std::mem::take(&mut self.line);
        if !self.started {
            self.started = true;
            if raw.starts_with(&[0xEF, 0xBB, 0xBF]) {
                raw.drain(..3);
            }
        }
        let line = String::from_utf8_lossy(&raw).into_owned();
        if line.is_empty() {
            if !self.data.is_empty() {
                out.push(SseEvent {
                    event: self.event.take().unwrap_or_else(|| "message".to_string()),
                    data: self.data.join("\n"),
                    id: self.id.clone(),
                });
            }
            self.event = None;
            self.data.clear();
            return;
        }
        if line.starts_with(':') {
            return; // komentár
        }
        let (field, value) = match line.split_once(':') {
            Some((f, v)) => (f, v.strip_prefix(' ').unwrap_or(v)),
            None => (line.as_str(), ""),
        };
        match field {
            "event" => self.event = Some(value.to_string()),
            "data" => self.data.push(value.to_string()),
            "id" if !value.contains('\0') => self.id = Some(value.to_string()),
            _ => {}
        }
    }
}

/// Oneskorenia 1, 2, 5, 10, 30 s; ďalej stále 30 s.
pub fn reconnect_delay(attempt: usize) -> Duration {
    const STEPS: [u64; 5] = [1, 2, 5, 10, 30];
    Duration::from_secs(STEPS[attempt.min(STEPS.len() - 1)])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(input: &str) -> Vec<SseEvent> {
        SseParser::new().feed(input.as_bytes())
    }

    #[test]
    fn jednoducha_udalost_s_id() {
        let ev = parse("id: 7\nevent: plan-changed\ndata: {\"a\":1}\n\n");
        assert_eq!(
            ev,
            vec![SseEvent {
                event: "plan-changed".into(),
                data: "{\"a\":1}".into(),
                id: Some("7".into())
            }]
        );
    }

    #[test]
    fn viacriadkove_data() {
        let ev = parse("event: notification\ndata: a\ndata: b\ndata:\ndata: c\n\n");
        assert_eq!(ev[0].data, "a\nb\n\nc");
    }

    #[test]
    fn komentare_a_neznáme_polia_sa_ignoruju() {
        let ev = parse(": keepalive\nretry: 100\nfoo: bar\n\ndata: x\n\n");
        assert_eq!(ev.len(), 1);
        assert_eq!(ev[0].event, "message");
        assert_eq!(ev[0].data, "x");
    }

    #[test]
    fn id_pretrvava_medzi_udalostami() {
        let ev = parse("id: 1\ndata: a\n\ndata: b\n\n");
        assert_eq!(ev[1].id.as_deref(), Some("1"));
    }

    #[test]
    fn udalost_bez_data_sa_neposiela() {
        assert!(parse("event: heartbeat\n\n").is_empty());
    }

    #[test]
    fn crlf_a_rozdelene_kusy() {
        let mut p = SseParser::new();
        let mut ev = p.feed(b"event: heart");
        ev.extend(p.feed(b"beat\r"));
        ev.extend(p.feed(b"\ndata: {}\r\n\r"));
        ev.extend(p.feed(b"\n"));
        assert_eq!(ev.len(), 1);
        assert_eq!(ev[0].event, "heartbeat");
        assert_eq!(ev[0].data, "{}");
    }

    #[test]
    fn utf8_rozdelene_medzi_kusmi() {
        let bytes = "data: žluťoučký\n\n".as_bytes();
        let mut p = SseParser::new();
        let mut ev = p.feed(&bytes[..8]);
        ev.extend(p.feed(&bytes[8..]));
        assert_eq!(ev[0].data, "žluťoučký");
    }

    #[test]
    fn oneskorenia_pripojenia() {
        let secs: Vec<u64> = (0..7).map(|i| reconnect_delay(i).as_secs()).collect();
        assert_eq!(secs, vec![1, 2, 5, 10, 30, 30, 30]);
    }
}
