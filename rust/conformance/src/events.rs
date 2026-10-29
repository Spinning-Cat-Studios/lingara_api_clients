//! The event steps and operations (conformance/README.md; ADR 30.9.26aa
//! D9): `events` iterates the feed helper to its end, `tail` takes `take`
//! events from the tail helper and then drops it (Rust's cancellation, and
//! the outcome `completed`), and the four new operations' inputs are read
//! from a `call` block. `page_canvases` and `page_dialogues` drive the
//! canvas and dialogue page helpers (ADRs 16.10.26ak D7, 29.10.26c D7); the
//! operations are `canvas.rs`'s and `dialogue.rs`'s.

use futures_util::{Stream, StreamExt as _};
use lingara::Error;
use lingara::events::{Event, EventStart, EventsOptions, InboundEvent, ListEventsParams, SendEventOptions, StreamEventsParams};
use serde_json::Value;

use crate::compare::Observed;
use crate::{canvas, dialogue};
use crate::observe::failed;

pub fn text(block: &Value, key: &str) -> Option<String> {
    block.get(key).and_then(Value::as_str).map(str::to_owned)
}

fn start(block: &Value) -> Result<Option<EventStart>, String> {
    match block.get("start").and_then(Value::as_str) {
        None => Ok(None),
        Some("latest") => Ok(Some(EventStart::Latest)),
        Some("oldest") => Ok(Some(EventStart::Oldest)),
        Some(other) => Err(format!("start: {other} is neither latest nor oldest")),
    }
}

fn types(block: &Value) -> Vec<String> {
    block.get("types").and_then(Value::as_array).map(|t| t.iter().filter_map(Value::as_str).map(str::to_owned).collect()).unwrap_or_default()
}

/// An `events` or `tail` step's block.
pub fn options(block: &Value) -> Result<EventsOptions, String> {
    Ok(EventsOptions { cursor: text(block, "cursor"), start: start(block)?, types: types(block) })
}

/// `listEvents`'s `params`.
pub fn list_params(call: &Value) -> Result<ListEventsParams, String> {
    let params = call.get("params").cloned().unwrap_or(Value::Null);
    let limit = params.get("limit").and_then(Value::as_u64).map(|n| u8::try_from(n).map_err(|_| format!("limit {n}"))).transpose()?;
    Ok(ListEventsParams { cursor: text(&params, "cursor"), start: start(&params)?, types: types(&params), limit })
}

/// `streamEvents`'s `params`.
pub fn stream_params(call: &Value) -> Result<StreamEventsParams, String> {
    let params = call.get("params").cloned().unwrap_or(Value::Null);
    let last_event_id = text(&params, "last_event_id");
    Ok(StreamEventsParams { last_event_id, cursor: text(&params, "cursor"), start: start(&params)?, types: types(&params) })
}

/// `sendEvent`'s body, built through the public `InboundEvent`, and its key.
pub fn send_input(call: &Value) -> Result<(InboundEvent, SendEventOptions), String> {
    let body = call.get("body").cloned().unwrap_or(Value::Null);
    let event: InboundEvent = serde_json::from_value(body).map_err(|e| format!("body: {e}"))?;
    Ok((event, SendEventOptions { idempotency_key: text(call, "idempotency_key") }))
}

/// What a helper yielded: each envelope's id, and the type of each one
/// that was `Unknown`.
#[derive(Default)]
struct Seen {
    ids: Vec<String>,
    unknown: Vec<String>,
}

impl Seen {
    fn push(&mut self, event: &Event) {
        self.ids.push(event.id().to_owned());
        if let Event::Unknown(e) = event {
            self.unknown.push(e.type_.clone());
        }
    }

    fn observed(self, outcome: &'static str, cursor: Option<&str>) -> Observed {
        Observed { outcome, event_ids: self.ids, unknown_types: self.unknown, cursor: cursor.map(str::to_owned), ..Observed::default() }
    }
}

/// Reads `helper` until it ends, or after `take` events; then drops it.
async fn drain<S>(helper: &mut S, take: Option<usize>, cursor: impl Fn(&S) -> Option<String>) -> Observed
where
    S: Stream<Item = Result<Event, Error>> + Unpin,
{
    let mut seen = Seen::default();
    while take != Some(seen.ids.len()) {
        match helper.next().await {
            Some(Ok(event)) => seen.push(&event),
            Some(Err(err)) => {
                let mut observed = failed(err, Vec::new(), None);
                (observed.event_ids, observed.unknown_types, observed.cursor) = (seen.ids, seen.unknown, cursor(helper));
                return observed;
            }
            None => break,
        }
    }
    seen.observed("completed", cursor(helper).as_deref())
}

pub async fn events_step(client: &lingara::Client, block: &Value) -> Result<Observed, String> {
    let mut feed = client.events(options(block)?);
    Ok(drain(&mut feed, None, |f| f.cursor().map(str::to_owned)).await)
}

pub async fn tail_step(client: &lingara::Client, block: &Value) -> Result<Observed, String> {
    let take = block.get("take").and_then(Value::as_u64).ok_or("tail: no take")? as usize;
    let mut tail = client.tail_events(options(block)?);
    let observed = drain(&mut tail, Some(take), |t| t.cursor().map(str::to_owned)).await;
    drop(tail);
    Ok(observed)
}

/// A `page_canvases` step (ADR 16.10.26ak D7): the helper iterated to its
/// end, its items reported as one list under `body`, or its error.
pub async fn page_canvases_step(client: &lingara::Client, block: &Value) -> Result<Observed, String> {
    let filters = canvas::filters(block.get("params").unwrap_or(&Value::Null))?;
    let mut items = Vec::new();
    let mut pages = client.canvases().page_canvases(filters);
    while let Some(item) = pages.next().await {
        match item {
            Ok(item) => items.push(serde_json::to_value(item).map_err(|e| e.to_string())?),
            Err(err) => return Ok(failed(err, Vec::new(), None)),
        }
    }
    Ok(Observed { outcome: "completed", body: Some(Value::Array(items)), ..Observed::default() })
}

/// A `page_dialogues` step (ADR 29.10.26c D7): `page_canvases`' shape over
/// `listDialogues`.
pub async fn page_dialogues_step(client: &lingara::Client, block: &Value) -> Result<Observed, String> {
    let filters = dialogue::filters(block.get("params").unwrap_or(&Value::Null))?;
    let mut items = Vec::new();
    let mut pages = client.dialogues().page_dialogues(filters);
    while let Some(item) = pages.next().await {
        match item {
            Ok(item) => items.push(serde_json::to_value(item).map_err(|e| e.to_string())?),
            Err(err) => return Ok(failed(err, Vec::new(), None)),
        }
    }
    Ok(Observed { outcome: "completed", body: Some(Value::Array(items)), ..Observed::default() })
}
