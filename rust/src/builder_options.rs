//! `ClientBuilder`'s timing, retry and seam options (ADR 29.9.26p D4). The
//! identity and endpoint options, and `build`, are in `builder.rs`.

use std::sync::Arc;
use std::time::Duration;

use crate::builder::ClientBuilder;
use crate::seams::{Clock, Sleeper};

impl ClientBuilder {
    /// Tries per HTTP request, the first included. `1` turns retries off.
    pub fn max_attempts(mut self, attempts: u32) -> Self {
        self.policy.max_attempts = attempts.max(1);
        self
    }

    /// A `Retry-After` above this is raised, not slept. Default 60 s.
    pub fn retry_after_cap(mut self, cap: Duration) -> Self {
        self.policy.retry_after_cap = cap;
        self
    }

    /// How long a stream may go without a byte while a read is pending.
    /// Default 120 s. Enforced by the stream itself, so it holds on a
    /// caller's own `http_client` too.
    pub fn stream_idle_timeout(mut self, timeout: Duration) -> Self {
        self.stream_idle_timeout = timeout;
        self
    }

    /// How many consecutive failed opens `tail_events` rides out before it
    /// raises the last one (CONTRACT.md K5a). Default 8: 91 s of sleeps.
    pub fn tail_max_failures(mut self, failures: u32) -> Self {
        self.tail_max_failures = failures.max(1);
        self
    }

    /// Bounds each token exchange attempt. Default 30 s.
    pub fn token_request_timeout(mut self, timeout: Duration) -> Self {
        self.token_request_timeout = timeout;
        self
    }

    /// Your own product token, appended to the `User-Agent` after one space.
    pub fn user_agent_suffix(mut self, suffix: impl Into<String>) -> Self {
        self.user_agent_suffix = Some(suffix.into());
        self
    }

    /// A testing seam: the clock refresh timing and `Retry-After` dates are
    /// read against.
    pub fn clock(mut self, clock: Arc<dyn Clock>) -> Self {
        self.policy.clock = clock;
        self
    }

    /// A testing seam: what waits out a `Retry-After`.
    pub fn sleeper(mut self, sleeper: Arc<dyn Sleeper>) -> Self {
        self.policy.sleeper = sleeper;
        self
    }

    /// Your own reqwest client, for proxies and pools. A total `timeout` on
    /// it cuts every long stream, because reqwest applies it to the body.
    pub fn http_client(mut self, client: reqwest::Client) -> Self {
        self.http_client = Some(client);
        self
    }
}
