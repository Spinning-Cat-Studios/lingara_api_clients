//! One error family (CONTRACT.md K3; ADR 29.9.26p D6): `Error` and its four
//! variants, the mapping from a refused response to one of them, and the
//! mapping from a failed `reqwest` call to a transport kind.
//!
//! `Error` is `Clone` because the token exchange is shared: every waiter on a
//! failed flight gets the same error. So `TransportError`'s source is an
//! `Arc`, not a `Box`.

use std::error::Error as StdError;
use std::fmt;
use std::sync::Arc;
use std::time::Duration;

use reqwest::header::{CONTENT_TYPE, HeaderMap};

/// Every error a call can end in. One `match` handles all four.
#[derive(Debug, Clone, thiserror::Error)]
#[non_exhaustive]
pub enum Error {
    #[error(transparent)]
    Api(#[from] ApiError),
    #[error(transparent)]
    OAuth(#[from] OAuthError),
    #[error(transparent)]
    Maintenance(#[from] MaintenanceError),
    #[error(transparent)]
    Transport(#[from] TransportError),
}

impl Error {
    /// The `Retry-After` any variant carries, so a caller can schedule the
    /// call the library declined to wait for.
    pub fn retry_after(&self) -> Option<Duration> {
        match self {
            Error::Api(e) => e.retry_after,
            Error::OAuth(e) => e.retry_after,
            Error::Maintenance(e) => e.retry_after,
            Error::Transport(_) => None,
        }
    }
}

/// A `/v1` refusal, or a stream's `error` event (then `status` is 200).
#[derive(Debug, Clone, thiserror::Error)]
#[error("{code}: {message} (HTTP {status})")]
#[non_exhaustive]
pub struct ApiError {
    pub status: u16,
    /// The envelope's `code`, or `http_<status>` when the body is not one.
    pub code: String,
    pub message: String,
    pub retry_after: Option<Duration>,
    /// Only from an `error` event that carried one.
    pub plan_id: Option<String>,
    pub served_version: Option<String>,
}

/// A token-endpoint refusal: RFC 6749 §5.2, or `http_<status>`.
#[derive(Debug, Clone, thiserror::Error)]
#[error("{error}{} (HTTP {status})", description.as_deref().map(|d| format!(": {d}")).unwrap_or_default())]
#[non_exhaustive]
pub struct OAuthError {
    pub status: u16,
    pub error: String,
    /// `error_description`.
    pub description: Option<String>,
    pub retry_after: Option<Duration>,
}

/// A `503` whose body is not JSON: the service is in maintenance.
#[derive(Debug, Clone, thiserror::Error)]
#[error("the Lingara API is under maintenance")]
#[non_exhaustive]
pub struct MaintenanceError {
    /// The response text, at most 1 KiB, cut on a character boundary.
    pub body: String,
    pub retry_after: Option<Duration>,
}

/// No usable HTTP answer.
#[derive(Debug, Clone, thiserror::Error)]
#[error("transport failure: {kind}")]
#[non_exhaustive]
pub struct TransportError {
    pub kind: TransportKind,
    /// The underlying failure (the contract's `cause`), when there is one.
    pub source: Option<Arc<dyn StdError + Send + Sync>>,
}

impl TransportError {
    pub(crate) fn new(kind: TransportKind) -> Self {
        Self { kind, source: None }
    }

    pub(crate) fn caused_by(kind: TransportKind, source: impl StdError + Send + Sync + 'static) -> Self {
        Self { kind, source: Some(Arc::new(source)) }
    }
}

/// C2 D4's seven transport kinds.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum TransportKind {
    Connect,
    Tls,
    Reset,
    /// The stream idle timeout, or a token exchange past its timeout.
    Timeout,
    StreamEndedEarly,
    MalformedResponse,
    MalformedEvent,
}

impl TransportKind {
    /// The contract's snake_case name.
    pub fn as_str(self) -> &'static str {
        match self {
            TransportKind::Connect => "connect",
            TransportKind::Tls => "tls",
            TransportKind::Reset => "reset",
            TransportKind::Timeout => "timeout",
            TransportKind::StreamEndedEarly => "stream_ended_early",
            TransportKind::MalformedResponse => "malformed_response",
            TransportKind::MalformedEvent => "malformed_event",
        }
    }
}

impl fmt::Display for TransportKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl From<TransportKind> for Error {
    fn from(kind: TransportKind) -> Self {
        Error::Transport(TransportError::new(kind))
    }
}

// ── Response → error ─────────────────────────────────────────────────────

const MAINTENANCE_BODY_BYTES: usize = 1024;

/// Which endpoint refused: the two map their bodies differently.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Endpoint {
    V1,
    Token,
}

/// What a refusal is mapped from, the body aside.
#[derive(Debug, Clone)]
pub(crate) struct Refusal {
    pub endpoint: Endpoint,
    pub status: u16,
    pub json: bool,
    pub retry_after: Option<Duration>,
    pub served_version: Option<String>,
}

/// Maps a non-2xx response to its K3 variant. Consumes the body.
pub(crate) async fn from_response(res: reqwest::Response, refusal: Refusal) -> Error {
    let text = res.text().await.unwrap_or_default();
    from_refusal(refusal, &text)
}

/// The mapping itself, in C2 D4's precedence: a non-JSON 503 from either
/// endpoint is maintenance first; then the endpoint's own body shape.
pub(crate) fn from_refusal(r: Refusal, text: &str) -> Error {
    if r.status == 503 && !r.json {
        let body = truncate_utf8(text, MAINTENANCE_BODY_BYTES).to_owned();
        return MaintenanceError { body, retry_after: r.retry_after }.into();
    }
    let body: Option<serde_json::Map<String, serde_json::Value>> = serde_json::from_str(text).ok();
    let field = |key: &str| body.as_ref().and_then(|b| b.get(key)).and_then(|v| v.as_str()).map(str::to_owned);
    match r.endpoint {
        Endpoint::V1 => {
            let (code, message) = match (field("code"), field("error")) {
                (Some(code), Some(message)) => (code, message),
                _ => (format!("http_{}", r.status), format!("HTTP {}", r.status)),
            };
            let served_version = r.served_version;
            ApiError { status: r.status, code, message, retry_after: r.retry_after, plan_id: None, served_version }.into()
        }
        Endpoint::Token => {
            let (error, description) = match field("error") {
                Some(error) => (error, field("error_description")),
                None => (format!("http_{}", r.status), None),
            };
            OAuthError { status: r.status, error, description, retry_after: r.retry_after }.into()
        }
    }
}

/// The `Content-Type` media type, parameters dropped, lower-cased.
pub(crate) fn media_type(headers: &HeaderMap) -> String {
    let value = headers.get(CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or_default();
    value.split(';').next().unwrap_or_default().trim().to_ascii_lowercase()
}

pub(crate) fn is_json(headers: &HeaderMap) -> bool {
    let media = media_type(headers);
    media == "application/json" || media.ends_with("+json")
}

fn truncate_utf8(text: &str, max: usize) -> &str {
    if text.len() <= max {
        return text;
    }
    let end = (0..=max).rev().find(|&i| text.is_char_boundary(i)).unwrap_or(0);
    &text[..end]
}

// ── reqwest failure → transport kind ─────────────────────────────────────

/// Whether the response headers had arrived when the failure happened.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Phase {
    Send,
    Body,
}

/// `tls` when a TLS error is anywhere in the chain; otherwise `connect`
/// before the response headers and `reset` after them. reqwest's own
/// connect timeout is `connect` too: `timeout` is reserved for the
/// library's two timers.
pub(crate) fn from_reqwest(err: reqwest::Error, phase: Phase) -> Error {
    let kind = match phase {
        _ if tls_in_chain(&err) => TransportKind::Tls,
        Phase::Send => TransportKind::Connect,
        Phase::Body => TransportKind::Reset,
    };
    TransportError::caused_by(kind, err).into()
}

/// hyper-util wraps a TLS failure in `std::io::Error`, sometimes twice, and
/// an `io::Error`'s `source()` skips the error it wraps. So the walk steps
/// into each `io::Error`'s wrapped error instead of past it.
fn tls_in_chain(err: &(dyn StdError + 'static)) -> bool {
    let mut next = Some(err);
    while let Some(e) = next {
        if is_tls_error(e) {
            return true;
        }
        next = match e.downcast_ref::<std::io::Error>().and_then(|io| io.get_ref()) {
            Some(wrapped) => Some(wrapped as &(dyn StdError + 'static)),
            None => e.source(),
        };
    }
    false
}

#[allow(unused_variables)] // with neither TLS feature `lib.rs` refuses to compile anyway
fn is_tls_error(e: &(dyn StdError + 'static)) -> bool {
    #[cfg(feature = "rustls")]
    if e.is::<rustls::Error>() {
        return true;
    }
    #[cfg(feature = "native-tls")]
    if e.is::<native_tls::Error>() {
        return true;
    }
    false
}

#[cfg(test)]
#[path = "tests/error_tests.rs"]
mod tests;
