//! The tail helper (CONTRACT.md K5a; ADR 30.9.26aa D7): `streamEvents`,
//! reopened after every ending from the last `id:` it saw.
//!
//! The state lives in `Tail`, which the in-flight future owns and hands
//! back with each item, so dropping the `EventTail` drops the connection or
//! the reconnect sleep with it, and nothing further is sent.

use std::fmt;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use futures_core::Stream;
use reqwest::Method;
use serde::Deserialize;

use super::{Event, EventsOptions, query, with_query};
use crate::BoxFuture;
use crate::client::Client;
use crate::error::{ApiError, Error, MaintenanceError, TransportKind};
use crate::generated::streams::STREAM_EVENTS;
use crate::pipeline::Req;
use crate::stream::EventStream;

/// The longest backoff step.
const MAX_DELAY: Duration = Duration::from_secs(30);

// The view marks the stream a tail; a view that stops doing so fails the
// build here rather than shipping a helper that reconnects a K5 stream.
const _: () = assert!(STREAM_EVENTS.resumable, "streamEvents is not resumable in the view");

/// One read of the tail: the state it hands back, and the item.
type Turn = BoxFuture<'static, (Tail, Option<Result<Event, Error>>)>;

/// The one frame a tail yields. `done` and `error` never reach it: K5's rule
/// ends the connection on the first and raises the second.
#[derive(Deserialize)]
#[serde(tag = "event", content = "data")]
enum TailFrame {
    #[serde(rename = "event")]
    Event(Event),
}

/// Every event from a starting point, live: `Stream<Item = Result<Event,
/// Error>>`. It reconnects after every ending (`done` at once, anything else
/// after a backoff) and ends only when dropped, or by raising the last of
/// `tail_max_failures` consecutive failed opens. `cursor()` is the `id:` of
/// the last event or `done` it read, the same token as a feed cursor.
pub struct EventTail {
    tail: Option<Tail>,
    pending: Option<Turn>,
    cursor: Option<String>,
}

struct Tail {
    client: Client,
    /// The first request's path and query, repeated by every reopen.
    path: String,
    cursor: Option<String>,
    conn: Option<EventStream<TailFrame>>,
    failures: u32,
    finished: bool,
}

/// What K5a does with a failed open or connection.
enum Failure {
    /// One failed reopen, with a `Retry-After` that replaces its delay.
    Retry(Error, Option<Duration>),
    Raise(Error),
}

impl EventTail {
    pub(crate) fn new(client: Client, options: EventsOptions) -> Self {
        let start = if options.cursor.is_some() { None } else { options.start };
        let path = with_query(STREAM_EVENTS.path, &query(None, start, &options.types, None));
        let cursor = options.cursor;
        let tail = Tail { client, path, cursor: cursor.clone(), conn: None, failures: 0, finished: false };
        Self { tail: Some(tail), pending: None, cursor }
    }

    /// Where to resume from: the caller's cursor until the first event or
    /// `done` carries an `id:`.
    pub fn cursor(&self) -> Option<&str> {
        self.cursor.as_deref()
    }

    /// The next event, `None` after a raised error. The same as
    /// `StreamExt::next`, without importing it.
    pub async fn next(&mut self) -> Option<Result<Event, Error>> {
        std::future::poll_fn(|cx| self.poll_event(cx)).await
    }

    fn poll_event(&mut self, cx: &mut Context<'_>) -> Poll<Option<Result<Event, Error>>> {
        if self.pending.is_none() {
            let Some(tail) = self.tail.take() else { return Poll::Ready(None) };
            self.pending = Some(Box::pin(tail.next_event()));
        }
        let Some(pending) = self.pending.as_mut() else { return Poll::Ready(None) };
        let (tail, item) = std::task::ready!(pending.as_mut().poll(cx));
        self.pending = None;
        self.cursor.clone_from(&tail.cursor);
        self.tail = Some(tail);
        Poll::Ready(item)
    }
}

impl Tail {
    async fn next_event(mut self) -> (Self, Option<Result<Event, Error>>) {
        if self.finished {
            return (self, None);
        }
        let item = self.read().await;
        self.finished = item.is_err();
        (self, Some(item))
    }

    /// Reads until an event, reopening as K5a says, or until it must raise.
    async fn read(&mut self) -> Result<Event, Error> {
        loop {
            let conn = match self.conn.as_mut() {
                Some(conn) => conn,
                None => match open(&self.client, &self.path, self.cursor.as_deref()).await {
                    Ok(conn) => self.conn.insert(conn),
                    Err(err) => {
                        self.fail(err).await?;
                        continue;
                    }
                },
            };
            let item = conn.next().await;
            if let Some(id) = conn.last_event_id() {
                self.cursor = Some(id.to_owned());
            }
            match item {
                Some(Ok(TailFrame::Event(event))) => {
                    self.failures = 0;
                    return Ok(event);
                }
                // `done`: not a failure, and the reopen is immediate.
                None => {
                    self.failures = 0;
                    self.conn = None;
                }
                Some(Err(err)) => {
                    self.conn = None;
                    self.fail(err).await?;
                }
            }
        }
    }

    /// Counts one failure and sleeps its delay, or hands back what to raise:
    /// a non-retryable error at once, a retryable one when the bound is
    /// spent.
    async fn fail(&mut self, err: Error) -> Result<(), Error> {
        let inner = &self.client.inner;
        let (err, retry_after) = match classify(err, inner.policy.retry_after_cap) {
            Failure::Raise(err) => return Err(err),
            Failure::Retry(err, retry_after) => (err, retry_after),
        };
        self.failures += 1;
        if self.failures >= inner.tail_max_failures {
            return Err(err);
        }
        let delay = retry_after.unwrap_or_else(|| backoff(self.failures));
        inner.policy.sleeper.sleep(delay).await;
        Ok(())
    }
}

/// One open, outside K4's attempt loop: K1's one 401 refresh still applies.
/// `Last-Event-ID` once a cursor is known. A free function, so the open does
/// not hold the `Tail` (and its `!Sync` connection slot) across an await.
async fn open(client: &Client, path: &str, cursor: Option<&str>) -> Result<EventStream<TailFrame>, Error> {
    let url = client.url(path);
    let headers: Vec<_> = cursor.map(|c| ("last-event-id", c.to_owned())).into_iter().collect();
    let req = Req { method: Method::GET, url: &url, body: None::<&()>, accept: "text/event-stream", needs_token: true, headers: &headers, retries: false };
    client.open_stream(&STREAM_EVENTS, &req).await
}

/// 1 s after the first failure, doubling up to 30 s.
fn backoff(failures: u32) -> Duration {
    let doubled = Duration::from_secs(1).checked_mul(1 << failures.saturating_sub(1).min(16));
    doubled.unwrap_or(MAX_DELAY).min(MAX_DELAY)
}

/// K5a's reopen answers: transport failures, an `error` event (an
/// `ApiError` with status 200) and a 429 or 503 within the cap are retried;
/// a known type that does not decode, a `Retry-After` above the cap and
/// every other refusal are raised.
fn classify(err: Error, cap: Duration) -> Failure {
    let retry_after = match &err {
        Error::Transport(e) if e.kind == TransportKind::MalformedEvent => return Failure::Raise(err),
        Error::Transport(_) | Error::Api(ApiError { status: 200, .. }) => return Failure::Retry(err, None),
        Error::Api(ApiError { status: 429 | 503, retry_after, .. }) | Error::Maintenance(MaintenanceError { retry_after, .. }) => *retry_after,
        _ => return Failure::Raise(err),
    };
    match retry_after {
        Some(wait) if wait > cap => Failure::Raise(err),
        wait => Failure::Retry(err, wait),
    }
}

impl Stream for EventTail {
    type Item = Result<Event, Error>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.get_mut().poll_event(cx)
    }
}

impl fmt::Debug for EventTail {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("EventTail").field("cursor", &self.cursor).finish()
    }
}

#[cfg(test)]
#[path = "../tests/tail_tests.rs"]
mod tests;
