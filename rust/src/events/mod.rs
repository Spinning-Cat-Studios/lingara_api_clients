//! Events (ADR 30.9.26aa; CONTRACT.md K5a, The event helpers, appendix W):
//! the generated `Event` union and its parser, the webhook verifier
//! (`webhook.rs`), the feed (`feed.rs`), the tail (`tail.rs`), and the four
//! operations behind them, here.
//!
//! - `list_events` and `stream_events` are the operations: one page, one
//!   connection. `events` and `tail_events` are the helpers built on them,
//!   each exposing `cursor()`.
//! - `send_event` sends an `InboundEvent` with an `Idempotency-Key`.

mod feed;
mod tail;
mod webhook;

use reqwest::Method;
use serde_json::Value;

use crate::client::{ApiResponse, Client};
use crate::error::{Error, TransportError, TransportKind};
use crate::generated::streams::STREAM_EVENTS;
use crate::models::{EventPage, InboundEventAccepted, StreamEventsEvent};
use crate::pipeline::Req;
use crate::stream::EventStream;

pub use crate::generated::events::*;
pub use feed::EventFeed;
pub use tail::EventTail;
pub use webhook::{VerifyError, Webhook, WebhookHeaders};

/// Where a feed or a tail begins when it has no cursor.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[non_exhaustive]
pub enum EventStart {
    /// From now on: the server's default.
    Latest,
    /// Every event still kept: about the last 30 days.
    Oldest,
}

impl EventStart {
    pub fn as_str(self) -> &'static str {
        match self {
            EventStart::Latest => "latest",
            EventStart::Oldest => "oldest",
        }
    }
}

/// What `events` and `tail_events` read: from `cursor` (a page's
/// `next_cursor` or a stream event's `id`, the same token), else from
/// `start`; only `types`, when any are named.
#[derive(Clone, Debug, Default)]
pub struct EventsOptions {
    pub cursor: Option<String>,
    pub start: Option<EventStart>,
    pub types: Vec<String>,
}

/// `listEvents`'s query, sent as given: `cursor` with `start` is the
/// server's `400`.
#[derive(Clone, Debug, Default)]
pub struct ListEventsParams {
    pub cursor: Option<String>,
    pub start: Option<EventStart>,
    pub types: Vec<String>,
    /// 1 to 100; the server's default is 50.
    pub limit: Option<u8>,
}

/// `streamEvents`'s parameters, sent as given. `last_event_id` takes
/// precedence over `cursor` and `start` at the server.
#[derive(Clone, Debug, Default)]
pub struct StreamEventsParams {
    pub last_event_id: Option<String>,
    pub cursor: Option<String>,
    pub start: Option<EventStart>,
    pub types: Vec<String>,
}

/// `send_event`'s options.
#[derive(Clone, Debug, Default)]
pub struct SendEventOptions {
    /// Sent unchanged. Without one, the library generates a UUIDv4 once per
    /// call and sends it on every retry; supply your own to resend safely
    /// after a crash, since a generated key is gone once the call returns.
    pub idempotency_key: Option<String>,
}

/// The query string, `types` comma-separated as one value (`explode: false`).
pub(crate) fn query(cursor: Option<&str>, start: Option<EventStart>, types: &[String], limit: Option<u8>) -> String {
    let mut q = form_urlencoded::Serializer::new(String::new());
    if let Some(cursor) = cursor {
        q.append_pair("cursor", cursor);
    }
    if let Some(start) = start {
        q.append_pair("start", start.as_str());
    }
    if !types.is_empty() {
        q.append_pair("types", &types.join(","));
    }
    if let Some(limit) = limit {
        q.append_pair("limit", &limit.to_string());
    }
    q.finish()
}

/// `path?query`, or `path` alone for an empty query.
pub(crate) fn with_query(path: &str, query: &str) -> String {
    if query.is_empty() { path.to_owned() } else { format!("{path}?{query}") }
}

impl Client {
    /// One page of events (`GET /v1/events`).
    pub async fn list_events(&self, params: &ListEventsParams) -> Result<ApiResponse<EventPage>, Error> {
        let query = query(params.cursor.as_deref(), params.start, &params.types, params.limit);
        self.json(&with_query("/v1/events", &query), true).await
    }

    /// Every event from `options`, page by page, to the end of the feed. It
    /// never sleeps and never polls: call it again later from `cursor()`.
    pub fn events(&self, options: EventsOptions) -> EventFeed {
        EventFeed::new(self.clone(), options)
    }

    /// One connection to `GET /v1/events/stream`, under K5: it ends on
    /// `done`, and an `error` event is raised. `tail_events` reconnects.
    pub async fn stream_events(&self, params: &StreamEventsParams) -> Result<EventStream<StreamEventsEvent>, Error> {
        let query = query(params.cursor.as_deref(), params.start, &params.types, None);
        let url = self.url(&with_query(STREAM_EVENTS.path, &query));
        let headers: Vec<_> = params.last_event_id.iter().map(|id| ("last-event-id", id.clone())).collect();
        let req = Req { method: Method::GET, url: &url, body: None::<&()>, accept: "text/event-stream", needs_token: true, headers: &headers, retries: true };
        self.open_stream(&STREAM_EVENTS, &req).await
    }

    /// Every event from `options`, live, reconnecting after every ending
    /// (CONTRACT.md K5a). It ends only by being dropped, or by raising after
    /// `tail_max_failures` consecutive failed opens.
    pub fn tail_events(&self, options: EventsOptions) -> EventTail {
        EventTail::new(self.clone(), options)
    }

    /// Records an event from your game (`POST /v1/events`). The same
    /// `Idempotency-Key` goes on every K4 retry, so a retry gets the first
    /// answer; a key reused for another event also gets the first answer.
    pub async fn send_event(&self, event: &InboundEvent, options: SendEventOptions) -> Result<ApiResponse<InboundEventAccepted>, Error> {
        let key = match options.idempotency_key {
            Some(key) => key,
            None => uuid_v4()?,
        };
        let url = self.url("/v1/events");
        let headers = [("idempotency-key", key)];
        let req = Req { method: Method::POST, url: &url, body: Some(event), accept: "application/json", needs_token: true, headers: &headers, retries: true };
        self.json_req(&req).await
    }

    /// The AsyncAPI document that describes the events. The spec's schema
    /// for it is a bare object; no token is needed.
    pub async fn get_async_api_document(&self) -> Result<ApiResponse<Value>, Error> {
        self.json("/v1/asyncapi.json", false).await
    }
}

/// A random (version 4) UUID from the platform CSPRNG, lower-case. A CSPRNG
/// that fails sends nothing, so it is a transport failure before any request.
fn uuid_v4() -> Result<String, Error> {
    let mut b = [0u8; 16];
    getrandom::fill(&mut b).map_err(|e| TransportError::caused_by(TransportKind::Connect, e))?;
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    let hex: String = b.iter().map(|byte| format!("{byte:02x}")).collect();
    Ok(format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..]))
}

#[cfg(test)]
#[path = "../tests/events_tests.rs"]
mod tests;
