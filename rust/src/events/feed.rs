//! The feed helper (CONTRACT.md, The event helpers; ADR 30.9.26aa D6):
//! `listEvents` page by page, each item parsed into `Event` from its raw
//! JSON, ending on `has_more: false`. It never sleeps and never polls.

use std::collections::VecDeque;
use std::fmt;
use std::pin::Pin;
use std::task::{Context, Poll};

use futures_core::Stream;
use serde::Deserialize;
use serde_json::Value;

use super::{Event, EventStart, EventsOptions, query, with_query};
use crate::BoxFuture;
use crate::client::Client;
use crate::error::{Error, TransportKind};

/// One page with its items kept raw, so each reaches the parser as the
/// server sent it rather than through the generated `EventEnvelope`.
#[derive(Deserialize)]
struct RawPage {
    items: VecDeque<Value>,
    next_cursor: String,
    has_more: bool,
}

/// Every event from a starting point to the end of the feed:
/// `Stream<Item = Result<Event, Error>>`. After a page's last item is
/// yielded, `cursor()` is that page's next cursor; save it and call
/// `Client::events` again later. Stopping mid-page and resuming from
/// `cursor()` may repeat that page's items: deduplicate by `id`.
pub struct EventFeed {
    client: Client,
    query: Query,
    cursor: Option<String>,
    items: VecDeque<Value>,
    // The current page's `next_cursor` and `has_more`, once it is in.
    page_end: Option<(String, bool)>,
    pending: Option<BoxFuture<'static, Result<RawPage, Error>>>,
    done: bool,
}

/// What every page request repeats: `start` is dropped once a cursor
/// exists, which is the server's own precedence.
struct Query {
    start: Option<EventStart>,
    types: Vec<String>,
}

impl EventFeed {
    pub(crate) fn new(client: Client, options: EventsOptions) -> Self {
        let query = Query { start: options.start, types: options.types };
        Self { client, query, cursor: options.cursor, items: VecDeque::new(), page_end: None, pending: None, done: false }
    }

    /// Where to resume from: the caller's cursor until the first page's
    /// last item is yielded (or an empty page arrives), then each page's
    /// `next_cursor` in turn.
    pub fn cursor(&self) -> Option<&str> {
        self.cursor.as_deref()
    }

    /// The next event, `None` at the end of the feed. The same as
    /// `StreamExt::next`, without importing it.
    pub async fn next(&mut self) -> Option<Result<Event, Error>> {
        std::future::poll_fn(|cx| self.poll_event(cx)).await
    }

    fn poll_event(&mut self, cx: &mut Context<'_>) -> Poll<Option<Result<Event, Error>>> {
        loop {
            if self.done {
                return Poll::Ready(None);
            }
            if let Some(item) = self.items.pop_front() {
                return Poll::Ready(Some(self.yield_item(item)));
            }
            if let Some((_, has_more)) = &self.page_end {
                if !has_more {
                    self.done = true;
                    continue;
                }
                self.page_end = None;
            }
            let pending = self.pending.get_or_insert_with(|| fetch(&self.client, &self.query, self.cursor.as_deref()));
            let page = std::task::ready!(pending.as_mut().poll(cx));
            self.pending = None;
            match page {
                Ok(page) => self.take_page(page),
                Err(err) => return self.fail(err),
            }
        }
    }

    fn take_page(&mut self, page: RawPage) {
        if page.items.is_empty() {
            self.cursor = Some(page.next_cursor.clone());
        }
        self.items = page.items;
        self.page_end = Some((page.next_cursor, page.has_more));
    }

    /// The cursor moves when a page's last item goes out.
    fn yield_item(&mut self, item: Value) -> Result<Event, Error> {
        let Ok(event) = serde_json::from_value::<Event>(item) else {
            self.done = true;
            return Err(TransportKind::MalformedEvent.into());
        };
        if self.items.is_empty() {
            self.cursor = self.page_end.as_ref().map(|(cursor, _)| cursor.clone());
        }
        Ok(event)
    }

    fn fail(&mut self, err: Error) -> Poll<Option<Result<Event, Error>>> {
        self.done = true;
        Poll::Ready(Some(Err(err)))
    }
}

/// One `GET /v1/events` from `cursor`, or from `start` without one.
fn fetch(client: &Client, q: &Query, cursor: Option<&str>) -> BoxFuture<'static, Result<RawPage, Error>> {
    let start = if cursor.is_some() { None } else { q.start };
    let path = with_query("/v1/events", &query(cursor, start, &q.types, None));
    let client = client.clone();
    Box::pin(async move { client.json::<RawPage>(&path, true).await.map(|res| res.into_inner()) })
}

impl Stream for EventFeed {
    type Item = Result<Event, Error>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.get_mut().poll_event(cx)
    }
}

impl fmt::Debug for EventFeed {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("EventFeed").field("cursor", &self.cursor).field("done", &self.done).finish()
    }
}
