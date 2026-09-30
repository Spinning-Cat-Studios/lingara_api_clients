//! The two testing seams (CONTRACT.md, Test seams; ADR 29.9.26p D7). Both
//! default to the real ones. The conformance harness injects a virtual clock
//! that moves only when a case says so, and a sleeper that records each
//! duration and returns at once.

use std::time::{Duration, SystemTime};

use crate::BoxFuture;

/// Wall-clock time: a token's stale point and an HTTP-date `Retry-After` are
/// both read against it. A testing seam.
pub trait Clock: Send + Sync + 'static {
    fn now(&self) -> SystemTime;
}

/// Waits out a `Retry-After`. Dropping the returned future is the
/// cancellation, so there is no second argument. A testing seam.
pub trait Sleeper: Send + Sync + 'static {
    fn sleep(&self, duration: Duration) -> BoxFuture<'static, ()>;
}

/// `SystemTime::now`.
#[derive(Clone, Copy, Debug, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> SystemTime {
        SystemTime::now()
    }
}

/// `tokio::time::sleep`.
#[derive(Clone, Copy, Debug, Default)]
pub struct TokioSleeper;

impl Sleeper for TokioSleeper {
    fn sleep(&self, duration: Duration) -> BoxFuture<'static, ()> {
        Box::pin(tokio::time::sleep(duration))
    }
}
