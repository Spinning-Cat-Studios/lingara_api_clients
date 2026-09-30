//! Just enough HTTP/1.1 for the mock, over a raw socket.
//!
//! The mock is written against `TcpStream` rather than a framework because
//! three of D10's shapes need the socket itself: each `chunks` entry flushed
//! as its own write, `then: reset` as a TCP reset (linger zero), and `hold`
//! noticing the client's disconnect. One request per connection; every
//! response says `connection: close`, and a stream's body ends at close.

use std::io;

use tokio::io::{AsyncRead, AsyncReadExt};

const MAX_HEAD: usize = 64 * 1024;
const MAX_BODY: usize = 8 * 1024 * 1024;

#[derive(Debug, Clone, Default)]
pub struct HttpRequest {
    pub method: String,
    pub path: String,
    pub query: Option<String>,
    /// Names lowercased, in arrival order.
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl HttpRequest {
    /// The first value of `name` (lowercase).
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(n, _)| n == name).map(|(_, v)| v.as_str())
    }
}

/// Reads one request, or `None` when the peer closed before sending one.
pub async fn read_request<R: AsyncRead + Unpin>(r: &mut R) -> io::Result<Option<HttpRequest>> {
    let mut buf = Vec::new();
    loop {
        let mut headers = [httparse::EMPTY_HEADER; 64];
        let mut parsed = httparse::Request::new(&mut headers);
        match parsed.parse(&buf).map_err(invalid)? {
            httparse::Status::Complete(len) => {
                let request = head_of(&parsed)?;
                let rest = buf.split_off(len);
                return read_body(r, request, rest).await.map(Some);
            }
            httparse::Status::Partial if buf.len() > MAX_HEAD => {
                return Err(invalid("request head too large"));
            }
            httparse::Status::Partial => {}
        }
        if !fill(r, &mut buf).await? {
            return if buf.is_empty() { Ok(None) } else { Err(eof()) };
        }
    }
}

fn head_of(parsed: &httparse::Request) -> io::Result<HttpRequest> {
    let target = parsed.path.ok_or_else(|| invalid("no request target"))?;
    let (path, query) = match target.split_once('?') {
        Some((p, q)) => (p.to_string(), Some(q.to_string())),
        None => (target.to_string(), None),
    };
    let headers = parsed
        .headers
        .iter()
        .map(|h| (h.name.to_ascii_lowercase(), String::from_utf8_lossy(h.value).into_owned()))
        .collect();
    let method = parsed.method.ok_or_else(|| invalid("no method"))?.to_string();
    Ok(HttpRequest { method, path, query, headers, body: Vec::new() })
}

async fn read_body<R: AsyncRead + Unpin>(
    r: &mut R,
    mut request: HttpRequest,
    mut buf: Vec<u8>,
) -> io::Result<HttpRequest> {
    let chunked = request
        .header("transfer-encoding")
        .is_some_and(|v| v.to_ascii_lowercase().contains("chunked"));
    if chunked {
        loop {
            if let Some(body) = dechunk(&buf)? {
                request.body = body;
                return Ok(request);
            }
            if buf.len() > MAX_BODY || !fill(r, &mut buf).await? {
                return Err(eof());
            }
        }
    }
    let length = match request.header("content-length") {
        Some(v) => v.trim().parse::<usize>().map_err(invalid)?,
        None => 0,
    };
    if length > MAX_BODY {
        return Err(invalid("request body too large"));
    }
    while buf.len() < length {
        if !fill(r, &mut buf).await? {
            return Err(eof());
        }
    }
    buf.truncate(length);
    request.body = buf;
    Ok(request)
}

/// A complete chunked body, or `None` while more bytes are needed.
fn dechunk(buf: &[u8]) -> io::Result<Option<Vec<u8>>> {
    let mut body = Vec::new();
    let mut at = 0;
    loop {
        let Some(eol) = find(&buf[at..], b"\r\n") else { return Ok(None) };
        let line = std::str::from_utf8(&buf[at..at + eol]).map_err(invalid)?;
        let size_hex = line.split(';').next().unwrap_or("").trim();
        let size = usize::from_str_radix(size_hex, 16).map_err(invalid)?;
        at += eol + 2;
        if size == 0 {
            return Ok(find(&buf[at..], b"\r\n").map(|_| body));
        }
        if buf.len() < at + size + 2 {
            return Ok(None);
        }
        body.extend_from_slice(&buf[at..at + size]);
        at += size + 2;
    }
}

fn find(hay: &[u8], needle: &[u8]) -> Option<usize> {
    hay.windows(needle.len()).position(|w| w == needle)
}

async fn fill<R: AsyncRead + Unpin>(r: &mut R, buf: &mut Vec<u8>) -> io::Result<bool> {
    let mut chunk = [0u8; 8192];
    let n = r.read(&mut chunk).await?;
    buf.extend_from_slice(&chunk[..n]);
    Ok(n > 0)
}

/// The status line and headers, ending in the blank line.
pub fn head(status: u16, headers: &[(String, String)]) -> Vec<u8> {
    let mut out = format!("HTTP/1.1 {status} {}\r\n", reason(status));
    for (name, value) in headers {
        out.push_str(&format!("{name}: {value}\r\n"));
    }
    out.push_str("connection: close\r\n\r\n");
    out.into_bytes()
}

/// A whole response with a fixed-length body.
pub fn whole(status: u16, content_type: &str, body: &[u8]) -> Vec<u8> {
    let headers = [
        ("content-type".to_string(), content_type.to_string()),
        ("content-length".to_string(), body.len().to_string()),
    ];
    let mut out = head(status, &headers);
    out.extend_from_slice(body);
    out
}

const REASONS: &[(u16, &str)] = &[
    (200, "OK"),
    (400, "Bad Request"),
    (401, "Unauthorized"),
    (402, "Payment Required"),
    (403, "Forbidden"),
    (404, "Not Found"),
    (409, "Conflict"),
    (410, "Gone"),
    (429, "Too Many Requests"),
    (500, "Internal Server Error"),
    (502, "Bad Gateway"),
    (503, "Service Unavailable"),
    (599, "Conformance Mismatch"),
];

fn reason(status: u16) -> &'static str {
    REASONS.iter().find(|(s, _)| *s == status).map_or("Status", |(_, r)| r)
}

fn invalid<E: ToString>(e: E) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, e.to_string())
}

fn eof() -> io::Error {
    io::Error::new(io::ErrorKind::UnexpectedEof, "connection closed mid-request")
}
