//! The webhook verifier (CONTRACT.md appendix W; ADR 30.9.26aa D4):
//! Standard Webhooks, keyed on `lgr_whsec_`, implemented natively with
//! RustCrypto's `hmac` and `sha2`. `Mac::verify_slice` is the constant-time
//! compare.

use std::collections::HashMap;
use std::fmt;
use std::sync::Arc;
use std::time::{Duration, UNIX_EPOCH};

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, Mac};
use sha2::Sha256;

use super::{Event, parse_event};
use crate::builder::BuildError;
use crate::seams::{Clock, SystemClock};

const PREFIX: &str = "lgr_whsec_";
/// The Standard Webhooks test vector's key length; a Lingara key is 32.
const MIN_KEY_BYTES: usize = 24;
/// The specification's, and not configurable: a wider window only widens
/// replay.
const TOLERANCE: Duration = Duration::from_secs(300);

/// Why a delivery was refused. Not an [`Error`](crate::Error) variant: no
/// Lingara server answered anything, and a catch-all around API calls must
/// not also swallow a forged webhook. Its message never holds a secret, a
/// signature or the body.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
#[non_exhaustive]
pub enum VerifyError {
    #[error("a webhook header is missing")]
    MissingHeader,
    #[error("webhook-timestamp is not a whole number of seconds")]
    MalformedHeader,
    #[error("webhook-timestamp is more than 300 s old")]
    TimestampTooOld,
    #[error("webhook-timestamp is more than 300 s ahead")]
    TimestampTooNew,
    #[error("no webhook signature matches")]
    NoMatchingSignature,
    #[error("the signed body is not an event envelope")]
    MalformedPayload,
}

impl VerifyError {
    /// The contract's snake_case reason.
    pub fn reason(self) -> &'static str {
        match self {
            VerifyError::MissingHeader => "missing_header",
            VerifyError::MalformedHeader => "malformed_header",
            VerifyError::TimestampTooOld => "timestamp_too_old",
            VerifyError::TimestampTooNew => "timestamp_too_new",
            VerifyError::NoMatchingSignature => "no_matching_signature",
            VerifyError::MalformedPayload => "malformed_payload",
        }
    }
}

/// A request's headers, looked up case-insensitively by a lower-case name.
pub trait WebhookHeaders {
    fn header(&self, name: &str) -> Option<&str>;
}

/// axum's and hyper's header map (the `http` 1.x type reqwest re-exports).
impl WebhookHeaders for reqwest::header::HeaderMap {
    fn header(&self, name: &str) -> Option<&str> {
        self.get(name).and_then(|v| v.to_str().ok())
    }
}

/// Any framework's headers, collected by name.
impl WebhookHeaders for HashMap<String, String> {
    fn header(&self, name: &str) -> Option<&str> {
        self.iter().find(|(k, _)| k.eq_ignore_ascii_case(name)).map(|(_, v)| v.as_str())
    }
}

/// Verifies Lingara's webhook deliveries. Hold one per process; it is cheap
/// to clone and stores nothing between calls.
#[derive(Clone)]
pub struct Webhook {
    keys: Arc<[Vec<u8>]>,
    clock: Arc<dyn Clock>,
}

impl Webhook {
    /// One `lgr_whsec_…` secret.
    pub fn new(secret: &str) -> Result<Self, BuildError> {
        Self::with_secrets([secret])
    }

    /// Every live secret: two during a rotation, so either one verifies.
    pub fn with_secrets<S: AsRef<str>>(secrets: impl IntoIterator<Item = S>) -> Result<Self, BuildError> {
        let keys: Vec<Vec<u8>> = secrets.into_iter().map(|s| key(s.as_ref())).collect::<Result<_, _>>()?;
        if keys.is_empty() {
            return Err(BuildError::InvalidWebhookSecret);
        }
        Ok(Self { keys: keys.into(), clock: Arc::new(SystemClock) })
    }

    /// A testing seam: the clock the 300 s tolerance is read against.
    #[must_use]
    pub fn clock(mut self, clock: Arc<dyn Clock>) -> Self {
        self.clock = clock;
        self
    }

    /// Checks the signature over the **raw** body, then parses it. Pass the
    /// bytes exactly as received, before any JSON parsing.
    pub fn verify(&self, body: &[u8], headers: &impl WebhookHeaders) -> Result<Event, VerifyError> {
        self.verify_signature(body, headers)?;
        let event = parse_event(body).map_err(|_| VerifyError::MalformedPayload)?;
        if Some(event.id()) != headers.header("webhook-id") {
            return Err(VerifyError::MalformedPayload);
        }
        Ok(event)
    }

    /// The signature check alone, for a signed body that is not an event
    /// envelope (an app-kit request; ADR 30.9.26aa D4's amendment).
    pub fn verify_signature(&self, body: &[u8], headers: &impl WebhookHeaders) -> Result<(), VerifyError> {
        let read = |name: &str| headers.header(name).ok_or(VerifyError::MissingHeader);
        let (id, timestamp, signatures) = (read("webhook-id")?, read("webhook-timestamp")?, read("webhook-signature")?);
        self.check_timestamp(timestamp)?;
        let macs: Vec<Hmac<Sha256>> = self.keys.iter().filter_map(|k| keyed(k, id, timestamp, body)).collect();
        for tag in signatures.split(' ').filter_map(|s| s.strip_prefix("v1,")).filter_map(|s| STANDARD.decode(s).ok()) {
            if macs.iter().any(|mac| mac.clone().verify_slice(&tag).is_ok()) {
                return Ok(());
            }
        }
        Err(VerifyError::NoMatchingSignature)
    }

    fn check_timestamp(&self, timestamp: &str) -> Result<(), VerifyError> {
        if timestamp.is_empty() || !timestamp.bytes().all(|b| b.is_ascii_digit()) {
            return Err(VerifyError::MalformedHeader);
        }
        // All digits, so only an overflow fails to parse: far in the future.
        let at = timestamp.parse::<u64>().map_err(|_| VerifyError::TimestampTooNew)?;
        let now = self.clock.now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs();
        if at.saturating_add(TOLERANCE.as_secs()) < now {
            return Err(VerifyError::TimestampTooOld);
        }
        if at > now.saturating_add(TOLERANCE.as_secs()) {
            return Err(VerifyError::TimestampTooNew);
        }
        Ok(())
    }
}

impl fmt::Debug for Webhook {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Webhook").field("secrets", &format_args!("[REDACTED; {}]", self.keys.len())).finish()
    }
}

/// The HMAC key: the base64 after `lgr_whsec_`. The text is matched against
/// `^[A-Za-z0-9+/]+={0,2}$`, length a multiple of 4, before it is decoded,
/// since decoders differ in leniency.
fn key(secret: &str) -> Result<Vec<u8>, BuildError> {
    let refused = || BuildError::InvalidWebhookSecret;
    let text = secret.strip_prefix(PREFIX).ok_or_else(refused)?;
    let body = text.trim_end_matches('=');
    let alphabet = |b: u8| b.is_ascii_alphanumeric() || b == b'+' || b == b'/';
    if body.is_empty() || text.len() - body.len() > 2 || text.len() % 4 != 0 || !body.bytes().all(alphabet) {
        return Err(refused());
    }
    let key = STANDARD.decode(text).map_err(|_| refused())?;
    if key.len() < MIN_KEY_BYTES {
        return Err(refused());
    }
    Ok(key)
}

/// `id.timestamp.body` under one key, ready to compare. HMAC takes a key of
/// any length, so this is never `None` in practice.
fn keyed(key: &[u8], id: &str, timestamp: &str, body: &[u8]) -> Option<Hmac<Sha256>> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).ok()?;
    for part in [id.as_bytes(), b".", timestamp.as_bytes(), b".", body] {
        mac.update(part);
    }
    Some(mac)
}

#[cfg(test)]
#[path = "../tests/webhook_tests.rs"]
mod tests;
