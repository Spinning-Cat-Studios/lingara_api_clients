//! K5: one stream of events (CONTRACT.md K5; ADR 29.9.26p D4, D7).
//!
//! Frames come from the pure parser in `sse.rs`; this file owns the bytes,
//! the idle timeout, the terminal events and dropping the body on every exit
//! path. The loop is generic over any `Stream` of `Bytes`, so the unit tests
//! drive it with an in-memory body.

use std::collections::VecDeque;
use std::fmt;
use std::marker::PhantomData;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use bytes::Bytes;
use futures_core::Stream;
use serde::de::DeserializeOwned;
use serde_json::{Value, json};
use tokio::time::Sleep;

use crate::error::{ApiError, Error, TransportKind};
use crate::sse::{Frame, SseParser};

/// A stream operation's route, generated from `x-lingara-streams`.
#[derive(Debug, Clone, Copy)]
pub(crate) struct StreamRoute {
    pub operation_id: &'static str,
    pub method: &'static str,
    pub path: &'static str,
    pub request_body: Option<&'static str>,
    pub path_params: &'static [&'static str],
    pub events: &'static [&'static str],
    /// Each event that ends the stream and what it does, generated from the
    /// view's `endsOn` (ADR 29.9.26ai D2).
    pub ends: &'static [(&'static str, Outcome)],
}

/// What an ending event does to iteration (CONTRACT.md K5's rule).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Outcome {
    /// Yielded, then the stream ends.
    Yield,
    /// A `Done` payload: the stream ends unyielded.
    End,
    /// The failure event: raised as `ApiError` with status 200.
    Raise,
}

pub(crate) type Body = Pin<Box<dyn Stream<Item = Result<Bytes, Error>> + Send>>;

/// A stream of events. `while let Some(event) = stream.next().await` reads
/// it; dropping it (or `close`) closes the connection.
///
/// Iteration ends after the operation's terminal event. An `error` event is
/// an `Err(Error::Api(..))` item and then the end; the event enum's `Error`
/// and `Done` variants are never yielded.
pub struct EventStream<E> {
    body: Option<Body>,
    parser: SseParser,
    frames: VecDeque<Frame>,
    events: &'static [&'static str],
    ends: &'static [(&'static str, Outcome)],
    idle: Duration,
    // Armed only while a read is pending; dropped on every chunk and before
    // an event is returned.
    timer: Option<Pin<Box<Sleep>>>,
    served_version: Option<String>,
    eof: bool,
    done: bool,
    _event: PhantomData<fn() -> E>,
}

/// What one frame means for the stream.
enum Step<E> {
    Skip,
    Yield(E),
    Last(E),
    End,
    Fail(Error),
}

impl<E: DeserializeOwned> EventStream<E> {
    pub(crate) fn new(route: &StreamRoute, body: Body, idle: Duration, served_version: Option<String>) -> Self {
        Self {
            body: Some(body),
            parser: SseParser::new(),
            frames: VecDeque::new(),
            events: route.events,
            ends: route.ends,
            idle,
            timer: None,
            served_version,
            eof: false,
            done: false,
            _event: PhantomData,
        }
    }

    /// The next event, `None` after the last. The same as
    /// `StreamExt::next`, without importing it.
    pub async fn next(&mut self) -> Option<Result<E, Error>> {
        std::future::poll_fn(|cx| self.poll_event(cx)).await
    }

    fn poll_event(&mut self, cx: &mut Context<'_>) -> Poll<Option<Result<E, Error>>> {
        loop {
            if self.done {
                return Poll::Ready(None);
            }
            if let Some(frame) = self.frames.pop_front() {
                match self.step(frame) {
                    Step::Skip => continue,
                    Step::Yield(event) => {
                        self.timer = None;
                        return Poll::Ready(Some(Ok(event)));
                    }
                    Step::Last(event) => return self.end(Some(Ok(event))),
                    Step::End => return self.end(None),
                    Step::Fail(err) => return self.end(Some(Err(err))),
                }
            }
            if self.eof {
                return self.end(Some(Err(TransportKind::StreamEndedEarly.into())));
            }
            if let Err(err) = std::task::ready!(self.poll_bytes(cx)) {
                return self.end(Some(Err(err)));
            }
        }
    }

    /// Reads one chunk into the parser, or notes the end of the body.
    fn poll_bytes(&mut self, cx: &mut Context<'_>) -> Poll<Result<(), Error>> {
        let Some(body) = self.body.as_mut() else {
            self.eof = true;
            return Poll::Ready(Ok(()));
        };
        match body.as_mut().poll_next(cx) {
            Poll::Ready(Some(Ok(bytes))) => {
                self.frames.extend(self.parser.push(&bytes));
                self.timer = None;
                Poll::Ready(Ok(()))
            }
            Poll::Ready(Some(Err(err))) => Poll::Ready(Err(err)),
            Poll::Ready(None) => {
                self.frames.extend(std::mem::take(&mut self.parser).finish());
                self.eof = true;
                Poll::Ready(Ok(()))
            }
            Poll::Pending => {
                let idle = self.idle;
                let timer = self.timer.get_or_insert_with(|| Box::pin(tokio::time::sleep(idle)));
                timer.as_mut().poll(cx).map(|()| Err(TransportKind::Timeout.into()))
            }
        }
    }

    fn step(&self, frame: Frame) -> Step<E> {
        if !self.events.contains(&frame.event.as_str()) {
            return Step::Skip;
        }
        let Ok(data) = serde_json::from_str::<Value>(&frame.data) else {
            return Step::Fail(TransportKind::MalformedEvent.into());
        };
        let outcome = self.ends.iter().find(|(e, _)| *e == frame.event).map(|(_, o)| *o);
        match outcome {
            Some(Outcome::Raise) => return Step::Fail(stream_error(&data, self.served_version.clone())),
            Some(Outcome::End) => return Step::End,
            Some(Outcome::Yield) | None => {}
        }
        match serde_json::from_value::<E>(json!({ "event": frame.event, "data": data })) {
            Ok(event) if outcome.is_some() => Step::Last(event),
            Ok(event) => Step::Yield(event),
            Err(_) => Step::Fail(TransportKind::MalformedEvent.into()),
        }
    }

    /// Drops the body, so no later byte is read, and returns `item`.
    fn end(&mut self, item: Option<Result<E, Error>>) -> Poll<Option<Result<E, Error>>> {
        self.done = true;
        self.body = None;
        self.timer = None;
        Poll::Ready(item)
    }
}

impl<E> EventStream<E> {
    /// The `Lingara-Version` the server answered under, if it said.
    pub fn served_version(&self) -> Option<&str> {
        self.served_version.as_deref()
    }

    /// Ends the stream and closes the connection: the same as dropping it.
    pub fn close(self) {}
}

impl<E: DeserializeOwned> Stream for EventStream<E> {
    type Item = Result<E, Error>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.get_mut().poll_event(cx)
    }
}

impl<E> fmt::Debug for EventStream<E> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("EventStream").field("served_version", &self.served_version).field("done", &self.done).finish()
    }
}

/// An `error` event: raised as `ApiError` with status 200, never yielded.
fn stream_error(data: &Value, served_version: Option<String>) -> Error {
    let text = |key: &str| data.get(key).and_then(Value::as_str).map(str::to_owned);
    ApiError {
        status: 200,
        code: text("code").unwrap_or_else(|| "stream_error".into()),
        message: text("message").unwrap_or_else(|| "the stream reported an error".into()),
        retry_after: None,
        plan_id: text("plan_id"),
        served_version,
    }
    .into()
}

#[cfg(test)]
#[path = "tests/stream_tests.rs"]
mod tests;
