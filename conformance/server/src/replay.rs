//! Scripted responses (ADR 29.9.26n D10): the head, a whole body, or SSE
//! chunks written one per flush as HTTP/1.1 chunks, then `close` (the last
//! chunk and a FIN), `reset` (an RST, no last chunk) or `hold`.

use std::time::{Duration, Instant};

use tokio::io::{AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::case::{Chunk, Response, Then};
use crate::case::check::decode_hex;
use crate::http;

/// How long `then: reset` waits after the last chunk before the RST.
const RESET_GRACE: Duration = Duration::from_millis(100);

/// How a replay ended, for the verdict.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Replayed {
    Done,
    /// A `hold`: whether the client disconnected within the window.
    Held { in_time: bool },
}

/// The status line and headers, with D10's `content-type` defaults, a
/// `content-length` on every body that is not a stream, and
/// `transfer-encoding: chunked` on every stream.
///
/// A stream is chunked because the Backend's are (hyper frames a streaming
/// HTTP/1.1 body that way), and because only then is `reset` observable on
/// every runtime: a close-delimited body has no end marker, and Node's
/// fetch deliberately reads a reset there as a clean end of message.
pub fn head_for(response: &Response) -> Vec<u8> {
    let mut headers: Vec<(String, String)> = response
        .headers
        .iter()
        .flatten()
        .map(|(k, v)| (k.clone(), v.as_str().map_or_else(|| v.to_string(), str::to_string)))
        .collect();
    let has = |headers: &[(String, String)], name: &str| headers.iter().any(|(k, _)| k.eq_ignore_ascii_case(name));
    let default_type = match (&response.json, &response.text, &response.sse) {
        (Some(_), _, _) => Some("application/json"),
        (_, Some(_), _) => Some("text/plain; charset=utf-8"),
        (_, _, Some(_)) => Some("text/event-stream"),
        _ => None,
    };
    if let Some(ct) = default_type.filter(|_| !has(&headers, "content-type")) {
        headers.push(("content-type".into(), ct.into()));
    }
    if response.sse.is_none() {
        headers.push(("content-length".into(), body_for(response).len().to_string()));
    } else {
        headers.push(("transfer-encoding".into(), "chunked".into()));
    }
    http::head(response.status, &headers)
}

/// A non-stream body: the JSON, the text, or nothing.
pub fn body_for(response: &Response) -> Vec<u8> {
    match (&response.json, &response.text) {
        (Some(json), _) => serde_json::to_vec(json).unwrap_or_default(),
        (_, Some(text)) => text.clone().into_bytes(),
        _ => Vec::new(),
    }
}

/// Writes each chunk as its own HTTP/1.1 chunk, one write and flush each;
/// `after_ms` pauses. An empty chunk is skipped, since a zero-length chunk
/// is the end marker only `close` may send.
pub async fn write_chunks<W: AsyncWrite + Unpin>(w: &mut W, chunks: &[Chunk]) -> std::io::Result<()> {
    for chunk in chunks {
        let bytes = match chunk {
            Chunk::Text(text) => text.as_bytes().to_vec(),
            Chunk::Hex(hex) => decode_hex(&hex.hex).map_err(std::io::Error::other)?,
            Chunk::Pause(p) => {
                tokio::time::sleep(Duration::from_millis(p.after_ms)).await;
                continue;
            }
        };
        if bytes.is_empty() {
            continue;
        }
        w.write_all(&frame(&bytes)).await?;
        w.flush().await?;
    }
    Ok(())
}

/// One HTTP/1.1 chunk: its size in hex, the bytes, CRLF.
pub fn frame(bytes: &[u8]) -> Vec<u8> {
    let mut out = format!("{:x}\r\n", bytes.len()).into_bytes();
    out.extend_from_slice(bytes);
    out.extend_from_slice(b"\r\n");
    out
}

/// The zero-length chunk that ends a chunked body cleanly.
pub const LAST_CHUNK: &[u8] = b"0\r\n\r\n";

/// Answers one matched request on its own connection.
pub async fn respond(mut stream: TcpStream, response: &Response) -> std::io::Result<Replayed> {
    if let Some(ms) = response.delay_ms {
        tokio::time::sleep(Duration::from_millis(ms)).await;
    }
    stream.write_all(&head_for(response)).await?;
    stream.flush().await?;
    let Some(sse) = &response.sse else {
        stream.write_all(&body_for(response)).await?;
        stream.shutdown().await?;
        return Ok(Replayed::Done);
    };
    write_chunks(&mut stream, &sse.chunks).await?;
    match sse.then {
        Then::Close => {
            stream.write_all(LAST_CHUNK).await?;
            stream.flush().await?;
            stream.shutdown().await?;
            Ok(Replayed::Done)
        }
        Then::Reset => {
            reset(stream).await;
            Ok(Replayed::Done)
        }
        Then::Hold => {
            let window = Duration::from_millis(sse.disconnect_within_ms.unwrap_or(0));
            Ok(Replayed::Held { in_time: hold(&mut stream, window).await })
        }
    }
}

/// Linger zero, then drop: the kernel sends RST instead of FIN. It waits
/// `RESET_GRACE` first, because some kernels (macOS) discard a peer's
/// unread bytes on RST, and a reset case is about what happens *after*
/// the scripted bytes were delivered.
async fn reset(stream: TcpStream) {
    tokio::time::sleep(RESET_GRACE).await;
    let _ = stream.set_zero_linger();
    drop(stream);
}

/// Waits for the client to close, up to `window` after the last chunk.
/// Anything the client sends meanwhile is read and dropped.
async fn hold(stream: &mut TcpStream, window: Duration) -> bool {
    let deadline = Instant::now() + window;
    let mut sink = [0u8; 1024];
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        match tokio::time::timeout(left, stream.read(&mut sink)).await {
            Ok(Ok(0)) | Ok(Err(_)) => return true,
            Ok(Ok(_)) => continue,
            Err(_) => return false,
        }
    }
}
