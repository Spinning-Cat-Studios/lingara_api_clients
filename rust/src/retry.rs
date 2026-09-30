//! The two retries (CONTRACT.md K1 and K4; ADR 29.9.26p D4): the
//! `Retry-After` loop around one HTTP request, and the one 401 retry around a
//! `/v1` call.
//!
//! Each HTTP request has its own budget of `max_attempts`. The token exchange
//! is one request and the `/v1` request another, and the 401 retry sends the
//! `/v1` request again with a fresh budget.

use std::future::Future;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use reqwest::StatusCode;
use reqwest::header::{HeaderMap, RETRY_AFTER};

use crate::error::Error;
use crate::seams::{Clock, Sleeper};
use crate::token::{AccessToken, TokenSource};

/// K4's knobs and the two seams they are read and slept against.
#[derive(Clone)]
pub(crate) struct RetryPolicy {
    /// Tries per HTTP request, the first included. `1` turns retries off.
    pub max_attempts: u32,
    /// A `Retry-After` above this is raised, never slept.
    pub retry_after_cap: Duration,
    pub clock: Arc<dyn Clock>,
    pub sleeper: Arc<dyn Sleeper>,
}

/// A `Retry-After` value: delta-seconds, or an HTTP-date read against `now`
/// (`max(0, date − now)`, rounded up to a whole second). Absent or
/// unreadable is `None`.
pub(crate) fn parse_retry_after(headers: &HeaderMap, now: SystemTime) -> Option<Duration> {
    let value = headers.get(RETRY_AFTER)?.to_str().ok()?.trim();
    if !value.is_empty() && value.bytes().all(|b| b.is_ascii_digit()) {
        return value.parse().ok().map(Duration::from_secs);
    }
    let at = httpdate::parse_http_date(value).ok()?;
    let wait = at.duration_since(now).unwrap_or(Duration::ZERO);
    let whole = wait.as_secs() + u64::from(wait.subsec_nanos() > 0);
    Some(Duration::from_secs(whole))
}

/// How long to wait before trying again, or `None` to hand the response back.
/// Decided on the status line and headers alone.
fn retry_wait(status: StatusCode, headers: &HeaderMap, policy: &RetryPolicy, tries: u32) -> Option<Duration> {
    if status != StatusCode::TOO_MANY_REQUESTS && status != StatusCode::SERVICE_UNAVAILABLE {
        return None;
    }
    if tries >= policy.max_attempts {
        return None;
    }
    let wait = parse_retry_after(headers, policy.clock.now())?;
    (wait <= policy.retry_after_cap).then_some(wait)
}

/// Sends `attempt` until it answers something other than a retryable 429 or
/// 503, or the attempts run out, and returns the last response. A retried
/// response is dropped unread. A transport error is never retried.
pub(crate) async fn with_retries<F, Fut>(policy: &RetryPolicy, mut attempt: F) -> Result<reqwest::Response, Error>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Result<reqwest::Response, Error>>,
{
    let mut tries = 1;
    loop {
        let res = attempt().await?;
        let Some(wait) = retry_wait(res.status(), res.headers(), policy, tries) else {
            return Ok(res);
        };
        drop(res);
        policy.sleeper.sleep(wait).await;
        tries += 1;
    }
}

/// K1's one 401 retry: send with a token; on a 401, forget that token (only
/// if it is still the cached one), get another and send once more.
pub(crate) async fn with_token_retry<F, Fut>(tokens: &dyn TokenSource, mut send: F) -> Result<reqwest::Response, Error>
where
    F: FnMut(AccessToken) -> Fut,
    Fut: Future<Output = Result<reqwest::Response, Error>>,
{
    let first = tokens.token().await?;
    let res = send(first.clone()).await?;
    if res.status() != StatusCode::UNAUTHORIZED {
        return Ok(res);
    }
    drop(res);
    tokens.invalidate(&first);
    send(tokens.token().await?).await
}

#[cfg(test)]
#[path = "tests/retry_tests.rs"]
mod tests;
