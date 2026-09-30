//! The server-sent-events parser: bytes in, frames out, no I/O (CONTRACT.md
//! K5, Parsing; ADR 29.9.26p D3).
//!
//! Bytes are buffered until a line ends, and only a complete line is decoded
//! as UTF-8, so a character split across chunks never meets a decoder
//! half-formed. The frames are therefore the same however the bytes were
//! chunked, down to one byte at a time.

/// One dispatched frame: its event name and its joined `data`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frame {
    pub event: String,
    pub data: String,
}

#[derive(Debug, Default)]
pub struct SseParser {
    line: Vec<u8>,
    event: String,
    data: Vec<String>,
    has_data: bool,
    // The last chunk ended on `\r`: a `\n` opening the next one is the same
    // line end, not a blank line.
    skip_lf: bool,
}

impl SseParser {
    pub fn new() -> Self {
        Self::default()
    }

    /// Feeds bytes; returns every frame they completed.
    pub fn push(&mut self, bytes: &[u8]) -> Vec<Frame> {
        let mut frames = Vec::new();
        for &byte in bytes {
            if std::mem::take(&mut self.skip_lf) && byte == b'\n' {
                continue;
            }
            match byte {
                b'\n' => self.end_line(&mut frames),
                b'\r' => {
                    self.end_line(&mut frames);
                    self.skip_lf = true;
                }
                _ => self.line.push(byte),
            }
        }
        frames
    }

    /// End of input. An undispatched frame is discarded, as WHATWG says, so
    /// this never returns a frame; it exists so the end is explicit.
    pub fn finish(self) -> Vec<Frame> {
        Vec::new()
    }

    fn end_line(&mut self, frames: &mut Vec<Frame>) {
        let line = String::from_utf8_lossy(&std::mem::take(&mut self.line)).into_owned();
        if line.is_empty() {
            self.dispatch(frames);
            return;
        }
        if line.starts_with(':') {
            return;
        }
        let (field, value) = match line.split_once(':') {
            Some((field, value)) => (field, value.strip_prefix(' ').unwrap_or(value)),
            None => (line.as_str(), ""),
        };
        match field {
            "event" => self.event = value.to_owned(),
            "data" => {
                self.data.push(value.to_owned());
                self.has_data = true;
            }
            // `id`, `retry` and unknown fields are ignored.
            _ => {}
        }
    }

    fn dispatch(&mut self, frames: &mut Vec<Frame>) {
        let event = std::mem::take(&mut self.event);
        let data = std::mem::take(&mut self.data);
        if std::mem::take(&mut self.has_data) {
            let event = if event.is_empty() { "message".to_owned() } else { event };
            frames.push(Frame { event, data: data.join("\n") });
        }
    }
}

#[cfg(test)]
#[path = "tests/sse_tests.rs"]
mod tests;
