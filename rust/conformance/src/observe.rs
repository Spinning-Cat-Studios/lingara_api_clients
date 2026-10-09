//! A call's result, in the contract's vocabulary: its outcome, its body or
//! events, and a raised error's snake_case fields and renderings.

use std::fmt::Debug;

use lingara::embed::MintedToken;
use lingara::{ApiResponse, Error, EventStream};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Map, Value, json};

use crate::compare::Observed;

pub fn json_result<T: Serialize + Debug>(result: Result<ApiResponse<T>, Error>) -> Observed {
    match result {
        Ok(res) => completed(&res, 200, serde_json::to_value(&*res).ok()),
        Err(err) => failed(err, Vec::new(), None),
    }
}

/// A bodiless answer (ADR 1.10.26w D4): the result carries only the echo.
pub fn empty_result(result: Result<ApiResponse<()>, Error>) -> Observed {
    match result {
        Ok(res) => completed(&res, 204, None),
        Err(err) => failed(err, Vec::new(), None),
    }
}

/// A mint's result, its body in wire form read through the one exposing
/// accessor; its renderings still go to the `redacted` scan.
pub fn minted_result(result: Result<ApiResponse<MintedToken>, Error>) -> Observed {
    match result {
        Ok(res) => {
            let body = json!({
                "token": res.token.expose_secret(), "expires_at": res.expires_at, "expires_in": res.expires_in.as_secs(),
                "subject": res.subject, "scopes": res.scopes, "account_linked": res.account_linked,
            });
            completed(&res, 200, Some(body))
        }
        Err(err) => failed(err, Vec::new(), None),
    }
}

/// A completed call: its result's renderings join the `redacted` scan.
fn completed<T: Debug>(res: &ApiResponse<T>, status: u16, body: Option<Value>) -> Observed {
    Observed {
        outcome: "completed",
        status: Some(status),
        body,
        served_version: res.served_version().map(str::to_owned),
        renderings: vec![format!("{res:?}"), format!("{res:#?}")],
        ..Observed::default()
    }
}

/// Drains a stream; after `cancel` events, drops it: Rust's cancellation.
pub async fn stream<E: Serialize + DeserializeOwned>(opened: Result<EventStream<E>, Error>, cancel: Option<usize>) -> Observed {
    let mut events_stream = match opened {
        Ok(s) => s,
        Err(err) => return failed(err, Vec::new(), None),
    };
    let served_version = events_stream.served_version().map(str::to_owned);
    let mut events = Vec::new();
    while let Some(item) = events_stream.next().await {
        match item {
            Ok(event) => events.push(serde_json::to_value(event).unwrap_or(Value::Null)),
            Err(err) => return failed(err, events, served_version),
        }
        if cancel == Some(events.len()) {
            drop(events_stream);
            return Observed { outcome: "cancelled", events, served_version, ..Observed::default() };
        }
    }
    Observed { outcome: "completed", status: Some(200), events, served_version, ..Observed::default() }
}

pub fn failed(err: Error, events: Vec<Value>, served_version: Option<String>) -> Observed {
    let mut renderings = vec![err.to_string(), format!("{err:?}"), format!("{err:#?}")];
    let mut source = std::error::Error::source(&err);
    while let Some(s) = source {
        renderings.extend([s.to_string(), format!("{s:?}")]);
        source = s.source();
    }
    Observed { outcome: "error", events, error: Some(error_fields(&err)), served_version, renderings, ..Observed::default() }
}

/// The contract's snake_case fields of a raised error.
fn error_fields(err: &Error) -> (String, Map<String, Value>) {
    let secs = |d: Option<std::time::Duration>| d.map(|d| d.as_secs());
    let (variant, fields) = match err {
        Error::Api(e) => ("ApiError", json!({ "status": e.status, "code": e.code, "message": e.message, "retry_after": secs(e.retry_after), "plan_id": e.plan_id, "served_version": e.served_version })),
        Error::OAuth(e) => ("OAuthError", json!({ "status": e.status, "error": e.error, "description": e.description, "retry_after": secs(e.retry_after) })),
        Error::Maintenance(e) => ("MaintenanceError", json!({ "body": e.body, "retry_after": secs(e.retry_after) })),
        Error::Transport(e) => ("TransportError", json!({ "kind": e.kind.as_str() })),
        other => ("not a known variant", json!({ "debug": format!("{other:?}") })),
    };
    (variant.to_owned(), fields.as_object().cloned().unwrap_or_default())
}
